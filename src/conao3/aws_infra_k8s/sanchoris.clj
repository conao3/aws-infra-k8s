(ns conao3.aws-infra-k8s.sanchoris
  "sanchoris (conao3/sanchoris) の基盤。制御側はサーバーレス: API Gateway の HTTP API (画面、webhook、OAuth) と
  WebSocket API (Run channel) の Lambda、DynamoDB (main は stream 付き、audit は追記だけ)、S3 (docs / records、SSE-S3)、
  SQS FIFO の作業キューとそれを消費する worker の Lambda、main の stream を作業キューと EventBridge Scheduler に渡す relay の Lambda。
  Workspace だけが ECS Fargate で動き、公開サブネットの中継 (egress relay の CONNECT プロキシ) を出口に持つ。
  中継は Workspace が動く間だけ動く (worker が ECS のサービスの希望数を 1 と 0 に切り替える)。
  Workspace のネットワークは WorkspaceNetwork で選ぶ: public は公開サブネットと公開 IP (ECR はインターネット経由で、中継は助言にとどまる)、
  strict はインターネットへの経路のないサブネットと ECR のエンドポイント (中継が唯一の出口)。
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
(def authorizer-timeout-sec 10)
(def worker-timeout-sec 300)
(def work-visibility-timeout-sec (* 6 worker-timeout-sec))
(def relay-timeout-sec 60)
(def log-retention-days 30)
;; Plan の retentionDays の最大 (infra/plans.json)。期限前の削除はアプリが Plan ごとに行う
(def record-retention-days 90)
(def vpc-cidr "10.1.0.0/16")
(def public-subnet-cidr "10.1.0.0/24")
(def strict-subnet-cidr "10.1.16.0/20")
(def relay-port 3128)
(def relay-nofile 65536)
(def workspace-ephemeral-storage-gib 30)
;; タスク定義の大きさ。worker が RunTask の overrides で Plan の大きさ (infra/plans.json の workspaceCpu・workspaceMemoryMib) に置き換える
(def workspace-cpu "1024")
(def workspace-memory "4096")
(def workspace-container "workspace")
(def workspace-volumes {"workspace" "/workspace" "home" "/home/node" "tmp" "/tmp"})
;; Session の Workspace.Architecture ごとのタスク定義 (論理 ID の接尾辞、ファミリーの接尾辞、ECS の CPU アーキテクチャ、イメージのタグの接尾辞)
(def workspace-architectures
  [{:id "Arm64" :family "workspace-arm64" :cpu-architecture "ARM64" :image-suffix "arm64"}
   {:id "X8664" :family "workspace-x86_64" :cpu-architecture "X86_64" :image-suffix "amd64"}])
(def relay-container "relay")

;; 運営者の拒否リスト。Session の Network.Allow より先に中継が当てる
(def relay-deny-hosts
  ["*.nanopool.org" "*.supportxmr.com" "*.minexmr.com" "*.2miners.com" "*.f2pool.com" "*.moneroocean.stream"])

;; --- Lambda 関数 ---
;; :env は環境変数のまとまり (env-vars)、:grants は IAM の許可のまとまり (grant-statements)。
;; 関数を足す・分けるときはこの一覧と、トリガー (HTTP API・WebSocket API・作業キュー・stream) の参照先を直す

(def functions
  [{:id "Api" :name "api" :timeout http-timeout-sec :memory 512
    :env [:store :secrets-path :allowed-sources :public-url :cognito :github-web :github-api :stripe :records]
    :secrets ["GITHUB_APP_ID" "GITHUB_APP_PRIVATE_KEY" "GITHUB_APP_CLIENT_ID" "GITHUB_APP_CLIENT_SECRET"
              "GITHUB_APP_SLUG" "GITHUB_WEBHOOK_SECRET" "STRIPE_SECRET_KEY" "STRIPE_WEBHOOK_SECRET"
              "TASK_TOKEN_SIGNING_KEY" "OAUTH_STATE_SIGNING_KEY" "SANCHORIS_CREDENTIAL_KEY"]
    :grants [:tables :docs :records]}
   {:id "ChannelAuthorizer" :name "channel-authorizer" :timeout authorizer-timeout-sec :memory 256
    :env [:store :secrets-path :allowed-sources]
    :secrets ["RUN_TOKEN_SIGNING_KEY"]
    :grants [:main-read :docs-read]}
   {:id "Channel" :name "channel" :timeout http-timeout-sec :memory 256
    :env [:store :secrets-path :records :channel-endpoint :github-api]
    :secrets ["RUN_TOKEN_SIGNING_KEY" "RELAY_TOKEN_SIGNING_KEY" "GITHUB_APP_ID" "GITHUB_APP_PRIVATE_KEY" "SANCHORIS_CREDENTIAL_KEY"]
    :grants [:tables :docs :records :connections]}
   {:id "Worker" :name "worker" :timeout worker-timeout-sec :memory 512
    :env [:store :secrets-path :records :channel-endpoint :github-api :ecs :control-url :git :relay]
    :secrets ["GITHUB_APP_ID" "GITHUB_APP_PRIVATE_KEY" "RUN_TOKEN_SIGNING_KEY" "RELAY_TOKEN_SIGNING_KEY"]
    :grants [:tables :docs :records :connections :work-consume :ecs]}
   {:id "Relay" :name "relay" :timeout relay-timeout-sec :memory 256
    :env [:work-queue]
    :secrets []
    :grants [:stream :work-send :schedules]}])

(def http-function "Api")
(def channel-function "Channel")
(def channel-authorizer-function "ChannelAuthorizer")
(def work-function "Worker")
(def stream-function "Relay")

;; HTTP API のルート。:jwt は Cognito の JWT オーソライザーを付ける。$default (webhook、OAuth、/health、画面の SPA) は署名と state をアプリが検証する
(def http-routes
  [["ANY /api/{proxy+}" :jwt]
   ["$default" nil]])

(def channel-routes ["$connect" "$disconnect" "$default"])

;; --- SSM パラメータ (String か SecureString、`/<prefix>/sanchoris/<名前>`)。値は手で作り、Lambda がコールドスタートで読む ---

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
   ["SANCHORIS_CREDENTIAL_KEY" "credential-key"]
   ["RELAY_TOKEN_SIGNING_KEY" "relay-token-signing-key"]])

;; 秘密ではない String のパラメータ。stripe-portal-config はスタックが api の STRIPE_PORTAL_CONFIGURATION に解決し、
;; stripe-price-<plan> はデプロイ後の PLANS の投入 (sanchoris-dynamo-create) が読む
(def string-parameters
  ["stripe-portal-config" "stripe-price-starter" "stripe-price-pro" "stripe-price-max"])

;; EventBridge Scheduler の定期実行 (architecture.md「Rate schedules」)
(def rate-schedules
  [["SweepConfigs" "sweep_configs" "rate(5 minutes)"]
   ["SweepChecks" "sweep_checks" "rate(1 minute)"]
   ["SweepTaskLimits" "sweep_task_limits" "rate(1 minute)"]
   ["UsageAlerts" "usage_alerts" "rate(15 minutes)"]
   ["SweepWorkspaces" "sweep_workspaces" "rate(1 minute)"]])

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})
(defn- prefix-name [suffix] (sub (str "${Prefix}-sanchoris-" suffix)))
(defn- relay-service-name [] (prefix-name "relay"))
(defn- import-value [export-suffix]
  {"Fn::ImportValue" (sub (str "${Prefix}-" export-suffix))})

(defn- secret-parameter [env-name]
  (or (some (fn [[name ssm-name]] (when (= name env-name) ssm-name)) secrets)
      (throw (ex-info (str "no SSM parameter for " env-name) {:env env-name}))))

(def ^:private secret-parameter-arn-prefix
  "arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${Prefix}/sanchoris/")

(defn- secret-parameter-arn [env-name]
  (sub (str secret-parameter-arn-prefix (secret-parameter env-name))))

(defn- secret-parameter-arns
  "複数の SSM パラメータの ARN のリスト。ARN の共通部分を 1 回だけ書いて Fn::Split で分ける (テンプレートを短くする)。"
  [env-names]
  {"Fn::Split" ["|" {"Fn::Sub" [(str/join "|" (map #(str "${p}" (secret-parameter %)) env-names))
                                {:p (sub secret-parameter-arn-prefix)}]}]})

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

;; --- S3 ---

(defn resource-bucket [expiration-days]
  {:Type "AWS::S3::Bucket"
   :Properties
   {:PublicAccessBlockConfiguration {:BlockPublicAcls true
                                     :BlockPublicPolicy true
                                     :IgnorePublicAcls true
                                     :RestrictPublicBuckets true}
    :OwnershipControls {:Rules [{:ObjectOwnership "BucketOwnerEnforced"}]}
    :BucketEncryption {:ServerSideEncryptionConfiguration
                       [{:ServerSideEncryptionByDefault {:SSEAlgorithm "AES256"}}]}
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
    :Resource (get-att bucket :Arn)}])


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

(defn resource-queue-policy
  "SQS のキューのポリシーは 1 つの Statement に 1 つの Resource しか持てないので、キューごとに作る。"
  [queue]
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref queue}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny (get-att queue :Arn))]}}})

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
;; 中継は公開サブネットに置く (下の「中継」)。Workspace は WorkspaceNetwork が public なら公開サブネットで公開 IP を持ち、
;; strict ならインターネットへの経路のない strict サブネットで、イメージを ECR のエンドポイントと S3 のゲートウェイから取る

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
   :Condition "UseStrictEndpoints"
   :Properties
   {:VpcId {:Ref :Vpc}
    :ServiceName (sub (str "com.amazonaws.${AWS::Region}." service))
    :VpcEndpointType "Interface"
    :PrivateDnsEnabled true
    :SubnetIds [{:Ref :StrictSubnet}]
    :SecurityGroupIds [{:Ref :EndpointSecurityGroup}]
    :PolicyDocument (account-only-endpoint-policy)}})

(defn resource-s3-endpoint
  "ECR のイメージのレイヤーは S3 から取る。ゲートウェイ型 (無料) を strict サブネットの経路表にだけ付け、ECR のレイヤーのバケットだけを許す。"
  []
  {:Type "AWS::EC2::VPCEndpoint"
   :Condition "UseStrictNetwork"
   :Properties
   {:VpcId {:Ref :Vpc}
    :ServiceName (sub "com.amazonaws.${AWS::Region}.s3")
    :VpcEndpointType "Gateway"
    :RouteTableIds [{:Ref :StrictRouteTable}]
    :PolicyDocument {:Version "2012-10-17"
                     :Statement [{:Effect "Allow"
                                  :Principal "*"
                                  :Action "s3:GetObject"
                                  :Resource (sub "arn:aws:s3:::prod-${AWS::Region}-starport-layer-bucket/*")}]}}})

(defn- repository-lifecycle-policy [source-images]
  {:LifecyclePolicyText
   (json/generate-string
    {:rules [{:rulePriority 1
              :selection {:tagStatus "tagged" :tagPatternList ["latest*"] :countType "imageCountMoreThan" :countNumber 1000}
              :action {:type "expire"}}
             {:rulePriority 2
              :selection {:tagStatus "untagged" :countType "sinceImagePushed" :countUnit "days" :countNumber 1}
              :action {:type "expire"}}
             {:rulePriority 3
              :selection {:tagStatus "tagged" :tagPrefixList ["src-"] :countType "imageCountMoreThan" :countNumber source-images}
              :action {:type "expire"}}]})})

(defn resource-workspace-repository []
  {:Type "AWS::ECR::Repository"
   :Properties
   {:RepositoryName (prefix-name "workspace")
    :ImageScanningConfiguration {:ScanOnPush true}
    :LifecyclePolicy (repository-lifecycle-policy (* 3 (count workspace-architectures)))}})

(defn resource-cluster []
  {:Type "AWS::ECS::Cluster"
   :Properties
   {:ClusterName (sub "${Prefix}-sanchoris")
    :CapacityProviders ["FARGATE" "FARGATE_SPOT"]
    :ClusterSettings [{:Name "containerInsights" :Value "disabled"}]}})

(defn resource-workspace-execution-role
  "Fargate がイメージを取るためのロール。Workspace の中のプロセスには渡らない。"
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
                    :Resource (get-att :WorkspaceRepository :Arn)}]}}]}})

(defn resource-workspace-task-definition
  "Workspace のタスク定義 (アーキテクチャごとに 1 つ、大きさは同じ)。イメージはアーキテクチャごとのタグ (`<WorkspaceImageTag>-arm64`、`-amd64`) を使う。タスクロールは持たない (エージェントは AWS の API を使わない。制御側とは Run channel でだけ話す)。
   ログのドライバーは持たない (agent のログは Run channel で DynamoDB の LOG# に入り、起動前の停止は worker が停止の理由を Task に書く)。
   Run ごとの値 (ブートストラップのトークン、中継の URL、Run channel の URL) は worker が RunTask の overrides で渡す。overrides は CloudTrail と DescribeTasks から読めるので、2 つのトークンは 15 分で切れ、ブートストラップのトークンは Run channel の接続 1 回にしか使えない。12 時間の Run と中継のトークンは、agent が最初の接続で channel から受け取る。
   ECS の init プロセスは使わない (PID 1 が元の環境変数、つまりブートストラップと中継のトークンを持ち続けるため)。
   イメージの entrypoint が 2 つのトークンを環境変数から外して stdin に移し、tini の下で agent を動かす。
   ルートファイルシステムは読み取り専用にし、書き込む場所は /workspace・/home/node・/tmp のバインドマウント (タスクのエフェメラルストレージ) だけにする。
   イメージがこの 3 つを VOLUME に書くので、Fargate はイメージの中身・所有者・モード (/workspace と /home/node は node、/tmp は 1777) をボリュームに写してからコンテナを始める。"
  [{:keys [family cpu-architecture image-suffix]}]
  {:Type "AWS::ECS::TaskDefinition"
   :Properties
   {:Family (prefix-name family)
    :RequiresCompatibilities ["FARGATE"]
    :NetworkMode "awsvpc"
    :Cpu workspace-cpu
    :Memory workspace-memory
    :EphemeralStorage {:SizeInGiB workspace-ephemeral-storage-gib}
    :RuntimePlatform {:CpuArchitecture cpu-architecture :OperatingSystemFamily "LINUX"}
    :ExecutionRoleArn (get-att :WorkspaceExecutionRole :Arn)
    :Volumes (vec (for [n (keys workspace-volumes)] {:Name n}))
    :ContainerDefinitions
    [{:Name workspace-container
      :Image (sub (str "${WorkspaceRepository.RepositoryUri}:${WorkspaceImageTag}-" image-suffix))
      :Essential true
      :User "1000:1000"
      :ReadonlyRootFilesystem true
      :MountPoints (vec (for [[n p] workspace-volumes] {:SourceVolume n :ContainerPath p}))
      :StopTimeout 120
      :LinuxParameters {:Capabilities {:Drop ["ALL"]}}}]}})


;; --- 中継 (egress relay) ---
;; Workspace の出口。公開サブネットの Fargate サービスで、CONNECT を 443 番だけ通す。
;; 運営者の拒否リスト、プラットフォームの通信先、Run ごとの Network.Allow (worker が署名した relay のトークン) の順に当てる。
;; サービスの希望数は 0 で作り、worker が Workspace を始める前に 1 にし、動く Workspace がなくなったら 0 に戻す

(defn- relay-platform-hosts
  "Session の Network.Allow に書かなくても Workspace が出られる通信先 (Worker の provider、GitHub、Run channel、セッションの記録)。"
  []
  {"Fn::Join" [","
               ["api.anthropic.com" "console.anthropic.com" "platform.claude.com" "claude.ai"
                "github.com" "api.github.com" "*.githubusercontent.com"
                {"Fn::If" ["UseApiDomains"
                           {:Ref :ChannelDomainName}
                           (sub "${ChannelApi}.execute-api.${AWS::Region}.amazonaws.com")]}
                (sub "${RecordsBucket}.s3.${AWS::Region}.amazonaws.com")]]})

(defn resource-relay-repository []
  {:Type "AWS::ECR::Repository"
   :Properties
   {:RepositoryName (prefix-name "relay")
    :ImageScanningConfiguration {:ScanOnPush true}
    :LifecyclePolicy (repository-lifecycle-policy 3)}})

(defn resource-relay-execution-role
  "中継のイメージを取り、ログを書き、署名鍵を SSM から読んでコンテナに渡すためのロール。"
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
    [{:PolicyName "sanchoris-relay-execution"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement [{:Effect "Allow"
                    :Action "ecr:GetAuthorizationToken"
                    :Resource "*"}
                   {:Effect "Allow"
                    :Action ["ecr:BatchGetImage" "ecr:GetDownloadUrlForLayer" "ecr:BatchCheckLayerAvailability"]
                    :Resource (get-att :RelayRepository :Arn)}
                   {:Effect "Allow"
                    :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
                    :Resource (get-att :EgressRelayLogGroup :Arn)}
                   {:Effect "Allow"
                    :Action "ssm:GetParameters"
                    :Resource (secret-parameter-arn "RELAY_TOKEN_SIGNING_KEY")}]}}]}})

(defn resource-relay-task-definition
  "中継のタスク定義。タスクロールは持たない。"
  []
  {:Type "AWS::ECS::TaskDefinition"
   :Properties
   {:Family (prefix-name "relay")
    :RequiresCompatibilities ["FARGATE"]
    :NetworkMode "awsvpc"
    :Cpu "256"
    :Memory "512"
    :RuntimePlatform {:CpuArchitecture "ARM64" :OperatingSystemFamily "LINUX"}
    :ExecutionRoleArn (get-att :RelayExecutionRole :Arn)
    :ContainerDefinitions
    [{:Name relay-container
      :Image (sub "${RelayRepository.RepositoryUri}:${RelayImageTag}")
      :Essential true
      :User "1000:1000"
      :ReadonlyRootFilesystem true
      :LinuxParameters {:Capabilities {:Drop ["ALL"]}}
      :Ulimits [{:Name "nofile" :SoftLimit relay-nofile :HardLimit relay-nofile}]
      :PortMappings [{:ContainerPort relay-port :Protocol "tcp"}]
      :Environment [{:Name "SANCHORIS_RELAY_PORT" :Value (str relay-port)}
                    {:Name "SANCHORIS_RELAY_PLATFORM_HOSTS" :Value (relay-platform-hosts)}
                    {:Name "SANCHORIS_RELAY_DENY_HOSTS" :Value (str/join "," relay-deny-hosts)}]
      :Secrets [{:Name "RELAY_TOKEN_SIGNING_KEY"
                 :ValueFrom (secret-parameter-arn "RELAY_TOKEN_SIGNING_KEY")}]
      :LogConfiguration {:LogDriver "awslogs"
                         :Options {"awslogs-group" {:Ref :EgressRelayLogGroup}
                                   "awslogs-region" {:Ref "AWS::Region"}
                                   "awslogs-stream-prefix" "relay"}}}]}})

(defn resource-relay-service
  "公開サブネットで公開 IP を持つタスク。Workspace のセキュリティグループからの 3128 番だけを受ける。
   希望数は 0 で作り、worker が UpdateService で切り替える。Workspace は worker が渡すタスクのプライベート IP で中継に届く。
   デプロイは新しいタスクが動いてから古いタスクを止める (動いている Workspace は古いタスクが止まると外に出られなくなる)。
   タスクが起動しないデプロイは circuit breaker が数分で失敗にして前の版に戻す。"
  []
  {:Type "AWS::ECS::Service"
   :Condition "UseWorkspaces"
   :Properties
   {:ServiceName (relay-service-name)
    :Cluster {:Ref :Cluster}
    :LaunchType "FARGATE"
    :TaskDefinition {:Ref :RelayTaskDefinition}
    :DesiredCount 0
    :DeploymentConfiguration {:MinimumHealthyPercent 100
                              :MaximumPercent 200
                              :DeploymentCircuitBreaker {:Enable true :Rollback true}}
    :NetworkConfiguration {:AwsvpcConfiguration {:Subnets [{:Ref :PublicSubnet}]
                                                  :SecurityGroups [{:Ref :RelaySecurityGroup}]
                                                  :AssignPublicIp "ENABLED"}}}})

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

(defn- channel-endpoint
  "API Gateway Management API (@connections) の呼び先。カスタムドメインがあるときは execute-api のエンドポイントを止めるので、
   カスタムドメイン (Cloudflare の proxied の CNAME) を通る。"
  []
  {"Fn::If" ["UseApiDomains"
             (sub "https://${ChannelDomainName}")
             (sub "https://${ChannelApi}.execute-api.${AWS::Region}.amazonaws.com/${ChannelStage}")]})

(defn env-vars
  "環境変数のまとまり。名前と意味は sanchoris の docs/architecture.md「Lambda functions」「Environment」。"
  [group table-spec]
  (case group
    :store (into {:SANCHORIS_DOCS_BUCKET {:Ref :DocsBucket}}
                 (for [table (:tables table-spec)]
                   [(keyword (:tableNameEnv table)) {:Ref (table-id table)}]))
    :secrets-path {:SANCHORIS_SECRETS_PATH (sub "/${Prefix}/sanchoris/")}
    :allowed-sources {:SANCHORIS_ALLOWED_SOURCES {"Fn::If" ["UseApiDomains" "cloudflare" "any"]}}
    :public-url {:SANCHORIS_PUBLIC_URL (public-url)}
    :cognito {:COGNITO_USER_POOL_ID (import-value "UserPool")
              :COGNITO_CLIENT_ID (import-value "SanchorisUserPoolClient")}
    :github-web {:GITHUB_URL "https://github.com"}
    :github-api {:GITHUB_API_URL "https://api.github.com"}
    :stripe {:STRIPE_API_URL "https://api.stripe.com"
             :STRIPE_PORTAL_CONFIGURATION {:Ref :StripePortalConfiguration}}
    :records {:SANCHORIS_RECORDS_STORE "s3"
              :SANCHORIS_RECORDS_BUCKET {:Ref :RecordsBucket}}
    :channel-endpoint {:SANCHORIS_CHANNEL_ENDPOINT (channel-endpoint)}
    :control-url {:SANCHORIS_CONTROL_URL (control-url)}
    :git {:SANCHORIS_GIT_URL "https://github.com"}
    :relay {:SANCHORIS_RELAY_SERVICE (relay-service-name)
            :SANCHORIS_RELAY_PORT (str relay-port)}
    :work-queue {:SANCHORIS_WORK_QUEUE_URL {:Ref :WorkQueue}
                 :SANCHORIS_WORK_QUEUE_ARN (get-att :WorkQueue :Arn)
                 :SANCHORIS_SCHEDULE_GROUP {:Ref :ScheduleGroup}
                 :SANCHORIS_SCHEDULER_ROLE_ARN (get-att :SchedulerRole :Arn)}
    :ecs {:SANCHORIS_ECS_CLUSTER {:Ref :Cluster}
          :SANCHORIS_ECS_TASK_DEFINITION_ARM64 (prefix-name "workspace-arm64")
          :SANCHORIS_ECS_TASK_DEFINITION_X86_64 (prefix-name "workspace-x86_64")
          :SANCHORIS_ECS_CONTAINER workspace-container
          :SANCHORIS_ECS_SUBNETS {"Fn::If" ["UseStrictNetwork" {:Ref :StrictSubnet} {:Ref :PublicSubnet}]}
          :SANCHORIS_ECS_SECURITY_GROUPS {:Ref :WorkspaceSecurityGroup}
          :SANCHORIS_ECS_ASSIGN_PUBLIC_IP {"Fn::If" ["UseStrictNetwork" "DISABLED" "ENABLED"]}}))

(defn- main-table-arns [table-spec]
  (mapcat table-arns (filter #(= "main" (:role %)) (:tables table-spec))))

(defn grant-statements
  "IAM の許可のまとまり。"
  [grant table-spec]
  (case grant
    :tables (table-statements table-spec)
    :docs (bucket-statements :DocsBucket ["s3:GetObject" "s3:PutObject"])
    :docs-read (bucket-statements :DocsBucket ["s3:GetObject"])
    :main-read [{:Effect "Allow"
                 :Action "dynamodb:GetItem"
                 :Resource (vec (main-table-arns table-spec))}]
    :records (bucket-statements :RecordsBucket ["s3:GetObject" "s3:PutObject"])
    :connections [{:Effect "Allow"
                   :Action "execute-api:ManageConnections"
                   :Resource [(sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${ChannelApi}/${ChannelStage}/POST/@connections/*")
                              (sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${ChannelApi}/${ChannelStage}/DELETE/@connections/*")]}]
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
           :Resource (vec (for [{:keys [family]} workspace-architectures]
                            (sub (str "arn:aws:ecs:${AWS::Region}:${AWS::AccountId}:task-definition/${Prefix}-sanchoris-" family ":*"))))
           :Condition {:ArnEquals {"ecs:cluster" (get-att :Cluster :Arn)}}}
          {:Effect "Allow"
           :Action ["ecs:StopTask" "ecs:DescribeTasks" "ecs:TagResource"]
           :Resource (sub "arn:aws:ecs:${AWS::Region}:${AWS::AccountId}:task/${Cluster}/*")}
          {:Effect "Allow"
           :Action "ecs:ListTasks"
           :Resource "*"
           :Condition {:ArnEquals {"ecs:cluster" (get-att :Cluster :Arn)}}}
          {:Effect "Allow"
           :Action ["ecs:UpdateService" "ecs:DescribeServices"]
           :Resource (sub "arn:aws:ecs:${AWS::Region}:${AWS::AccountId}:service/${Cluster}/${Prefix}-sanchoris-relay")}
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
       (cond-> (into [{:Effect "Allow"
                       :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
                       :Resource (get-att (function-log-group-id f) :Arn)}]
                     (mapcat #(grant-statements % table-spec) (:grants f)))
         (seq (:secrets f)) (conj {:Effect "Allow"
                                   :Action "ssm:GetParameters"
                                   :Resource (secret-parameter-arns (:secrets f))}))}}]}})

(def function-globals
  "全 Lambda に共通の設定。SAM の Globals に置く。"
  {:LoggingConfig {:LogFormat "JSON"}
   :Runtime "provided.al2023"
   :Architectures ["arm64"]
   :Handler "bootstrap"})

(defn resource-function [f table-spec code-dir]
  {:Type "AWS::Serverless::Function"
   :DependsOn [(name (function-log-group-id f))]
   :Properties
   {:FunctionName (prefix-name (:name f))
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
;; $connect は channel-authorizer の Lambda (REQUEST オーソライザー) が Authorization ヘッダーの Run のトークンかブートストラップのトークンを検証する

(defn- lambda-invocation-uri [function-id]
  (sub (str "arn:aws:apigateway:${AWS::Region}:lambda:path/2015-03-31/functions/${"
            function-id "Function.Arn}/invocations")))

(defn resource-channel-api []
  {:Type "AWS::ApiGatewayV2::Api"
   :Properties
   {:Name (prefix-name "channel")
    :ProtocolType "WEBSOCKET"
    :RouteSelectionExpression "$request.body.action"
    :DisableExecuteApiEndpoint {"Fn::If" ["UseApiDomains" true false]}}})

(defn resource-channel-integration []
  {:Type "AWS::ApiGatewayV2::Integration"
   :Properties
   {:ApiId {:Ref :ChannelApi}
    :IntegrationType "AWS_PROXY"
    :IntegrationUri (lambda-invocation-uri channel-function)}})

(defn resource-channel-authorizer []
  {:Type "AWS::ApiGatewayV2::Authorizer"
   :Properties
   {:ApiId {:Ref :ChannelApi}
    :Name "run-token"
    :AuthorizerType "REQUEST"
    :AuthorizerUri (lambda-invocation-uri channel-authorizer-function)
    :IdentitySource ["route.request.header.Authorization"]}})

(defn resource-channel-route [route-key]
  {:Type "AWS::ApiGatewayV2::Route"
   :Properties
   (cond-> {:ApiId {:Ref :ChannelApi}
            :RouteKey route-key
            :AuthorizationType "NONE"
            :Target (sub "integrations/${ChannelIntegration}")}
     (= route-key "$connect") (assoc :AuthorizationType "CUSTOM"
                                     :AuthorizerId {:Ref :ChannelAuthorizer}))})

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

(defn- strict
  "strict の Workspace のネットワークでだけ作るリソース。"
  [resource]
  (assoc resource :Condition "UseStrictNetwork"))

(defn- route-id [prefix route-key]
  (keyword (str prefix (str/join (map str/capitalize (re-seq #"[A-Za-z]+" route-key))) "Route")))

(defn cfn [{:keys [table-spec code-dir]}]
  (a.cfn/template
   {:Globals {:Function function-globals}
    :Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     {:StripePortalConfiguration {:Type "AWS::SSM::Parameter::Value<String>"}
      :ApiDomainName {:Type "String" :Default ""}
      :ChannelDomainName {:Type "String" :Default ""}
      :CertificateArn {:Type "String" :Default ""}
      :EnableWorkspaces {:Type "String" :AllowedValues ["true" "false"] :Default "false"}
      :WorkspaceNetwork {:Type "String" :AllowedValues ["public" "strict"]}
      :S3PrefixListId {:Type "String"}
      :WorkspaceImageTag {:Type "String" :Default "latest"}
      :RelayImageTag {:Type "String" :Default "latest"}
      :WorkerMaxConcurrency {:Type "Number" :Default 5 :MinValue 2}})

    :Conditions
    {:UseApiDomains {"Fn::And" [{"Fn::Not" [{"Fn::Equals" [{:Ref :ApiDomainName} ""]}]}
                                {"Fn::Not" [{"Fn::Equals" [{:Ref :ChannelDomainName} ""]}]}
                                {"Fn::Not" [{"Fn::Equals" [{:Ref :CertificateArn} ""]}]}]}
     :UseWorkspaces {"Fn::Equals" [{:Ref :EnableWorkspaces} "true"]}
     :UseStrictNetwork {"Fn::Equals" [{:Ref :WorkspaceNetwork} "strict"]}
     :UsePublicNetwork {"Fn::Equals" [{:Ref :WorkspaceNetwork} "public"]}
     :UseStrictEndpoints {"Fn::And" [{:Condition "UseWorkspaces"} {:Condition "UseStrictNetwork"}]}}

    :Resources
    (merge
     (into {} (for [table (:tables table-spec)]
                [(table-id table) (resource-table table)]))

     {:DocsBucket (resource-bucket nil)
      :DocsBucketPolicy (resource-bucket-policy :DocsBucket)
      :RecordsBucket (resource-bucket record-retention-days)
      :RecordsBucketPolicy (resource-bucket-policy :RecordsBucket)

      :WorkDlq (resource-work-dlq)
      :WorkQueue (resource-work-queue)
      :RelayDlq (resource-relay-dlq)
      :WorkQueuePolicy (resource-queue-policy :WorkQueue)
      :WorkDlqPolicy (resource-queue-policy :WorkDlq)
      :RelayDlqPolicy (resource-queue-policy :RelayDlq)
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
      :StrictSubnet (strict (resource-subnet strict-subnet-cidr "workspace"))
      :StrictRouteTable (strict (resource-route-table "workspace"))
      :StrictSubnetRouteTableAssociation (strict (resource-route-table-association :StrictSubnet :StrictRouteTable))
      :S3Endpoint (resource-s3-endpoint)
      :EcrApiEndpoint (resource-interface-endpoint "ecr.api")
      :EcrDkrEndpoint (resource-interface-endpoint "ecr.dkr")

      :WorkspaceSecurityGroup (resource-security-group "sanchoris workspaces: egress to the relay and to ECR")
      :RelaySecurityGroup (resource-security-group "sanchoris network relay: proxy for workspaces, egress to the internet")
      :EndpointSecurityGroup (strict (resource-security-group "sanchoris VPC interface endpoints"))
      :WorkspaceToRelayEgress (resource-egress :WorkspaceSecurityGroup {:FromPort relay-port :ToPort relay-port
                                                                        :DestinationSecurityGroupId {:Ref :RelaySecurityGroup}})
      :WorkspaceToInternetEgress (assoc (resource-egress :WorkspaceSecurityGroup {:FromPort 443 :ToPort 443 :CidrIp "0.0.0.0/0"})
                                        :Condition "UsePublicNetwork")
      :WorkspaceToEndpointsEgress (strict (resource-egress :WorkspaceSecurityGroup {:FromPort 443 :ToPort 443
                                                                                    :DestinationSecurityGroupId {:Ref :EndpointSecurityGroup}}))
      :WorkspaceToS3Egress (strict (resource-egress :WorkspaceSecurityGroup {:FromPort 443 :ToPort 443
                                                                             :DestinationPrefixListId {:Ref :S3PrefixListId}}))
      :RelayFromWorkspaceIngress (resource-ingress :RelaySecurityGroup {:FromPort relay-port :ToPort relay-port
                                                                        :SourceSecurityGroupId {:Ref :WorkspaceSecurityGroup}})
      :RelayHttpsEgress (resource-egress :RelaySecurityGroup {:FromPort 443 :ToPort 443 :CidrIp "0.0.0.0/0"})      :EndpointFromWorkspaceIngress (strict (resource-ingress :EndpointSecurityGroup {:FromPort 443 :ToPort 443
                                                                                      :SourceSecurityGroupId {:Ref :WorkspaceSecurityGroup}}))
      :EndpointFromRelayIngress (strict (resource-ingress :EndpointSecurityGroup {:FromPort 443 :ToPort 443
                                                                                  :SourceSecurityGroupId {:Ref :RelaySecurityGroup}}))

      :WorkspaceRepository (resource-workspace-repository)
      :Cluster (resource-cluster)
      :WorkspaceExecutionRole (resource-workspace-execution-role)

      :RelayRepository (resource-relay-repository)
      :EgressRelayLogGroup (resource-log-group (sub "/aws/ecs/${Prefix}-sanchoris-relay"))
      :RelayExecutionRole (resource-relay-execution-role)
      :RelayTaskDefinition (resource-relay-task-definition)
      :RelayService (resource-relay-service)}
     (into {} (for [{:keys [id] :as architecture} workspace-architectures]
                [(keyword (str "WorkspaceTaskDefinition" id)) (resource-workspace-task-definition architecture)]))

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
      :ChannelInvokePermission (resource-invoke-permission channel-function :ChannelApi "*")
      :ChannelAuthorizer (resource-channel-authorizer)
      :ChannelAuthorizerInvokePermission (resource-invoke-permission channel-authorizer-function :ChannelApi "authorizers/*")}
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
        :SanchorisChannelEndpoint (channel-endpoint)
        :SanchorisWorkQueueUrl {:Ref :WorkQueue}
        :SanchorisWorkDlqUrl {:Ref :WorkDlq}
        :SanchorisRelayDlqUrl {:Ref :RelayDlq}
        :SanchorisWorkspaceRepositoryUri (get-att :WorkspaceRepository :RepositoryUri)
        :SanchorisRelayRepositoryUri (get-att :RelayRepository :RepositoryUri)
        :SanchorisCluster {:Ref :Cluster}
        :SanchorisWorkspaceSubnet {"Fn::If" ["UseStrictNetwork" {:Ref :StrictSubnet} {:Ref :PublicSubnet}]}
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
  "Lambda とスタックと PLANS の投入が読む SSM パラメータ `/<prefix>/sanchoris/*` が全部あることを確かめる。無ければ名前を挙げて止める。"
  [prefix]
  (let [names (->> (concat (map secret-parameter (distinct (mapcat :secrets functions)))
                           string-parameters)
                   (mapv #(format "/%s/sanchoris/%s" prefix %)))
        found (->> (partition-all 10 names)
                   (mapcat (fn [batch]
                             (-> (apply c.util/eshell {:out :string} "aws" "ssm" "get-parameters" "--region" "ap-northeast-1" "--names"
                                        (concat batch ["--query" "Parameters[*].Name" "--output" "json"]))
                                 :out
                                 json/parse-string)))
                   set)
        missing (remove found names)]
    (when (seq missing)
      (throw (ex-info (str "create the SSM parameters (String or SecureString) first: " (str/join " " missing))
                      {:missing missing})))))

(defn- workspace-network
  "SANCHORIS_WORKSPACE_NETWORK (public か strict)。既定値は持たない。"
  []
  (let [value (System/getenv "SANCHORIS_WORKSPACE_NETWORK")]
    (when-not (#{"public" "strict"} value)
      (throw (ex-info (format "set SANCHORIS_WORKSPACE_NETWORK to public or strict (got %s)" (pr-str value)) {:value value})))
    value))

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

(defmacro ^:private timed [step & body]
  `(let [t0# (System/nanoTime)
         result# (do ~@body)]
     (c.util/eprintln (format "[time] %s %.1fs" ~step (/ (- (System/nanoTime) t0#) 1e9)))
     result#))

(defn- sha256 [^String s]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn- ssm-string [name]
  (str/trim (:out (c.util/eshell {:out :string} "aws" "ssm" "get-parameter" "--region" "ap-northeast-1"
                                 "--name" name "--query" "Parameter.Value" "--output" "text"))))

(defn- deploy-parameters [param]
  (let [prefix (-> param :prefix)
        cert (or (System/getenv "SANCHORIS_CERTIFICATE_ARN") (certificate-arn prefix))]
    (cond-> {:Env (-> param :env)
             :Prefix prefix
             :StripePortalConfiguration (format "/%s/sanchoris/stripe-portal-config" prefix)
             :S3PrefixListId (s3-prefix-list-id)
             :EnableWorkspaces (if (= "true" (System/getenv "SANCHORIS_ENABLE_WORKSPACES")) "true" "false")
             :WorkspaceNetwork (workspace-network)
             :WorkspaceImageTag (or (System/getenv "SANCHORIS_WORKSPACE_IMAGE_TAG") "latest")
             :RelayImageTag (or (System/getenv "SANCHORIS_RELAY_IMAGE_TAG") "latest")
             :WorkerMaxConcurrency (or (System/getenv "SANCHORIS_WORKER_MAX_CONCURRENCY") "5")}
      cert
      (assoc :ApiDomainName (:sanchoris-domain-name param)
             :ChannelDomainName (:sanchoris-channel-domain-name param)
             :CertificateArn cert))))

(defn- write-template
  "テンプレートを target/cfn/sanchoris.json に書く。Output `SanchorisTemplateHash` は、成果物の場所を除いたテンプレートとパラメータ (ポータル設定は SSM の値) の SHA-256 で、scripts/deploy.mjs がデプロイ済みのスタックと比べる。"
  [param]
  (let [file (fs/file "target/cfn/sanchoris.json")
        prefix (-> param :prefix)
        input (template-input)
        parameters (timed "parameters" (deploy-parameters param))
        portal (timed "portal configuration" (ssm-string (:StripePortalConfiguration parameters)))
        hash (timed "template hash"
               (sha256 (json/generate-string [(cfn (assoc input :code-dir "CODE"))
                                              (into (sorted-map) (assoc parameters :StripePortalConfiguration portal))])))
        template (timed "template" (assoc-in (cfn input) [:Outputs :SanchorisTemplateHash] {:Value hash}))]
    (fs/create-dirs (fs/parent file))
    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (json/generate-stream template writer))
    {:file file
     :input input
     :hash hash
     :parameters parameters
     :stack-name (str prefix "-" "sanchoris")}))

(defn template
  "テンプレートを書き、ハッシュを JSON で標準出力に出す。AWS には読み取りだけを行う。"
  [param]
  (let [{:keys [file hash stack-name]} (write-template param)]
    (println (json/generate-string {:stackName stack-name :templateHash hash :file (str (fs/absolutize file))}))))

(defn- lint
  "sam validate --lint を、前回通したテンプレートと中身が違うときだけ実行する。"
  [file]
  (let [content-hash (sha256 (slurp file))
        stamp (fs/file "target/cfn/sanchoris.lint-ok")]
    (if (and (fs/exists? stamp) (= content-hash (slurp stamp)))
      (c.util/eprintln "sam validate --lint: unchanged template, skipped")
      (do
        (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
        (spit stamp content-hash)))))

(defn deploy [param]
  (let [{:keys [file input parameters stack-name]} (timed "write template" (write-template param))
        prefix (-> param :prefix)]
    (doseq [f functions]
      (let [zip (fs/path (:code-dir input) (:name f) "bootstrap.zip")]
        (when-not (fs/exists? zip)
          (throw (ex-info (format "build the Lambda functions in sanchoris first (%s)" zip) {:file (str zip)})))))
    (timed "ssm parameters" (ensure-secrets prefix))
    (timed "sam validate --lint" (lint file))
    (timed "stack status" (c.util/ensure-stack-deployable stack-name))
    (timed "sam deploy"
      (c.util/eshell "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--resolve-s3"
                     "--capabilities" "CAPABILITY_IAM"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (->> parameters
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
