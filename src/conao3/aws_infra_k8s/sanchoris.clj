(ns conao3.aws-infra-k8s.sanchoris
  "sanchoris (conao3/sanchoris) の基盤。制御側はサーバーレス: API Gateway の HTTP API (画面、webhook、OAuth) と
  WebSocket API (Run channel) の Lambda、DynamoDB (main は stream 付き、audit は追記だけ)、S3 (docs / records / logs、SSE-KMS)、
  SQS FIFO の作業キューとそれを消費する worker の Lambda、main の stream を作業キューと EventBridge Scheduler に渡す relay の Lambda。
  Workspace だけが ECS Fargate で動き、外向きの通信は中継 (relay のプロキシ。このスタックには含めない) だけに出られる。
  認証は共有の Cognito ユーザープール (cognito スタック。先にデプロイする) の JWT オーソライザー。k8s には載せない。
  表の定義は sanchoris の `infra/tables.json`、Lambda のコードは sanchoris の `target/lambda/<関数>/bootstrap.zip`。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(def http-timeout-sec 29)
(def worker-timeout-sec 300)
(def work-visibility-timeout-sec (* 6 worker-timeout-sec))
(def relay-timeout-sec 60)
(def log-retention-days 30)
;; Plan の retentionDays の最大 (infra/plans.json)。期限前の削除はアプリが Plan ごとに行う
(def record-retention-days 90)
(def vpc-cidr "10.1.0.0/16")
(def public-subnet-cidr "10.1.0.0/24")
(def workspace-subnet-cidr "10.1.16.0/20")
(def relay-port 3128)

;; --- Lambda 関数 ---
;; :env は環境変数のまとまり (env-vars)、:grants は IAM の許可のまとまり (grant-statements)。
;; 関数を足す・分けるときはこの一覧と、トリガー (HTTP API・WebSocket API・作業キュー・stream) の参照先を直す

(def functions
  [{:id "Api" :name "api" :timeout http-timeout-sec :memory 512
    :env [:tables :docs :cognito :urls :github-app :github-oauth :stripe :run-token :web-signing :credential :records :git]
    :grants [:tables :docs :records :logs-read]}
   {:id "Channel" :name "channel" :timeout http-timeout-sec :memory 256
    :env [:tables :docs :run-token :records]
    :grants [:tables :docs :records :logs-write :connections]}
   {:id "Worker" :name "worker" :timeout worker-timeout-sec :memory 512
    :env [:tables :docs :urls :github-app :run-token :credential :records :git :ecs]
    :grants [:tables :docs :records :logs-write :work-consume :ecs :connections]}
   {:id "Relay" :name "relay" :timeout relay-timeout-sec :memory 256
    :env [:tables :docs :work-queue]
    :grants [:docs-read :stream :work-send :schedules]}])

(def http-function "Api")
(def channel-function "Channel")
(def work-function "Worker")
(def stream-function "Relay")

;; HTTP API のルート。:jwt は Cognito の JWT オーソライザーを付ける。webhook と OAuth は署名と state をアプリが検証する
(def http-routes
  [["ANY /api/{proxy+}" :jwt]
   ["GET /health" nil]
   ["POST /webhooks/github" nil]
   ["POST /webhooks/stripe" nil]
   ["POST /auth/github" nil]
   ["GET /auth/github/setup" nil]
   ["GET /auth/github/callback" nil]])

(def channel-routes ["$connect" "$disconnect" "$default"])

;; --- SSM パラメータ (String、`/<prefix>/sanchoris/<名前>`)。値は手で作り、テンプレートは動的参照で読む ---

(def secrets
  [["GITHUB_APP_ID" "github-app-id"]
   ["GITHUB_APP_PRIVATE_KEY" "github-app-private-key"]
   ["GITHUB_APP_CLIENT_ID" "github-app-client-id"]
   ["GITHUB_APP_CLIENT_SECRET" "github-app-client-secret"]
   ["GITHUB_APP_SLUG" "github-app-slug"]
   ["GITHUB_WEBHOOK_SECRET" "github-webhook-secret"]
   ["STRIPE_SECRET_KEY" "stripe-secret-key"]
   ["STRIPE_WEBHOOK_SECRET" "stripe-webhook-secret"]
   ["RUN_TOKEN_SIGNING_KEY" "run-token-signing-key"]
   ["TASK_TOKEN_SIGNING_KEY" "task-token-signing-key"]
   ["OAUTH_STATE_SIGNING_KEY" "oauth-state-signing-key"]
   ["SANCHORIS_CREDENTIAL_KEY" "credential-key"]])

;; EventBridge Scheduler の定期実行 (architecture.md「Rate schedules」)
(def rate-schedules
  [["SweepConfigs" "sweep_configs" "rate(5 minutes)"]
   ["SweepChecks" "sweep_checks" "rate(1 minute)"]
   ["SweepTaskLimits" "sweep_task_limits" "rate(1 minute)"]
   ["UsageAlerts" "usage_alerts" "rate(15 minutes)"]])

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})
(defn- prefix-name [suffix] (sub (str "${Prefix}-sanchoris-" suffix)))
(defn- import-value [export-suffix]
  {"Fn::ImportValue" (sub (str "${Prefix}-" export-suffix))})

(defn- secret-ref [ssm-name]
  (sub (str "{{resolve:ssm:/${Prefix}/sanchoris/" ssm-name "}}")))

(defn- secret-env [& env-names]
  (into {}
        (for [[env-name ssm-name] secrets
              :when (some #{env-name} env-names)]
          [(keyword env-name) (secret-ref ssm-name)])))

;; --- 表 (sanchoris の infra/tables.json) ---

(defn read-tables
  "sanchoris の `infra/tables.json` を読む。キーはキーワード (CloudFormation のプロパティ名のまま)。"
  [file]
  (json/parse-string (slurp (str file)) true))

(defn- table-id [table] (keyword (:logicalId table)))
(defn- append-only? [table] (:appendOnly table))

(defn resource-table [table]
  {:Type "AWS::DynamoDB::Table"
   :Properties (assoc (:properties table) :TableName (prefix-name (:role table)))})

(defn- table-arns [table]
  [(get-att (table-id table) :Arn)
   (sub (str "${" (:logicalId table) ".Arn}/index/*"))])

(defn- table-statements
  "表ごとの許可。追記だけの表は Put と読み取りだけで、appendOnlyDeniedActions を明示的に拒否する。
   readOnlyLeadingKeys のパーティションへの書き込みも拒否する (Plan の一覧はデプロイ時に管理者が書く)。"
  [{:keys [tables appendOnlyDeniedActions readOnlyLeadingKeys]}]
  (vec
   (mapcat
    (fn [table]
      (if (append-only? table)
        [{:Effect "Allow"
          :Action ["dynamodb:PutItem" "dynamodb:GetItem" "dynamodb:Query" "dynamodb:ConditionCheckItem"]
          :Resource (table-arns table)}
         {:Effect "Deny"
          :Action appendOnlyDeniedActions
          :Resource (table-arns table)}]
        [{:Effect "Allow"
          :Action ["dynamodb:GetItem" "dynamodb:BatchGetItem" "dynamodb:Query" "dynamodb:PutItem"
                   "dynamodb:UpdateItem" "dynamodb:DeleteItem" "dynamodb:ConditionCheckItem"]
          :Resource (table-arns table)}
         {:Effect "Deny"
          :Action ["dynamodb:PutItem" "dynamodb:UpdateItem" "dynamodb:DeleteItem" "dynamodb:BatchWriteItem"]
          :Resource (table-arns table)
          :Condition {"ForAnyValue:StringEquals" {"dynamodb:LeadingKeys" readOnlyLeadingKeys}}}]))
    tables)))

(defn- stream-tables [table-spec]
  (filter #(contains? (:streamFilters table-spec) (table-id %)) (:tables table-spec)))

;; --- KMS と S3 ---

(defn resource-key []
  {:Type "AWS::KMS::Key"
   :Properties
   {:Description (sub "${Prefix}-sanchoris: S3 buckets (docs, records, logs)")
    :EnableKeyRotation true
    :KeyPolicy
    {:Version "2012-10-17"
     :Statement [{:Sid "AccountAdministration"
                  :Effect "Allow"
                  :Principal {:AWS (sub "arn:aws:iam::${AWS::AccountId}:root")}
                  :Action "kms:*"
                  :Resource "*"}]}}})

(defn resource-key-alias []
  {:Type "AWS::KMS::Alias"
   :Properties
   {:AliasName (sub "alias/${Prefix}-sanchoris")
    :TargetKeyId {:Ref :Key}}})

(defn- tenant-key-alias-prefix [] (sub "alias/${Prefix}-sanchoris-tenant-"))

(defn resource-bucket [expiration-days]
  {:Type "AWS::S3::Bucket"
   :Properties
   {:PublicAccessBlockConfiguration {:BlockPublicAcls true
                                     :BlockPublicPolicy true
                                     :IgnorePublicAcls true
                                     :RestrictPublicBuckets true}
    :OwnershipControls {:Rules [{:ObjectOwnership "BucketOwnerEnforced"}]}
    :BucketEncryption {:ServerSideEncryptionConfiguration
                       [{:ServerSideEncryptionByDefault {:SSEAlgorithm "aws:kms"
                                                         :KMSMasterKeyID (get-att :Key :Arn)}
                         :BucketKeyEnabled true}]}
    :LifecycleConfiguration
    {:Rules [(cond-> {:Id "cleanup"
                      :Status "Enabled"
                      :AbortIncompleteMultipartUpload {:DaysAfterInitiation 1}}
               expiration-days (assoc :ExpirationInDays expiration-days))]}}})

(defn resource-bucket-policy [bucket]
  {:Type "AWS::S3::BucketPolicy"
   :Properties
   {:Bucket {:Ref bucket}
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Sid "DenyInsecureTransport"
       :Effect "Deny"
       :Principal "*"
       :Action "s3:*"
       :Resource [(get-att bucket :Arn)
                  (sub (str "${" (name bucket) ".Arn}/*"))]
       :Condition {:Bool {"aws:SecureTransport" "false"}}}]}}})

(defn- bucket-statements
  "ListBucket は、無いキーの GetObject を 403 でなく 404 にするため。"
  [bucket actions]
  [{:Effect "Allow"
    :Action actions
    :Resource (sub (str "${" (name bucket) ".Arn}/*"))}
   {:Effect "Allow"
    :Action "s3:ListBucket"
    :Resource (get-att bucket :Arn)}
   {:Effect "Allow"
    :Action ["kms:GenerateDataKey" "kms:Decrypt"]
    :Resource (get-att :Key :Arn)}])

(defn- tenant-key-statement
  "Tenant ごとの KMS の鍵 (`<KMS_KEY_PREFIX><tenant id>` の別名) で記録を暗号化・復号する。"
  []
  {:Effect "Allow"
   :Action ["kms:GenerateDataKey" "kms:Decrypt" "kms:Encrypt" "kms:DescribeKey"]
   :Resource (sub "arn:aws:kms:${AWS::Region}:${AWS::AccountId}:key/*")
   :Condition {"ForAnyValue:StringLike" {"kms:ResourceAliases" (sub "alias/${Prefix}-sanchoris-tenant-*")}}})

;; --- 作業キュー (SQS FIFO) と EventBridge Scheduler ---

(defn- secure-transport-deny [resource-arn]
  {:Sid "DenyInsecureTransport"
   :Effect "Deny"
   :Principal "*"
   :Action "sqs:*"
   :Resource resource-arn
   :Condition {:Bool {"aws:SecureTransport" "false"}}})

(defn resource-work-dlq []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "work-dlq.fifo")
    :FifoQueue true
    :MessageRetentionPeriod 1209600
    :SqsManagedSseEnabled true}})

(defn resource-work-queue []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "work.fifo")
    :FifoQueue true
    :ContentBasedDeduplication true
    :VisibilityTimeout work-visibility-timeout-sec
    :MessageRetentionPeriod 345600
    :SqsManagedSseEnabled true
    :RedrivePolicy {:deadLetterTargetArn (get-att :WorkDlq :Arn)
                    :maxReceiveCount 5}}})

(defn resource-relay-dlq
  "relay が処理できなかった stream のレコード (再試行の後) の置き場。"
  []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "relay-dlq")
    :MessageRetentionPeriod 1209600
    :SqsManagedSseEnabled true}})

(defn resource-queue-policy [queues]
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues (mapv (fn [q] {:Ref q}) queues)
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny (mapv #(get-att % :Arn) queues))]}}})

(defn resource-schedule-group []
  {:Type "AWS::Scheduler::ScheduleGroup"
   :Properties {:Name (sub "${Prefix}-sanchoris")}})

(defn resource-scheduler-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "scheduler.amazonaws.com"}
                  :Action "sts:AssumeRole"
                  :Condition {:StringEquals {"aws:SourceAccount" {:Ref "AWS::AccountId"}}}}]}
    :Policies
    [{:PolicyName "sanchoris-scheduler"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement [{:Effect "Allow"
                    :Action "sqs:SendMessage"
                    :Resource (get-att :WorkQueue :Arn)}]}}]}})

(defn resource-rate-schedule [job rate]
  {:Type "AWS::Scheduler::Schedule"
   :Properties
   {:Name (prefix-name (str/replace job "_" "-"))
    :GroupName {:Ref :ScheduleGroup}
    :ScheduleExpression rate
    :FlexibleTimeWindow {:Mode "OFF"}
    :State "ENABLED"
    :Target
    {:Arn (get-att :WorkQueue :Arn)
     :RoleArn (get-att :SchedulerRole :Arn)
     :Input (json/generate-string {:id "00000000-0000-0000-0000-000000000000"
                                   :job {:type job}
                                   :scheduledTime "<aws.scheduler.scheduled-time>"})
     :SqsParameters {:MessageGroupId (str "sweep:" job)}}}})

;; --- ネットワークと ECS (Workspace) ---
;; Workspace のサブネットはインターネットへの経路を持たない。外向きは中継 (relay のプロキシ) と、
;; イメージの取得とログのための VPC エンドポイントだけ。中継は公開サブネットに置く (このスタックには含めない)

(defn resource-vpc []
  {:Type "AWS::EC2::VPC"
   :Properties
   {:CidrBlock vpc-cidr
    :EnableDnsSupport true
    :EnableDnsHostnames true
    :Tags [{:Key "Name" :Value (prefix-name "vpc")}]}})

(defn- availability-zone [] {"Fn::Select" [0 {"Fn::GetAZs" ""}]})

(defn resource-subnet [cidr tag]
  {:Type "AWS::EC2::Subnet"
   :Properties
   {:VpcId {:Ref :Vpc}
    :CidrBlock cidr
    :AvailabilityZone (availability-zone)
    :MapPublicIpOnLaunch false
    :Tags [{:Key "Name" :Value (prefix-name tag)}]}})

(defn resource-route-table [tag]
  {:Type "AWS::EC2::RouteTable"
   :Properties
   {:VpcId {:Ref :Vpc}
    :Tags [{:Key "Name" :Value (prefix-name tag)}]}})

(defn resource-route-table-association [subnet route-table]
  {:Type "AWS::EC2::SubnetRouteTableAssociation"
   :Properties {:SubnetId {:Ref subnet} :RouteTableId {:Ref route-table}}})

(defn resource-internet-gateway []
  {:Type "AWS::EC2::InternetGateway"
   :Properties {:Tags [{:Key "Name" :Value (prefix-name "igw")}]}})

(defn resource-gateway-attachment []
  {:Type "AWS::EC2::VPCGatewayAttachment"
   :Properties {:VpcId {:Ref :Vpc} :InternetGatewayId {:Ref :InternetGateway}}})

(defn resource-public-route []
  {:Type "AWS::EC2::Route"
   :DependsOn ["GatewayAttachment"]
   :Properties
   {:RouteTableId {:Ref :PublicRouteTable}
    :DestinationCidrBlock "0.0.0.0/0"
    :GatewayId {:Ref :InternetGateway}}})

(defn resource-security-group [description]
  {:Type "AWS::EC2::SecurityGroup"
   :Properties
   {:GroupDescription description
    :VpcId {:Ref :Vpc}
    :SecurityGroupEgress [{:IpProtocol "-1" :CidrIp "127.0.0.1/32" :Description "no default egress"}]}})

(defn resource-egress [group-id rule]
  {:Type "AWS::EC2::SecurityGroupEgress"
   :Properties (merge {:GroupId {:Ref group-id} :IpProtocol "tcp"} rule)})

(defn resource-ingress [group-id rule]
  {:Type "AWS::EC2::SecurityGroupIngress"
   :Properties (merge {:GroupId {:Ref group-id} :IpProtocol "tcp"} rule)})

(defn- account-only-endpoint-policy []
  {:Version "2012-10-17"
   :Statement [{:Effect "Allow"
                :Principal "*"
                :Action "*"
                :Resource "*"
                :Condition {:StringEquals {"aws:PrincipalAccount" {:Ref "AWS::AccountId"}}}}]})

(defn resource-interface-endpoint [service]
  {:Type "AWS::EC2::VPCEndpoint"
   :Condition "UseWorkspaceEndpoints"
   :Properties
   {:VpcId {:Ref :Vpc}
    :ServiceName (sub (str "com.amazonaws.${AWS::Region}." service))
    :VpcEndpointType "Interface"
    :PrivateDnsEnabled true
    :SubnetIds [{:Ref :WorkspaceSubnet}]
    :SecurityGroupIds [{:Ref :EndpointSecurityGroup}]
    :PolicyDocument (account-only-endpoint-policy)}})

(defn resource-s3-endpoint
  "ECR のイメージのレイヤーは S3 から取る。ゲートウェイ型 (無料) を Workspace の経路表にだけ付け、ECR のレイヤーのバケットだけを許す。"
  []
  {:Type "AWS::EC2::VPCEndpoint"
   :Properties
   {:VpcId {:Ref :Vpc}
    :ServiceName (sub "com.amazonaws.${AWS::Region}.s3")
    :VpcEndpointType "Gateway"
    :RouteTableIds [{:Ref :WorkspaceRouteTable}]
    :PolicyDocument {:Version "2012-10-17"
                     :Statement [{:Effect "Allow"
                                  :Principal "*"
                                  :Action "s3:GetObject"
                                  :Resource (sub "arn:aws:s3:::prod-${AWS::Region}-starport-layer-bucket/*")}]}}})

(defn resource-workspace-repository []
  {:Type "AWS::ECR::Repository"
   :Properties
   {:RepositoryName (prefix-name "workspace")
    :ImageScanningConfiguration {:ScanOnPush true}
    :LifecyclePolicy
    {:LifecyclePolicyText
     (json/generate-string
      {:rules [{:rulePriority 1
                :description "keep the last 10 images"
                :selection {:tagStatus "any" :countType "imageCountMoreThan" :countNumber 10}
                :action {:type "expire"}}]})}}})

(defn resource-cluster []
  {:Type "AWS::ECS::Cluster"
   :Properties
   {:ClusterName (sub "${Prefix}-sanchoris")
    :CapacityProviders ["FARGATE" "FARGATE_SPOT"]
    :ClusterSettings [{:Name "containerInsights" :Value "disabled"}]}})

(defn resource-workspace-execution-role
  "Fargate がイメージを取り、ログを書くためのロール。Workspace の中のプロセスには渡らない。"
  []
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "ecs-tasks.amazonaws.com"}
                  :Action "sts:AssumeRole"
                  :Condition {:StringEquals {"aws:SourceAccount" {:Ref "AWS::AccountId"}}}}]}
    :Policies
    [{:PolicyName "sanchoris-workspace-execution"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement [{:Effect "Allow"
                    :Action "ecr:GetAuthorizationToken"
                    :Resource "*"}
                   {:Effect "Allow"
                    :Action ["ecr:BatchGetImage" "ecr:GetDownloadUrlForLayer" "ecr:BatchCheckLayerAvailability"]
                    :Resource (get-att :WorkspaceRepository :Arn)}
                   {:Effect "Allow"
                    :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
                    :Resource (get-att :WorkspaceLogGroup :Arn)}]}}]}})

(defn resource-workspace-task-definition
  "Workspace のタスク定義。タスクロールは持たない (エージェントは AWS の API を使わない。制御側とは Run channel でだけ話す)。
   Run ごとの値 (Run のトークン、中継の URL、Run channel の URL) は worker が RunTask の overrides で渡す。"
  []
  {:Type "AWS::ECS::TaskDefinition"
   :Properties
   {:Family (prefix-name "workspace")
    :RequiresCompatibilities ["FARGATE"]
    :NetworkMode "awsvpc"
    :Cpu {:Ref :WorkspaceCpu}
    :Memory {:Ref :WorkspaceMemory}
    :RuntimePlatform {:CpuArchitecture "ARM64" :OperatingSystemFamily "LINUX"}
    :ExecutionRoleArn (get-att :WorkspaceExecutionRole :Arn)
    :ContainerDefinitions
    [{:Name "workspace"
      :Image (sub "${WorkspaceRepository.RepositoryUri}:${WorkspaceImageTag}")
      :Essential true
      :User "1000:1000"
      :StopTimeout 120
      :LinuxParameters {:InitProcessEnabled true}
      :LogConfiguration {:LogDriver "awslogs"
                         :Options {"awslogs-group" {:Ref :WorkspaceLogGroup}
                                   "awslogs-region" {:Ref "AWS::Region"}
                                   "awslogs-stream-prefix" "workspace"}}}]}})

;; --- Lambda (IAM、環境変数、関数) ---

(defn resource-log-group [log-group-name]
  {:Type "AWS::Logs::LogGroup"
   :Properties {:LogGroupName log-group-name
                :RetentionInDays log-retention-days}})

(defn- function-log-group-id [f] (keyword (str (:id f) "LogGroup")))
(defn- function-role-id [f] (keyword (str (:id f) "FunctionRole")))
(defn- function-id [f] (keyword (str (:id f) "Function")))
(defn- function-arn [id] (get-att (keyword (str id "Function")) :Arn))

(defn- control-url []
  {"Fn::If" ["UseApiDomains"
             (sub "wss://${ChannelDomainName}")
             (sub "wss://${ChannelApi}.execute-api.${AWS::Region}.amazonaws.com/${ChannelStage}")]})

(defn- public-url []
  {"Fn::If" ["UseApiDomains"
             (sub "https://${ApiDomainName}")
             (get-att :HttpApi :ApiEndpoint)]})

(defn env-vars
  "環境変数のまとまり。名前と意味は sanchoris の README.md「Environment」と docs/architecture.md「Environment」。"
  [group table-spec]
  (case group
    :tables (into {} (for [table (:tables table-spec)]
                       [(keyword (:tableNameEnv table)) {:Ref (table-id table)}]))
    :docs {:SANCHORIS_DOCS_BUCKET {:Ref :DocsBucket}}
    :cognito {:COGNITO_USER_POOL_ID (import-value "UserPool")
              :COGNITO_CLIENT_ID (import-value "SanchorisUserPoolClient")}
    :urls {:SANCHORIS_PUBLIC_URL (public-url)
           :SANCHORIS_CONTROL_URL (control-url)}
    :github-app (merge {:GITHUB_URL "https://github.com"
                        :GITHUB_API_URL "https://api.github.com"}
                       (secret-env "GITHUB_APP_ID" "GITHUB_APP_PRIVATE_KEY"))
    :github-oauth (secret-env "GITHUB_APP_CLIENT_ID" "GITHUB_APP_CLIENT_SECRET" "GITHUB_APP_SLUG" "GITHUB_WEBHOOK_SECRET")
    :stripe (merge {:STRIPE_API_URL "https://api.stripe.com"}
                   (secret-env "STRIPE_SECRET_KEY" "STRIPE_WEBHOOK_SECRET"))
    :run-token (secret-env "RUN_TOKEN_SIGNING_KEY")
    :web-signing (secret-env "TASK_TOKEN_SIGNING_KEY" "OAUTH_STATE_SIGNING_KEY")
    :credential (secret-env "SANCHORIS_CREDENTIAL_KEY")
    :records {:SANCHORIS_RECORDS_STORE "s3"
              :S3_RECORDS_BUCKET {:Ref :RecordsBucket}
              :KMS_KEY_PREFIX (tenant-key-alias-prefix)}
    :git {:SANCHORIS_GIT_URL "https://github.com"}
    :work-queue {:SANCHORIS_WORK_QUEUE_URL {:Ref :WorkQueue}
                 :SANCHORIS_WORK_QUEUE_ARN (get-att :WorkQueue :Arn)
                 :SANCHORIS_SCHEDULE_GROUP {:Ref :ScheduleGroup}
                 :SANCHORIS_SCHEDULER_ROLE_ARN (get-att :SchedulerRole :Arn)}
    :ecs {:ECS_CLUSTER {:Ref :Cluster}
          :ECS_TASK_DEFINITION (prefix-name "workspace")
          :ECS_SUBNETS {:Ref :WorkspaceSubnet}
          :ECS_SECURITY_GROUPS {:Ref :WorkspaceSecurityGroup}}))

(defn grant-statements
  "IAM の許可のまとまり。"
  [grant table-spec]
  (case grant
    :tables (table-statements table-spec)
    :docs (bucket-statements :DocsBucket ["s3:GetObject" "s3:PutObject"])
    :docs-read (bucket-statements :DocsBucket ["s3:GetObject"])
    :records (conj (bucket-statements :RecordsBucket ["s3:GetObject" "s3:PutObject" "s3:DeleteObject"])
                   (tenant-key-statement))
    :logs-read (bucket-statements :LogsBucket ["s3:GetObject"])
    :logs-write (bucket-statements :LogsBucket ["s3:GetObject" "s3:PutObject" "s3:DeleteObject"])
    :connections [{:Effect "Allow"
                   :Action "execute-api:ManageConnections"
                   :Resource (sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${ChannelApi}/*/*/@connections/*")}]
    :work-consume [{:Effect "Allow"
                    :Action ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes" "sqs:ChangeMessageVisibility"]
                    :Resource (get-att :WorkQueue :Arn)}]
    :work-send [{:Effect "Allow"
                 :Action "sqs:SendMessage"
                 :Resource (get-att :WorkQueue :Arn)}]
    :schedules [{:Effect "Allow"
                 :Action ["scheduler:CreateSchedule" "scheduler:DeleteSchedule" "scheduler:GetSchedule"]
                 :Resource (sub "arn:aws:scheduler:${AWS::Region}:${AWS::AccountId}:schedule/${ScheduleGroup}/*")}
                {:Effect "Allow"
                 :Action "iam:PassRole"
                 :Resource (get-att :SchedulerRole :Arn)
                 :Condition {:StringEquals {"iam:PassedToService" "scheduler.amazonaws.com"}}}]
    :stream (into [{:Effect "Allow"
                    :Action "sqs:SendMessage"
                    :Resource (get-att :RelayDlq :Arn)}]
                  (for [table (stream-tables table-spec)]
                    {:Effect "Allow"
                     :Action ["dynamodb:DescribeStream" "dynamodb:GetRecords" "dynamodb:GetShardIterator" "dynamodb:ListStreams"]
                     :Resource (get-att (table-id table) :StreamArn)}))
    :ecs [{:Effect "Allow"
           :Action "ecs:RunTask"
           :Resource (sub "arn:aws:ecs:${AWS::Region}:${AWS::AccountId}:task-definition/${Prefix}-sanchoris-workspace:*")
           :Condition {:ArnEquals {"ecs:cluster" (get-att :Cluster :Arn)}}}
          {:Effect "Allow"
           :Action ["ecs:StopTask" "ecs:DescribeTasks" "ecs:TagResource"]
           :Resource (sub "arn:aws:ecs:${AWS::Region}:${AWS::AccountId}:task/${Cluster}/*")}
          {:Effect "Allow"
           :Action "iam:PassRole"
           :Resource (get-att :WorkspaceExecutionRole :Arn)
           :Condition {:StringEquals {"iam:PassedToService" "ecs-tasks.amazonaws.com"}}}]))

(defn resource-function-role [f table-spec]
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "lambda.amazonaws.com"}
                  :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName (str "sanchoris-" (:name f))
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       (into [{:Effect "Allow"
               :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
               :Resource (get-att (function-log-group-id f) :Arn)}]
             (mapcat #(grant-statements % table-spec) (:grants f)))}}]}})

(defn resource-function [f table-spec code-dir]
  {:Type "AWS::Serverless::Function"
   :DependsOn [(name (function-log-group-id f))]
   :Properties
   {:FunctionName (prefix-name (:name f))
    :LoggingConfig {:LogFormat "JSON"}
    :Runtime "provided.al2023"
    :Architectures ["arm64"]
    :Handler "bootstrap"
    :CodeUri (str (fs/path code-dir (:name f) "bootstrap.zip"))
    :Timeout (:timeout f)
    :MemorySize (:memory f)
    :Role (get-att (function-role-id f) :Arn)
    :Environment {:Variables (apply merge (map #(env-vars % table-spec) (:env f)))}}})

;; --- Lambda のトリガー (作業キュー、DynamoDB Streams) ---

(defn resource-work-event-source []
  {:Type "AWS::Lambda::EventSourceMapping"
   :Properties
   {:FunctionName {:Ref (keyword (str work-function "Function"))}
    :EventSourceArn (get-att :WorkQueue :Arn)
    :BatchSize 10
    :FunctionResponseTypes ["ReportBatchItemFailures"]
    :ScalingConfig {:MaximumConcurrency {:Ref :WorkerMaxConcurrency}}}})

(defn resource-stream-event-source [table table-spec]
  {:Type "AWS::Lambda::EventSourceMapping"
   :Properties
   {:FunctionName {:Ref (keyword (str stream-function "Function"))}
    :EventSourceArn (get-att (table-id table) :StreamArn)
    :StartingPosition "LATEST"
    :BatchSize 100
    :MaximumBatchingWindowInSeconds 0
    :BisectBatchOnFunctionError true
    :MaximumRetryAttempts 10
    :FunctionResponseTypes ["ReportBatchItemFailures"]
    :FilterCriteria {:Filters (get-in table-spec [:streamFilters (table-id table)])}
    :DestinationConfig {:OnFailure {:Destination (get-att :RelayDlq :Arn)}}}})

;; --- API Gateway: HTTP API (画面、webhook、OAuth) ---

(defn- access-log-format [fields]
  (json/generate-string fields))

(defn resource-http-api []
  {:Type "AWS::ApiGatewayV2::Api"
   :Properties
   {:Name (prefix-name "http")
    :ProtocolType "HTTP"
    :DisableExecuteApiEndpoint {"Fn::If" ["UseApiDomains" true false]}}})

(defn resource-http-authorizer []
  {:Type "AWS::ApiGatewayV2::Authorizer"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :Name "cognito"
    :AuthorizerType "JWT"
    :IdentitySource ["$request.header.Authorization"]
    :JwtConfiguration
    {:Audience [(import-value "SanchorisUserPoolClient")]
     :Issuer {"Fn::Join" ["" ["https://cognito-idp." {:Ref "AWS::Region"} ".amazonaws.com/" (import-value "UserPool")]]}}}})

(defn resource-http-integration []
  {:Type "AWS::ApiGatewayV2::Integration"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :IntegrationType "AWS_PROXY"
    :IntegrationUri (function-arn http-function)
    :PayloadFormatVersion "2.0"
    :TimeoutInMillis (* 1000 http-timeout-sec)}})

(defn resource-http-route [route-key auth]
  {:Type "AWS::ApiGatewayV2::Route"
   :Properties
   (cond-> {:ApiId {:Ref :HttpApi}
            :RouteKey route-key
            :Target (sub "integrations/${HttpIntegration}")
            :AuthorizationType "NONE"}
     (= auth :jwt) (assoc :AuthorizationType "JWT"
                          :AuthorizerId {:Ref :HttpAuthorizer}))})

(defn resource-http-stage []
  {:Type "AWS::ApiGatewayV2::Stage"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :StageName "$default"
    :AutoDeploy true
    :DefaultRouteSettings {:ThrottlingRateLimit 20
                           :ThrottlingBurstLimit 40}
    ;; Authorization ヘッダー、クエリ、本文は出さない
    :AccessLogSettings
    {:DestinationArn (get-att :HttpAccessLogGroup :Arn)
     :Format (access-log-format
              {:requestId "$context.requestId"
               :requestTime "$context.requestTime"
               :ip "$context.identity.sourceIp"
               :routeKey "$context.routeKey"
               :status "$context.status"
               :responseLength "$context.responseLength"
               :latency "$context.responseLatency"
               :integrationError "$context.integrationErrorMessage"
               :authorizerError "$context.authorizer.error"
               :userSub "$context.authorizer.claims.sub"
               :userAgent "$context.identity.userAgent"})}}})

(defn resource-invoke-permission [function-id api source-path]
  {:Type "AWS::Lambda::Permission"
   :Properties
   {:Action "lambda:InvokeFunction"
    :FunctionName {:Ref (keyword (str function-id "Function"))}
    :Principal "apigateway.amazonaws.com"
    :SourceArn (sub (str "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${" (name api) "}/" source-path))}})

;; --- API Gateway: WebSocket API (Run channel) ---
;; $connect は channel の Lambda が Run のトークンを検証し、拒否するときは 2xx 以外を返す

(defn resource-channel-api []
  {:Type "AWS::ApiGatewayV2::Api"
   :Properties
   {:Name (prefix-name "channel")
    :ProtocolType "WEBSOCKET"
    :RouteSelectionExpression "$request.body.action"}})

(defn resource-channel-integration []
  {:Type "AWS::ApiGatewayV2::Integration"
   :Properties
   {:ApiId {:Ref :ChannelApi}
    :IntegrationType "AWS_PROXY"
    :IntegrationUri (sub (str "arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${"
                              channel-function "Function.Arn}/invocations"))}})

(defn resource-channel-route [route-key]
  {:Type "AWS::ApiGatewayV2::Route"
   :Properties
   {:ApiId {:Ref :ChannelApi}
    :RouteKey route-key
    :AuthorizationType "NONE"
    :Target (sub "integrations/${ChannelIntegration}")}})

(defn resource-channel-stage
  "アクセスログは API Gateway のアカウント設定のロール (sanrecap スタックが設定する) で書く。"
  []
  {:Type "AWS::ApiGatewayV2::Stage"
   :Properties
   {:ApiId {:Ref :ChannelApi}
    :StageName "prod"
    :AutoDeploy true
    :DefaultRouteSettings {:ThrottlingRateLimit 20
                           :ThrottlingBurstLimit 40}
    :AccessLogSettings
    {:DestinationArn (get-att :ChannelAccessLogGroup :Arn)
     :Format (access-log-format
              {:requestId "$context.requestId"
               :requestTime "$context.requestTime"
               :ip "$context.identity.sourceIp"
               :routeKey "$context.routeKey"
               :eventType "$context.eventType"
               :connectionId "$context.connectionId"
               :status "$context.status"
               :integrationError "$context.integrationErrorMessage"})}}})

;; --- カスタムドメイン (Cloudflare の背後) ---

(defn resource-domain-name [domain-param]
  {:Type "AWS::ApiGatewayV2::DomainName"
   :Condition "UseApiDomains"
   :Properties
   {:DomainName {:Ref domain-param}
    :DomainNameConfigurations [{:CertificateArn {:Ref :CertificateArn}
                                :EndpointType "REGIONAL"
                                :SecurityPolicy "TLS_1_2"}]}})

(defn resource-api-mapping [domain api stage]
  {:Type "AWS::ApiGatewayV2::ApiMapping"
   :Condition "UseApiDomains"
   :Properties
   {:DomainName {:Ref domain}
    :ApiId {:Ref api}
    :Stage stage}})

;; --- テンプレート ---

(defn- function-resources [table-spec code-dir]
  (into {}
        (mapcat (fn [f]
                  [[(function-log-group-id f) (resource-log-group (sub (str "/aws/lambda/${Prefix}-sanchoris-" (:name f))))]
                   [(function-role-id f) (resource-function-role f table-spec)]
                   [(function-id f) (resource-function f table-spec code-dir)]])
                functions)))

(defn- route-id [prefix route-key]
  (keyword (str prefix (str/join (map str/capitalize (re-seq #"[A-Za-z]+" route-key))) "Route")))

(defn cfn [{:keys [table-spec code-dir]}]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     {:ApiDomainName {:Type "String"
                      :Default ""
                      :Description "HTTP API のカスタムドメイン (dev-sanchoris.sancode.dev など)。空なら作らない"}
      :ChannelDomainName {:Type "String"
                          :Default ""
                          :Description "WebSocket API (Run channel) のカスタムドメイン (dev-sanchoris-channel.sancode.dev など)"}
      :CertificateArn {:Type "String"
                       :Default ""
                       :Description "両方のドメインを持つ ACM 証明書 (ap-northeast-1) の ARN。空ならカスタムドメインを作らない"}
      :EnableWorkspaceEndpoints {:Type "String"
                                 :AllowedValues ["true" "false"]
                                 :Default "false"
                                 :Description "true で Workspace のサブネットに ECR (api, dkr) と CloudWatch Logs のインターフェースエンドポイントを作る。固定費が月 30 USD 前後かかる。Workspace を動かすときだけ true"}
      :S3PrefixListId {:Type "String"
                       :Description "リージョンの S3 のマネージドプレフィックスリスト (com.amazonaws.<region>.s3) の ID"}
      :WorkspaceImageTag {:Type "String"
                          :Default "latest"
                          :Description "Workspace のイメージのタグ"}
      :WorkspaceCpu {:Type "String"
                     :Default "2048"
                     :Description "Workspace のタスクの CPU (1024 = 1 vCPU)"}
      :WorkspaceMemory {:Type "String"
                        :Default "4096"
                        :Description "Workspace のタスクのメモリ (MiB)"}
      :WorkerMaxConcurrency {:Type "Number"
                             :Default 5
                             :MinValue 2
                             :Description "作業キューを消費する worker の同時実行数の上限"}})

    :Conditions
    {:UseApiDomains {"Fn::And" [{"Fn::Not" [{"Fn::Equals" [{:Ref :ApiDomainName} ""]}]}
                                {"Fn::Not" [{"Fn::Equals" [{:Ref :ChannelDomainName} ""]}]}
                                {"Fn::Not" [{"Fn::Equals" [{:Ref :CertificateArn} ""]}]}]}
     :UseWorkspaceEndpoints {"Fn::Equals" [{:Ref :EnableWorkspaceEndpoints} "true"]}}

    :Resources
    (merge
     (into {} (for [table (:tables table-spec)]
                [(table-id table) (resource-table table)]))

     {:Key (resource-key)
      :KeyAlias (resource-key-alias)
      :DocsBucket (resource-bucket nil)
      :DocsBucketPolicy (resource-bucket-policy :DocsBucket)
      :RecordsBucket (resource-bucket record-retention-days)
      :RecordsBucketPolicy (resource-bucket-policy :RecordsBucket)
      :LogsBucket (resource-bucket record-retention-days)
      :LogsBucketPolicy (resource-bucket-policy :LogsBucket)

      :WorkDlq (resource-work-dlq)
      :WorkQueue (resource-work-queue)
      :RelayDlq (resource-relay-dlq)
      :QueuePolicy (resource-queue-policy [:WorkQueue :WorkDlq :RelayDlq])
      :ScheduleGroup (resource-schedule-group)
      :SchedulerRole (resource-scheduler-role)}
     (into {} (for [[id job rate] rate-schedules]
                [(keyword (str id "Schedule")) (resource-rate-schedule job rate)]))

     {:Vpc (resource-vpc)
      :InternetGateway (resource-internet-gateway)
      :GatewayAttachment (resource-gateway-attachment)
      :PublicSubnet (resource-subnet public-subnet-cidr "public")
      :PublicRouteTable (resource-route-table "public")
      :PublicRoute (resource-public-route)
      :PublicSubnetRouteTableAssociation (resource-route-table-association :PublicSubnet :PublicRouteTable)
      :WorkspaceSubnet (resource-subnet workspace-subnet-cidr "workspace")
      :WorkspaceRouteTable (resource-route-table "workspace")
      :WorkspaceSubnetRouteTableAssociation (resource-route-table-association :WorkspaceSubnet :WorkspaceRouteTable)
      :S3Endpoint (resource-s3-endpoint)
      :EcrApiEndpoint (resource-interface-endpoint "ecr.api")
      :EcrDkrEndpoint (resource-interface-endpoint "ecr.dkr")
      :LogsEndpoint (resource-interface-endpoint "logs")

      :WorkspaceSecurityGroup (resource-security-group "sanchoris workspaces: egress to the relay and the VPC endpoints only")
      :RelaySecurityGroup (resource-security-group "sanchoris network relay: proxy for workspaces, egress to the internet")
      :EndpointSecurityGroup (resource-security-group "sanchoris VPC interface endpoints")
      :WorkspaceToRelayEgress (resource-egress :WorkspaceSecurityGroup {:FromPort relay-port :ToPort relay-port
                                                                        :DestinationSecurityGroupId {:Ref :RelaySecurityGroup}})
      :WorkspaceToEndpointsEgress (resource-egress :WorkspaceSecurityGroup {:FromPort 443 :ToPort 443
                                                                            :DestinationSecurityGroupId {:Ref :EndpointSecurityGroup}})
      :WorkspaceToS3Egress (resource-egress :WorkspaceSecurityGroup {:FromPort 443 :ToPort 443
                                                                     :DestinationPrefixListId {:Ref :S3PrefixListId}})
      :RelayFromWorkspaceIngress (resource-ingress :RelaySecurityGroup {:FromPort relay-port :ToPort relay-port
                                                                        :SourceSecurityGroupId {:Ref :WorkspaceSecurityGroup}})
      :RelayHttpsEgress (resource-egress :RelaySecurityGroup {:FromPort 443 :ToPort 443 :CidrIp "0.0.0.0/0"})
      :RelayHttpEgress (resource-egress :RelaySecurityGroup {:FromPort 80 :ToPort 80 :CidrIp "0.0.0.0/0"})
      :EndpointFromWorkspaceIngress (resource-ingress :EndpointSecurityGroup {:FromPort 443 :ToPort 443
                                                                              :SourceSecurityGroupId {:Ref :WorkspaceSecurityGroup}})

      :WorkspaceRepository (resource-workspace-repository)
      :Cluster (resource-cluster)
      :WorkspaceLogGroup (resource-log-group (sub "/aws/ecs/${Prefix}-sanchoris-workspace"))
      :WorkspaceExecutionRole (resource-workspace-execution-role)
      :WorkspaceTaskDefinition (resource-workspace-task-definition)}

     (function-resources table-spec code-dir)
     {:WorkEventSource (resource-work-event-source)}
     (into {} (for [table (stream-tables table-spec)]
                [(keyword (str (:logicalId table) "StreamEventSource")) (resource-stream-event-source table table-spec)]))

     {:HttpAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-sanchoris-http-access"))
      :HttpApi (resource-http-api)
      :HttpAuthorizer (resource-http-authorizer)
      :HttpIntegration (resource-http-integration)
      :HttpStage (resource-http-stage)
      :HttpInvokePermission (resource-invoke-permission http-function :HttpApi "*")}
     (into {} (for [[route-key auth] http-routes]
                [(route-id "Http" route-key) (resource-http-route route-key auth)]))

     {:ChannelAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-sanchoris-channel-access"))
      :ChannelApi (resource-channel-api)
      :ChannelIntegration (resource-channel-integration)
      :ChannelStage (resource-channel-stage)
      :ChannelInvokePermission (resource-invoke-permission channel-function :ChannelApi "*")}
     (into {} (for [route-key channel-routes]
                [(route-id "Channel" route-key) (resource-channel-route route-key)]))

     {:HttpDomain (resource-domain-name :ApiDomainName)
      :HttpApiMapping (assoc (resource-api-mapping :HttpDomain :HttpApi "$default") :DependsOn ["HttpStage"])
      :ChannelDomain (resource-domain-name :ChannelDomainName)
      :ChannelApiMapping (resource-api-mapping :ChannelDomain :ChannelApi {:Ref :ChannelStage})})

    :Outputs
    (merge
     (a.cfn/list-outputs
      (merge
       (into {} (for [table (:tables table-spec)]
                  [(keyword (str "Sanchoris" (:logicalId table))) {:Ref (table-id table)}]))
       {:SanchorisPublicUrl (public-url)
        :SanchorisControlUrl (control-url)
        :SanchorisDocsBucket {:Ref :DocsBucket}
        :SanchorisRecordsBucket {:Ref :RecordsBucket}
        :SanchorisLogsBucket {:Ref :LogsBucket}
        :SanchorisWorkQueueUrl {:Ref :WorkQueue}
        :SanchorisWorkDlqUrl {:Ref :WorkDlq}
        :SanchorisRelayDlqUrl {:Ref :RelayDlq}
        :SanchorisWorkspaceRepositoryUri (get-att :WorkspaceRepository :RepositoryUri)
        :SanchorisCluster {:Ref :Cluster}
        :SanchorisWorkspaceSubnet {:Ref :WorkspaceSubnet}
        :SanchorisPublicSubnet {:Ref :PublicSubnet}
        :SanchorisRelaySecurityGroup {:Ref :RelaySecurityGroup}}))
     ;; Cloudflare の CNAME の向け先 (カスタムドメインを作ったときだけ)
     {:SanchorisApiDomainTarget {:Condition "UseApiDomains"
                                 :Value (get-att :HttpDomain :RegionalDomainName)}
      :SanchorisChannelDomainTarget {:Condition "UseApiDomains"
                                     :Value (get-att :ChannelDomain :RegionalDomainName)}})}))

(defn- certificate-arn
  "`deploy sanchoris-certificate` が ap-northeast-1 に作った証明書の ARN。まだ無ければ nil (カスタムドメインを作らない)。"
  [prefix]
  (let [out (:out (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1"
                                 "--query" (format "Exports[?Name=='%s-SanchorisCertificateArn'].Value | [0]" prefix)
                                 "--output" "text"))
        arn (str/trim out)]
    (when (str/starts-with? arn "arn:") arn)))

(defn- s3-prefix-list-id []
  (str/trim (:out (c.util/eshell {:out :string} "aws" "ec2" "describe-prefix-lists" "--region" "ap-northeast-1"
                                 "--filters" "Name=prefix-list-name,Values=com.amazonaws.ap-northeast-1.s3"
                                 "--query" "PrefixLists[0].PrefixListId" "--output" "text"))))

(defn- ensure-secrets
  "SSM パラメータ `/<prefix>/sanchoris/*` (secrets) が全部あることを確かめる。無ければ名前を挙げて止める。"
  [prefix]
  (let [names (mapv (fn [[_ ssm-name]] (format "/%s/sanchoris/%s" prefix ssm-name)) secrets)
        found (-> (apply c.util/eshell {:out :string} "aws" "ssm" "get-parameters" "--region" "ap-northeast-1" "--names"
                         (concat names ["--query" "Parameters[*].Name" "--output" "json"]))
                  :out
                  json/parse-string
                  set)
        missing (remove found names)]
    (when (seq missing)
      (throw (ex-info (str "create the SSM parameters (String) first: " (str/join " " missing))
                      {:missing missing})))))

(defn template-input
  "sanchoris のリポジトリ (SANCHORIS_DIR、既定 ~/ghq/github.com/conao3/sanchoris) の表の定義と Lambda の成果物の場所。"
  []
  (let [dir (fs/absolutize (or (System/getenv "SANCHORIS_DIR")
                               (fs/expand-home "~/ghq/github.com/conao3/sanchoris")))
        tables-file (fs/path dir "infra" "tables.json")]
    (when-not (fs/exists? tables-file)
      (throw (ex-info (format "table definitions not found: %s" tables-file) {:file (str tables-file)})))
    {:table-spec (read-tables tables-file)
     :code-dir (str (fs/path dir "target" "lambda"))}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/sanchoris.json")
        prefix (-> param :prefix)
        stack-name (str prefix "-" "sanchoris")
        {:keys [code-dir] :as input} (template-input)
        cert (or (System/getenv "SANCHORIS_CERTIFICATE_ARN") (certificate-arn prefix))]
    (doseq [f functions]
      (let [zip (fs/path code-dir (:name f) "bootstrap.zip")]
        (when-not (fs/exists? zip)
          (throw (ex-info (format "build the Lambda functions in sanchoris first (%s)" zip) {:file (str zip)})))))
    (ensure-secrets prefix)
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn input)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--capabilities" "CAPABILITY_IAM"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (->> (cond-> {:Env (-> param :env)
                                 :Prefix prefix
                                 :S3PrefixListId (s3-prefix-list-id)
                                 :EnableWorkspaceEndpoints (if (= "true" (System/getenv "SANCHORIS_ENABLE_WORKSPACES")) "true" "false")
                                 :WorkspaceImageTag (or (System/getenv "SANCHORIS_WORKSPACE_IMAGE_TAG") "latest")
                                 :WorkerMaxConcurrency (or (System/getenv "SANCHORIS_WORKER_MAX_CONCURRENCY") "5")}
                          cert
                          (assoc :ApiDomainName (:sanchoris-domain-name param)
                                 :ChannelDomainName (:sanchoris-channel-domain-name param)
                                 :CertificateArn cert))
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
