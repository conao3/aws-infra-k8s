(ns conao3.aws-infra-k8s.sanfreceipt
  "sanfreceipt (sancode-org/sanfreceipt) のバックエンド。Cloudflare (proxied) の背後の API Gateway の HTTP API から、
  コンテナイメージ (arm64、ECR) の Lambda `api` (Hono。API と SPA の静的ファイルを両方返す) を呼ぶ。
  Stripe の契約イベントは、Stripe のイベント送信先 (EventBridge のパートナーイベントソース) → SQS (+DLQ) → 同じイメージの別ハンドラの Lambda `billing`。
  データは単一の DynamoDB 表 (PITR、TTL。暗号化は DynamoDB 既定の保存時暗号化だけで、KMS の鍵を持たない)。
  設定と秘密は SSM パラメータ (String) `/<prefix>/sanfreceipt/*` をバージョン付きの動的参照で Lambda の環境変数に渡す。
  Lambda のエラー・スロットル、HTTP API の 5xx、DLQ に CloudWatch アラーム (SNS のメール)。k8s には載せない。
  イメージの置き場の ECR は別スタック (`deploy sanfreceipt-repository`)。Lambda を作る前にイメージが要るため。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

;; api の Lambda の大きさは sanfreceipt の README「Deployment (AWS Lambda)」に合わせる (サブスクの読み取りで Claude Code を起動する)
(def api-timeout-sec 60)
(def api-memory-mb 2048)
;; HTTP API の統合タイムアウトの上限
(def http-integration-timeout-ms 30000)
(def billing-timeout-sec 30)
(def billing-memory-mb 256)
;; SQS の可視性タイムアウトは関数のタイムアウトの 6 倍
(def billing-visibility-timeout-sec (* 6 billing-timeout-sec))
;; billing の同時実行の上限 (SQS のイベントソースの最小値)。アカウントの Lambda の同時実行の上限が 10 なので小さく取る
(def billing-max-concurrency 2)
(def log-retention-days 30)
(def default-ai-gateway-model "anthropic/claude-opus-5.5")
(def freee-accounts-base "https://accounts.secure.freee.co.jp")
(def freee-api-base "https://api.freee.co.jp")
(def nta-api-base "https://web-api.invoice-kohyo.nta.go.jp")
(def ai-gateway-base-url "https://ai-gateway.vercel.sh/v4/ai")
(def stripe-api-base "https://api.stripe.com/v1")
;; ECR に残すタグ付きのイメージの数 (ロールバックの候補)
(def kept-images 5)

;; イメージの CMD を関数ごとに上書きするハンドラ (AWS の Lambda のベースイメージの形式 `<ファイル>.<関数>`、/var/task から)
(def api-handler "lambda/index.handler")
(def billing-handler "lambda/billing.handler")
;; 両方の関数が表に使う操作。TransactWriteItems は中の項目ごとの操作 (PutItem・UpdateItem・DeleteItem・ConditionCheckItem) で許可される
(def table-actions
  ["dynamodb:GetItem" "dynamodb:PutItem" "dynamodb:UpdateItem" "dynamodb:DeleteItem" "dynamodb:Query" "dynamodb:ConditionCheckItem"])

;; --- SSM パラメータ (String、`/<prefix>/sanfreceipt/<名前>`)。値は手で作る ---
;; [名前 環境変数 渡す関数]。環境変数が nil のものはスタックのパラメータとして読む

(def ssm-parameters
  [["freee-client-id" "FREEE_CLIENT_ID" #{:api}]
   ["freee-client-secret" "FREEE_CLIENT_SECRET" #{:api}]
   ["ai-gateway-api-key" "AI_GATEWAY_API_KEY" #{:api}]
   ["nta-application-id" "NTA_APPLICATION_ID" #{:api}]
   ["admin-company-ids" "SANFRECEIPT_ADMIN_COMPANY_IDS" #{:api}]
   ["stripe-secret-key" "STRIPE_SECRET_KEY" #{:api}]
   ["stripe-price-starter" "STRIPE_PRICE_STARTER" #{:api :billing}]
   ["stripe-price-pro" "STRIPE_PRICE_PRO" #{:api :billing}]
   ["stripe-price-max" "STRIPE_PRICE_MAX" #{:api :billing}]
   ["stripe-portal-config" "STRIPE_PORTAL_CONFIG" #{:api}]
   ["stripe-event-source" nil #{}]])

(defn- ssm-name [prefix name] (format "/%s/sanfreceipt/%s" prefix name))

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})
(defn- prefix-name [suffix] (sub (str "${Prefix}-sanfreceipt-" suffix)))

(defn- ssm-ref
  "SSM パラメータの動的参照。versions にバージョンがあれば固定する (値を変えるとテンプレートが変わり、次の deploy で Lambda に届く)。"
  [versions name]
  (sub (str "{{resolve:ssm:/${Prefix}/sanfreceipt/" name
            (when-let [v (get versions name)] (str ":" v))
            "}}")))

(defn- ssm-env [versions function]
  (into {}
        (for [[name env-name functions] ssm-parameters
              :when (and env-name (functions function))]
          [(keyword env-name) (ssm-ref versions name)])))

(defn- secure-transport-deny [resource-arn]
  {:Sid "DenyInsecureTransport"
   :Effect "Deny"
   :Principal "*"
   :Action "sqs:*"
   :Resource resource-arn
   :Condition {:Bool {"aws:SecureTransport" "false"}}})

(defn resource-log-group [name]
  {:Type "AWS::Logs::LogGroup"
   :Properties {:LogGroupName name
                :RetentionInDays log-retention-days}})

;; --- ECR (別スタック `<prefix>-sanfreceipt-repository`) ---

(defn resource-repository []
  {:Type "AWS::ECR::Repository"
   :Properties
   {:RepositoryName (sub "${Prefix}-sanfreceipt")
    :ImageScanningConfiguration {:ScanOnPush true}
    ;; タグは 1 つのイメージだけを指す (Lambda はデプロイのときにタグを digest に解決する)
    :ImageTagMutability "IMMUTABLE"
    :LifecyclePolicy
    {:LifecyclePolicyText
     (json/generate-string
      {:rules [{:rulePriority 1
                :selection {:tagStatus "untagged" :countType "sinceImagePushed" :countUnit "days" :countNumber 1}
                :action {:type "expire"}}
               {:rulePriority 2
                :selection {:tagStatus "any" :countType "imageCountMoreThan" :countNumber kept-images}
                :action {:type "expire"}}]})}
    ;; この環境の sanfreceipt の Lambda だけがイメージを取れる
    :RepositoryPolicyText
    {:Version "2012-10-17"
     :Statement [{:Sid "LambdaImagePull"
                  :Effect "Allow"
                  :Principal {:Service "lambda.amazonaws.com"}
                  :Action ["ecr:BatchGetImage" "ecr:GetDownloadUrlForLayer"]
                  :Condition {:StringLike {"aws:sourceArn" (sub "arn:aws:lambda:${AWS::Region}:${AWS::AccountId}:function:${Prefix}-sanfreceipt-*")}}}]}}})

(defn repository-cfn [_param]
  (a.cfn/template
   {:Parameters (a.cfn/list-string-parameters [:Env :Prefix])
    :Resources {:Repository (resource-repository)}
    :Outputs (a.cfn/list-outputs
              {:SanfreceiptRepositoryUri (get-att :Repository :RepositoryUri)
               :SanfreceiptRepositoryName {:Ref :Repository}})}))

;; --- データ (単一の表) ---

;; 主キーは汎用の pk / sk (S)。項目の種類と形はアプリ (sanfreceipt の DynamoDB のストア) が決める。
;; ttl (epoch 秒) のある項目 (OAuth の state、device flow のコード、セッション) は TTL で消える。アプリの値の expiresAt (ミリ秒) と名前を分ける
(defn resource-table []
  {:Type "AWS::DynamoDB::Table"
   :Properties
   {:TableName (sub "${Prefix}-sanfreceipt")
    :BillingMode "PAY_PER_REQUEST"
    :AttributeDefinitions [{:AttributeName "pk" :AttributeType "S"}
                           {:AttributeName "sk" :AttributeType "S"}]
    :KeySchema [{:AttributeName "pk" :KeyType "HASH"}
                {:AttributeName "sk" :KeyType "RANGE"}]
    :TimeToLiveSpecification {:AttributeName "ttl" :Enabled true}
    :PointInTimeRecoverySpecification {:PointInTimeRecoveryEnabled true}
    :DeletionProtectionEnabled true}})

;; --- Stripe の契約イベント (イベント送信先 → EventBridge のバス → ルール → SQS → billing) ---

(defn resource-billing-dlq []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "billing-dlq")
    :MessageRetentionPeriod 1209600
    :SqsManagedSseEnabled true}})

(defn resource-billing-queue []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "billing")
    :VisibilityTimeout billing-visibility-timeout-sec
    :MessageRetentionPeriod 345600
    :SqsManagedSseEnabled true
    :RedrivePolicy {:deadLetterTargetArn (get-att :BillingDlq :Arn)
                    :maxReceiveCount 5}}})

(defn resource-stripe-event-bus []
  {:Type "AWS::Events::EventBus"
   :Properties
   {:Name {:Ref :StripeEventSourceName}
    :EventSourceName {:Ref :StripeEventSourceName}}})

(defn resource-stripe-rule []
  {:Type "AWS::Events::Rule"
   :Properties
   {:Name (prefix-name "stripe")
    :EventBusName {:Ref :StripeEventBus}
    :EventPattern {:source [{:prefix "aws.partner/stripe.com"}]}
    :Targets [{:Id "BillingQueue" :Arn (get-att :BillingQueue :Arn)}]}})

(defn resource-billing-queue-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :BillingQueue}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Sid "AllowStripeRule"
       :Effect "Allow"
       :Principal {:Service "events.amazonaws.com"}
       :Action "sqs:SendMessage"
       :Resource (get-att :BillingQueue :Arn)
       :Condition {:ArnEquals {"aws:SourceArn" (get-att :StripeRule :Arn)}}}
      (secure-transport-deny (get-att :BillingQueue :Arn))]}}})

(defn resource-billing-dlq-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :BillingDlq}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny (get-att :BillingDlq :Arn))]}}})

;; --- IAM (関数ごと。ロググループと表は自分のものだけ) ---

(defn- lambda-role [policy-name log-group statements]
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "lambda.amazonaws.com"}
                  :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName policy-name
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       (into [{:Effect "Allow"
               :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
               :Resource (get-att log-group :Arn)}]
             statements)}}]}})

(defn resource-api-function-role []
  (lambda-role
   "sanfreceipt-api"
   :ApiLogGroup
   [{:Effect "Allow"
     :Action table-actions
     :Resource (get-att :Table :Arn)}]))

(defn resource-billing-function-role []
  (lambda-role
   "sanfreceipt-billing"
   :BillingLogGroup
   [{:Effect "Allow"
     :Action table-actions
     :Resource (get-att :Table :Arn)}
    {:Effect "Allow"
     :Action ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"]
     :Resource (get-att :BillingQueue :Arn)}]))

;; --- Lambda (同じイメージ、ハンドラだけ違う) ---

(defn- app-origin []
  {"Fn::If" ["UseApiDomain"
             (sub "https://${ApiDomainName}")
             (get-att :HttpApi :ApiEndpoint)]})

(defn resource-api-function [image-uri versions]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["ApiLogGroup"]
   :Properties
   {:FunctionName (prefix-name "api")
    :PackageType "Image"
    :ImageUri image-uri
    :ImageConfig {:Command [api-handler]}
    :Architectures ["arm64"]
    :LoggingConfig {:LogFormat "JSON"}
    :Timeout api-timeout-sec
    :MemorySize api-memory-mb
    :Role (get-att :ApiFunctionRole :Arn)
    :Environment
    {:Variables
     (merge
      {:SANFRECEIPT_APP_ORIGIN (app-origin)
       :SANFRECEIPT_TABLE {:Ref :Table}
       ;; カスタムドメインがあるとき、Cloudflare の IP 範囲の外からの要求をアプリが 403 で拒む
       :SANFRECEIPT_ALLOWED_SOURCES {"Fn::If" ["UseApiDomain" "cloudflare" "any"]}
       :FREEE_ACCOUNTS_BASE freee-accounts-base
       :FREEE_API_BASE freee-api-base
       :NTA_API_BASE nta-api-base
       :AI_GATEWAY_BASE_URL ai-gateway-base-url
       :STRIPE_API_BASE stripe-api-base
       :AI_GATEWAY_MODEL {:Ref :AiGatewayModel}}
      (ssm-env versions :api))}}})

(defn resource-billing-function [image-uri versions]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["BillingLogGroup"]
   :Properties
   {:FunctionName (prefix-name "billing")
    :PackageType "Image"
    :ImageUri image-uri
    :ImageConfig {:Command [billing-handler]}
    :Architectures ["arm64"]
    :LoggingConfig {:LogFormat "JSON"}
    :Timeout billing-timeout-sec
    :MemorySize billing-memory-mb
    :Role (get-att :BillingFunctionRole :Arn)
    :Environment
    {:Variables
     (merge
      {:SANFRECEIPT_TABLE {:Ref :Table}}
      (ssm-env versions :billing))}
    :Events
    {:Stripe {:Type "SQS"
              :Properties {:Queue (get-att :BillingQueue :Arn)
                           :BatchSize 1
                           :FunctionResponseTypes ["ReportBatchItemFailures"]
                           :ScalingConfig {:MaximumConcurrency billing-max-concurrency}}}}}})

;; --- API Gateway: HTTP API (`$default` の 1 ルート。認証はアプリのセッション Cookie) ---

(defn resource-http-api []
  {:Type "AWS::ApiGatewayV2::Api"
   :Properties
   {:Name (prefix-name "http")
    :ProtocolType "HTTP"
    ;; Cloudflare の背後に置くとき、既定の execute-api のエンドポイントを塞ぐ
    :DisableExecuteApiEndpoint {"Fn::If" ["UseApiDomain" true false]}}})

(defn resource-http-integration []
  {:Type "AWS::ApiGatewayV2::Integration"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :IntegrationType "AWS_PROXY"
    :IntegrationUri (get-att :ApiFunction :Arn)
    :PayloadFormatVersion "2.0"
    :TimeoutInMillis http-integration-timeout-ms}})

(defn resource-http-route []
  {:Type "AWS::ApiGatewayV2::Route"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :RouteKey "$default"
    :Target (sub "integrations/${HttpIntegration}")
    :AuthorizationType "NONE"}})

(defn resource-http-stage []
  {:Type "AWS::ApiGatewayV2::Stage"
   :Properties
   {:ApiId {:Ref :HttpApi}
    :StageName "$default"
    :AutoDeploy true
    :DefaultRouteSettings {:ThrottlingRateLimit 20
                           :ThrottlingBurstLimit 40}
    ;; Cookie・クエリ・本文は出さない
    :AccessLogSettings
    {:DestinationArn (get-att :HttpAccessLogGroup :Arn)
     :Format (json/generate-string
              {:requestId "$context.requestId"
               :requestTime "$context.requestTime"
               :ip "$context.identity.sourceIp"
               :httpMethod "$context.httpMethod"
               :path "$context.path"
               :status "$context.status"
               :responseLength "$context.responseLength"
               :latency "$context.responseLatency"
               :integrationError "$context.integrationErrorMessage"
               :userAgent "$context.identity.userAgent"})}}})

(defn resource-invoke-permission []
  {:Type "AWS::Lambda::Permission"
   :Properties
   {:Action "lambda:InvokeFunction"
    :FunctionName {:Ref :ApiFunction}
    :Principal "apigateway.amazonaws.com"
    :SourceArn (sub "arn:aws:execute-api:${AWS::Region}:${AWS::AccountId}:${HttpApi}/*")}})

(defn resource-domain-name []
  {:Type "AWS::ApiGatewayV2::DomainName"
   :Condition "UseApiDomain"
   :Properties
   {:DomainName {:Ref :ApiDomainName}
    :DomainNameConfigurations [{:CertificateArn {:Ref :CertificateArn}
                                :EndpointType "REGIONAL"
                                :SecurityPolicy "TLS_1_2"}]}})

(defn resource-api-mapping []
  {:Type "AWS::ApiGatewayV2::ApiMapping"
   :Condition "UseApiDomain"
   :DependsOn ["HttpStage"]
   :Properties
   {:DomainName {:Ref :HttpDomain}
    :ApiId {:Ref :HttpApi}
    :Stage "$default"}})

;; --- 監視 (CloudWatch アラーム → SNS → メール) ---

(defn resource-alarm-topic []
  {:Type "AWS::SNS::Topic"
   :Properties {:TopicName (prefix-name "alarms")}})

(defn resource-alarm-subscription []
  {:Type "AWS::SNS::Subscription"
   :Properties
   {:TopicArn {:Ref :AlarmTopic}
    :Protocol "email"
    :Endpoint {:Ref :AlarmEmail}}})

(defn- alarm [{:keys [description namespace metric dimensions statistic threshold]}]
  {:Type "AWS::CloudWatch::Alarm"
   :Properties
   {:AlarmDescription description
    :Namespace namespace
    :MetricName metric
    :Dimensions dimensions
    :Statistic statistic
    :Period 300
    :EvaluationPeriods 1
    :Threshold threshold
    :ComparisonOperator "GreaterThanOrEqualToThreshold"
    :TreatMissingData "notBreaching"
    :AlarmActions [{:Ref :AlarmTopic}]
    :OKActions [{:Ref :AlarmTopic}]}})

(defn- function-alarm [function metric description]
  (alarm {:description description
          :namespace "AWS/Lambda"
          :metric metric
          :dimensions [{:Name "FunctionName" :Value {:Ref function}}]
          :statistic "Sum"
          :threshold 1}))

(defn- alarm-resources []
  {:AlarmTopic (resource-alarm-topic)
   :AlarmSubscription (resource-alarm-subscription)
   :ApiErrorsAlarm (function-alarm :ApiFunction "Errors" "sanfreceipt api: the function failed (5 minutes)")
   :ApiThrottlesAlarm (function-alarm :ApiFunction "Throttles" "sanfreceipt api: throttled by the account's Lambda concurrency (5 minutes)")
   :BillingErrorsAlarm (function-alarm :BillingFunction "Errors" "sanfreceipt billing: the function failed (5 minutes)")
   :Http5xxAlarm (alarm {:description "sanfreceipt HTTP API: 5 or more 5xx responses in 5 minutes"
                         :namespace "AWS/ApiGateway"
                         :metric "5xx"
                         :dimensions [{:Name "ApiId" :Value {:Ref :HttpApi}}
                                      {:Name "Stage" :Value "$default"}]
                         :statistic "Sum"
                         :threshold 5})
   :BillingDlqAlarm (alarm {:description "sanfreceipt billing: a Stripe event is in the DLQ"
                            :namespace "AWS/SQS"
                            :metric "ApproximateNumberOfMessagesVisible"
                            :dimensions [{:Name "QueueName" :Value (get-att :BillingDlq :QueueName)}]
                            :statistic "Maximum"
                            :threshold 1})})

;; --- テンプレート ---

(defn cfn
  "image-uri は ECR のイメージの URI (タグ付き)。ssm-versions は SSM パラメータの名前からバージョンへの写像 (nil ならバージョンを付けない)。"
  [{:keys [image-uri ssm-versions]}]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix :AlarmEmail])
     {:AiGatewayModel {:Type "String" :Default default-ai-gateway-model}
      :ApiDomainName {:Type "String" :Default ""}
      :CertificateArn {:Type "String" :Default ""}
      ;; Stripe のイベント送信先 (Amazon EventBridge) が作るパートナーイベントソースの名前 (aws.partner/stripe.com/ed_...)
      :StripeEventSourceName {:Type "String"}})

    :Conditions
    {:UseApiDomain {"Fn::And" [{"Fn::Not" [{"Fn::Equals" [{:Ref :ApiDomainName} ""]}]}
                               {"Fn::Not" [{"Fn::Equals" [{:Ref :CertificateArn} ""]}]}]}}

    :Resources
    (merge
     {:ApiLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-sanfreceipt-api"))
      :BillingLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-sanfreceipt-billing"))
      :HttpAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-sanfreceipt-http-access"))

      :Table (resource-table)

      :BillingDlq (resource-billing-dlq)
      :BillingQueue (resource-billing-queue)
      :BillingQueuePolicy (resource-billing-queue-policy)
      :BillingDlqPolicy (resource-billing-dlq-policy)
      :StripeEventBus (resource-stripe-event-bus)
      :StripeRule (resource-stripe-rule)

      :ApiFunctionRole (resource-api-function-role)
      :BillingFunctionRole (resource-billing-function-role)
      :ApiFunction (resource-api-function image-uri ssm-versions)
      :BillingFunction (resource-billing-function image-uri ssm-versions)

      :HttpApi (resource-http-api)
      :HttpIntegration (resource-http-integration)
      :HttpDefaultRoute (resource-http-route)
      :HttpStage (resource-http-stage)
      :HttpInvokePermission (resource-invoke-permission)
      :HttpDomain (resource-domain-name)
      :HttpApiMapping (resource-api-mapping)}
     (alarm-resources))

    :Outputs
    (merge
     (a.cfn/list-outputs
      {:SanfreceiptPublicUrl (app-origin)
       :SanfreceiptTable {:Ref :Table}
       :SanfreceiptApiFunction {:Ref :ApiFunction}
       :SanfreceiptBillingFunction {:Ref :BillingFunction}
       :SanfreceiptBillingDlqUrl {:Ref :BillingDlq}
       :SanfreceiptAlarmTopic {:Ref :AlarmTopic}})
     ;; Cloudflare の CNAME の向け先 (カスタムドメインを作ったときだけ)
     {:SanfreceiptApiDomainTarget {:Condition "UseApiDomain"
                                   :Value (get-att :HttpDomain :RegionalDomainName)}})}))

;; --- デプロイ ---

(defn- export-value
  "ap-northeast-1 の CloudFormation の Export の値。無ければ nil。"
  [export-name]
  (let [out (str/trim (:out (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1"
                                           "--query" (format "Exports[?Name=='%s'].Value | [0]" export-name)
                                           "--output" "text")))]
    (when-not (contains? #{"" "None"} out) out)))

(defn- required-env [name]
  (let [value (System/getenv name)]
    (when (str/blank? value)
      (throw (ex-info (format "set %s" name) {:env name})))
    value))

(defn- image-uri
  "SANFRECEIPT_IMAGE_TAG のイメージが ECR にあることを確かめて、タグ付きの URI を返す。"
  [prefix]
  (let [tag (required-env "SANFRECEIPT_IMAGE_TAG")
        repository (or (export-value (str prefix "-SanfreceiptRepositoryUri"))
                       (throw (ex-info "deploy sanfreceipt-repository first" {:prefix prefix})))]
    (c.util/eshell {:out :string} "aws" "ecr" "describe-images" "--region" "ap-northeast-1"
                   "--repository-name" (str prefix "-sanfreceipt") "--image-ids" (str "imageTag=" tag))
    (str repository ":" tag)))

(defn- ssm-values
  "SSM パラメータ `/<prefix>/sanfreceipt/*` を全部読み、名前から {:version :value} への写像を返す。欠けていれば名前を挙げて止める。"
  [prefix]
  (let [names (mapv (fn [[name]] (ssm-name prefix name)) ssm-parameters)
        found (->> (partition-all 10 names)
                   (mapcat (fn [batch]
                             (-> (apply c.util/eshell {:out :string} "aws" "ssm" "get-parameters" "--region" "ap-northeast-1" "--names"
                                        (concat batch ["--query" "Parameters[*].{name:Name,version:Version,value:Value}" "--output" "json"]))
                                 :out
                                 (json/parse-string true))))
                   (into {} (map (fn [{:keys [name version value]}]
                                   [(last (str/split name #"/")) {:version version :value value}]))))
        missing (remove #(found (last (str/split % #"/"))) names)]
    (when (seq missing)
      (throw (ex-info (str "create the SSM parameters (String) first: " (str/join " " missing))
                      {:missing missing})))
    found))

(defn- write-template [file input]
  (fs/create-dirs (fs/parent file))
  (c.util/eprintln (format "Write: %s" (fs/path file)))
  (with-open [writer (io/writer file)]
    (json/generate-stream (cfn input) writer)))

(defn template
  "AWS を呼ばずにテンプレートを target/cfn/sanfreceipt.json に書く (検証用。イメージの URI は仮の値、SSM のバージョンは付けない)。"
  [_param]
  (let [file (fs/file "target/cfn/sanfreceipt.json")]
    (write-template file {:image-uri "123456789012.dkr.ecr.ap-northeast-1.amazonaws.com/dev-k8s-sanfreceipt:example"
                          :ssm-versions nil})
    (println (str (fs/absolutize file)))))

(defn- sam-deploy [file stack-name parameters]
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
                 (->> parameters
                      (map (fn [[k v]]
                             (format "%s=\"%s\"" (name k) v)))
                      (str/join " "))))

(defn deploy-repository [param]
  (let [file (fs/file "target/cfn/sanfreceipt-repository.json")]
    (fs/create-dirs (fs/parent file))
    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (json/generate-stream (repository-cfn param) writer))
    (sam-deploy file
                (str (:prefix param) "-sanfreceipt-repository")
                {:Env (:env param) :Prefix (:prefix param)})))

(defn deploy [param]
  (let [file (fs/file "target/cfn/sanfreceipt.json")
        prefix (:prefix param)
        image (image-uri prefix)
        values (ssm-values prefix)
        alarm-email (required-env "SANFRECEIPT_ALARM_EMAIL")
        cert (or (System/getenv "SANFRECEIPT_CERTIFICATE_ARN")
                 (export-value (str prefix "-SanfreceiptCertificateArn")))]
    (write-template file {:image-uri image
                          :ssm-versions (update-vals values :version)})
    (sam-deploy file
                (str prefix "-sanfreceipt")
                (cond-> {:Env (:env param)
                         :Prefix prefix
                         :AlarmEmail alarm-email
                         :AiGatewayModel (or (System/getenv "SANFRECEIPT_AI_GATEWAY_MODEL") default-ai-gateway-model)
                         :StripeEventSourceName (get-in values ["stripe-event-source" :value])}
                  ;; カスタムドメインは、証明書のスタック (`deploy sanfreceipt-certificate`) があれば作る
                  cert
                  (assoc :ApiDomainName (:sanfreceipt-domain-name param)
                         :CertificateArn cert)))))
