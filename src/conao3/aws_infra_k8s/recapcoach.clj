(ns conao3.aws-infra-k8s.recapcoach
  "recapcoach (conao3/ts-recapcoach) のバックエンド。API Gateway (REST API) + WAF の入口。認証は共有の Cognito
  ユーザープール (cognito スタック。先にデプロイする) のオーソライザー。モノリスの API Lambda、
  重い処理を SNS → SQS → コンシューマの Lambda で非同期に処理し、結果を DynamoDB (TTL) に置く。
  字幕は API Gateway / WAF を通さず、署名付き POST で S3 に直接置かせる。ユーザーは DynamoDB の users 表。
  LLM は Vercel AI Gateway (キーとモデル ID は SSM パラメータを参照する)。k8s には載せない。
  コードは ts-recapcoach の `pnpm --filter @recapcoach/api build` の成果物 (apps/api/dist/lambda の index.mjs と worker.mjs)。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.recapcoach-extension :as c.recapcoach-extension]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

;; API Gateway の統合タイムアウトは 29 秒。API の Lambda はそれ以下にする
(def api-timeout-sec 29)
;; コンシューマは LLM を 2 回以上呼ぶ。SQS の可視性タイムアウトはこの 6 倍以上にする
(def worker-timeout-sec 120)
(def worker-visibility-timeout-sec (* 6 worker-timeout-sec))
(def stage-name "prod")

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})
(defn- prefix-name [suffix] (sub (str "${Prefix}-recapcoach-" suffix)))

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
                :RetentionInDays 30}})

;; --- 共有の Cognito ユーザープール (cognito スタックの Export) ---
;; recapcoach → cognito の順に消す (Export を使っている間は cognito を消せない)

(defn- import-value [export-suffix]
  {"Fn::ImportValue" (sub (str "${Prefix}-" export-suffix))})

;; --- ユーザー (管理人だけが登録する) ---

(defn resource-users-table []
  {:Type "AWS::DynamoDB::Table"
   :Properties
   {:TableName (prefix-name "users")
    :BillingMode "PAY_PER_REQUEST"
    ;; 小文字にしたメールアドレス。属性は userId (ジョブ・利用量のキー) と plan
    :AttributeDefinitions [{:AttributeName "email" :AttributeType "S"}]
    :KeySchema [{:AttributeName "email" :KeyType "HASH"}]}})

;; --- 字幕の置き場 (署名付き POST で直接置かせ、API とワーカーが読んで消す) ---

(defn resource-uploads-bucket []
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
    {:Rules [{:Id "expire-uploads"
              :Status "Enabled"
              :Prefix "uploads/"
              :ExpirationInDays 1
              :AbortIncompleteMultipartUpload {:DaysAfterInitiation 1}}]}
    ;; 拡張機能のオリジンからの POST だけ
    :CorsConfiguration {:CorsRules [{:AllowedOrigins (c.recapcoach-extension/origins)
                                     :AllowedMethods ["POST"]
                                     :AllowedHeaders ["*"]
                                     :MaxAge 3000}]}}})

(defn resource-uploads-bucket-policy []
  {:Type "AWS::S3::BucketPolicy"
   :Properties
   {:Bucket {:Ref :UploadsBucket}
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Sid "DenyInsecureTransport"
       :Effect "Deny"
       :Principal "*"
       :Action "s3:*"
       :Resource [(get-att :UploadsBucket :Arn)
                  (sub "${UploadsBucket.Arn}/*")]
       :Condition {:Bool {"aws:SecureTransport" "false"}}}]}}})

;; --- ジョブ (SNS → SQS → コンシューマの Lambda → DynamoDB) ---

(defn resource-jobs-table []
  {:Type "AWS::DynamoDB::Table"
   :Properties
   {:TableName (prefix-name "jobs")
    :BillingMode "PAY_PER_REQUEST"
    ;; 主キーに userId を含め、他のユーザーのジョブを引けない形にする
    :AttributeDefinitions [{:AttributeName "userId" :AttributeType "S"}
                           {:AttributeName "jobId" :AttributeType "S"}]
    :KeySchema [{:AttributeName "userId" :KeyType "HASH"}
                {:AttributeName "jobId" :KeyType "RANGE"}]
    :TimeToLiveSpecification {:AttributeName "expiresAt" :Enabled true}}})

(defn resource-jobs-topic []
  {:Type "AWS::SNS::Topic"
   :Properties {:TopicName (prefix-name "jobs")}})

(defn resource-jobs-dlq []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "jobs-dlq")
    :MessageRetentionPeriod 1209600
    :SqsManagedSseEnabled true}})

(defn resource-jobs-queue []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (prefix-name "jobs")
    :VisibilityTimeout worker-visibility-timeout-sec
    ;; ジョブは 1 時間で期限切れになるので、それより古いメッセージは残さない
    :MessageRetentionPeriod 3600
    :SqsManagedSseEnabled true
    :RedrivePolicy {:deadLetterTargetArn (get-att :JobsDlq :Arn)
                    :maxReceiveCount 3}}})

(defn resource-jobs-queue-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :JobsQueue}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Sid "AllowJobsTopic"
       :Effect "Allow"
       :Principal {:Service "sns.amazonaws.com"}
       :Action "sqs:SendMessage"
       :Resource (get-att :JobsQueue :Arn)
       :Condition {:ArnEquals {"aws:SourceArn" {:Ref :JobsTopic}}}}
      (secure-transport-deny (get-att :JobsQueue :Arn))]}}})

(defn resource-jobs-dlq-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :JobsDlq}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny (get-att :JobsDlq :Arn))]}}})

(defn resource-jobs-subscription []
  {:Type "AWS::SNS::Subscription"
   :DependsOn "JobsQueuePolicy"
   :Properties
   {:TopicArn {:Ref :JobsTopic}
    :Protocol "sqs"
    :Endpoint (get-att :JobsQueue :Arn)
    ;; 本文をそのまま SQS に渡す (SNS のエンベロープを付けない)
    :RawMessageDelivery true}})

;; --- IAM (最小権限。ロググループは自分のものだけ) ---

(defn- lambda-role [log-group statements]
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "lambda.amazonaws.com"}
                  :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "recapcoach"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       (into [{:Effect "Allow"
               :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
               ;; LogGroup の Arn 属性は末尾が `:*` (ログストリームを含む)
               :Resource (get-att log-group :Arn)}]
             statements)}}]}})

(defn- uploads-objects-arn [] (sub "${UploadsBucket.Arn}/uploads/*"))

;; 無いキーを GetObject したときに 403 でなく 404 (NoSuchKey) を返すための ListBucket。
;; バケットには uploads/ しか置かない
(defn- uploads-list-statement []
  {:Effect "Allow"
   :Action "s3:ListBucket"
   :Resource (get-att :UploadsBucket :Arn)})

(defn resource-api-function-role []
  (lambda-role
   :ApiLogGroup
   [{:Effect "Allow"
     :Action ["dynamodb:PutItem" "dynamodb:GetItem" "dynamodb:DeleteItem"]
     :Resource (get-att :JobsTable :Arn)}
    {:Effect "Allow"
     :Action "dynamodb:GetItem"
     :Resource (get-att :UsersTable :Arn)}
    {:Effect "Allow"
     :Action "sns:Publish"
     :Resource {:Ref :JobsTopic}}
    ;; PutObject は署名付き POST の署名者として要る
    {:Effect "Allow"
     :Action ["s3:PutObject" "s3:GetObject" "s3:DeleteObject"]
     :Resource (uploads-objects-arn)}
    (uploads-list-statement)]))

(defn resource-worker-function-role []
  (lambda-role
   :WorkerLogGroup
   [{:Effect "Allow"
     :Action ["dynamodb:GetItem" "dynamodb:UpdateItem"]
     :Resource (get-att :JobsTable :Arn)}
    {:Effect "Allow"
     :Action ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"]
     :Resource (get-att :JobsQueue :Arn)}
    {:Effect "Allow"
     :Action ["s3:GetObject" "s3:DeleteObject"]
     :Resource (uploads-objects-arn)}
    (uploads-list-statement)]))

;; --- Lambda ---

(defn resource-api-function [code-uri]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["ApiLogGroup"]
   :Properties
   {:FunctionName (prefix-name "api")
    ;; 1 行 1 JSON のログ (アプリの構造化ログを Logs Insights で event ごとに集計できる)
    :LoggingConfig {:LogFormat "JSON"}
    :Runtime "nodejs22.x"
    :Architectures ["arm64"]
    :Handler "index.handler"
    :CodeUri code-uri
    :Timeout api-timeout-sec
    :MemorySize 512
    :Role (get-att :ApiFunctionRole :Arn)
    :Environment
    {:Variables
     {:AI_GATEWAY_MODEL {:Ref :AiGatewayModel}
      :AI_GATEWAY_API_KEY {:Ref :AiGatewayApiKey}
      :ALLOWED_ORIGINS (str/join "," (c.recapcoach-extension/origins))
      ;; ID トークンの aud がこのクライアントのものか、API で確かめる
      :COGNITO_CLIENT_ID (import-value "RecapcoachUserPoolClient")
      :USERS_TABLE {:Ref :UsersTable}
      :JOBS_TABLE {:Ref :JobsTable}
      :JOBS_TOPIC_ARN {:Ref :JobsTopic}
      :UPLOADS_BUCKET {:Ref :UploadsBucket}}}
    :Events
    {;; /healthz だけ認証なし。それ以外のパスは既定のオーソライザー (Cognito) で守る
     :Health {:Type "Api"
              :Properties {:RestApiId {:Ref :Api} :Path "/healthz" :Method "GET"
                           :Auth {:Authorizer "NONE"}}}
     ;; CORS のプリフライトはトークンを持たないので、認証なしで Hono (cors) に通す
     :Preflight {:Type "Api"
                 :Properties {:RestApiId {:Ref :Api} :Path "/{proxy+}" :Method "OPTIONS"
                              :Auth {:Authorizer "NONE"}}}
     :Proxy {:Type "Api"
             :Properties {:RestApiId {:Ref :Api} :Path "/{proxy+}" :Method "ANY"}}}}})

(defn resource-worker-function [code-uri]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["WorkerLogGroup"]
   :Properties
   {:FunctionName (prefix-name "worker")
    ;; 1 行 1 JSON のログ (アプリの構造化ログを Logs Insights で event ごとに集計できる)
    :LoggingConfig {:LogFormat "JSON"}
    :Runtime "nodejs22.x"
    :Architectures ["arm64"]
    :Handler "worker.handler"
    :CodeUri code-uri
    :Timeout worker-timeout-sec
    :MemorySize 512
    :Role (get-att :WorkerFunctionRole :Arn)
    :Environment
    {:Variables
     {:AI_GATEWAY_MODEL {:Ref :AiGatewayModel}
      :AI_GATEWAY_API_KEY {:Ref :AiGatewayApiKey}
      :JOBS_TABLE {:Ref :JobsTable}
      :UPLOADS_BUCKET {:Ref :UploadsBucket}}}
    :Events
    {:Jobs {:Type "SQS"
            :Properties {:Queue (get-att :JobsQueue :Arn)
                         :BatchSize 1
                         :FunctionResponseTypes ["ReportBatchItemFailures"]
                         ;; LLM への同時リクエストの上限
                         :ScalingConfig {:MaximumConcurrency 5}}}}}})

;; --- API Gateway (REST API) ---

(defn- cloudflare-ip-ranges
  "Cloudflare が原点に接続してくる IPv4 / IPv6 の範囲 (`bin/update-cloudflare-ips` で更新する)。"
  []
  (let [ips (-> (io/resource "cloudflare-ips.edn") slurp edn/read-string)]
    (vec (concat (:ipv4 ips) (:ipv6 ips)))))

(defn resource-api
  "cloudflare-only? が真なら、リソースポリシーで Cloudflare の IP 以外からの呼び出しを拒否する
   (カスタムドメインを Cloudflare の背後に置いたとき)。"
  [cloudflare-only?]
  {:Type "AWS::Serverless::Api"
   ;; アクセスログの出力先ロールが設定されてからステージを作る
   :DependsOn ["ApiGatewayAccount"]
   :Properties
   {:Name (prefix-name "api")
    :StageName stage-name
    :OpenApiVersion "3.0.1"
    :EndpointConfiguration {:Type "REGIONAL"}
    ;; Cloudflare の背後に置くとき、既定の execute-api のエンドポイントを塞ぐ
    :DisableExecuteApiEndpoint {"Fn::If" ["DisableExecuteApi" true false]}
    :Auth (cond-> {:DefaultAuthorizer "CognitoAuth"
                   :Authorizers {:CognitoAuth {:UserPoolArn (import-value "UserPoolArn")
                                               :Identity {:Header "Authorization"}}}}
            cloudflare-only? (assoc :ResourcePolicy {:IpRangeWhitelist (cloudflare-ip-ranges)}))
    :MethodSettings [{:ResourcePath "/*"
                      :HttpMethod "*"
                      :ThrottlingRateLimit 20
                      :ThrottlingBurstLimit 40
                      :MetricsEnabled true}]
    ;; Authorization ヘッダー、クエリ、本文は出さない。パスはテンプレート (resourcePath) だけ
    :AccessLogSetting
    {:DestinationArn (get-att :ApiAccessLogGroup :Arn)
     :Format (json/generate-string
              {:requestId "$context.requestId"
               :requestTime "$context.requestTime"
               :ip "$context.identity.sourceIp"
               :httpMethod "$context.httpMethod"
               :resourcePath "$context.resourcePath"
               :status "$context.status"
               :responseLength "$context.responseLength"
               :latency "$context.responseLatency"
               :integrationStatus "$context.integrationStatus"
               :integrationError "$context.integrationErrorMessage"
               :authorizerError "$context.authorizer.error"
               :userSub "$context.authorizer.claims.sub"
               :userAgent "$context.identity.userAgent"})}}})

;; API Gateway のアカウント単位の設定 (アクセスログを CloudWatch Logs に書くためのロール)
(defn resource-api-gateway-account-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow"
                  :Principal {:Service "apigateway.amazonaws.com"}
                  :Action "sts:AssumeRole"}]}
    :ManagedPolicyArns ["arn:aws:iam::aws:policy/service-role/AmazonAPIGatewayPushToCloudWatchLogs"]}})

(defn resource-api-gateway-account []
  {:Type "AWS::ApiGateway::Account"
   :Properties {:CloudWatchRoleArn (get-att :ApiGatewayAccountRole :Arn)}})

;; --- WAF (REST API のステージに付ける) ---

(defn- visibility [metric]
  {:SampledRequestsEnabled true
   :CloudWatchMetricsEnabled true
   :MetricName metric})

(defn resource-web-acl []
  {:Type "AWS::WAFv2::WebACL"
   :Condition "UseWaf"
   :Properties
   {:Name (prefix-name "api")
    :Scope "REGIONAL"
    :DefaultAction {:Allow {}}
    :VisibilityConfig (visibility "recapcoach-api")
    :Rules
    [{:Name "RateLimitPerIp"
      :Priority 0
      :Action {:Block {}}
      :Statement {:RateBasedStatement {:Limit 600
                                       :EvaluationWindowSec 300
                                       :AggregateKeyType "IP"}}
      :VisibilityConfig (visibility "recapcoach-rate-limit")}
     {:Name "AWSManagedRulesCommonRuleSet"
      :Priority 1
      :OverrideAction {:None {}}
      :Statement
      {:ManagedRuleGroupStatement
       {:VendorName "AWS"
        :Name "AWSManagedRulesCommonRuleSet"
        ;; 字幕は最大 1MB の JSON。既定の 8KB 上限 (SizeRestrictions_BODY) では弾かれるので、
        ;; 本文の大きさは Hono の bodyLimit で見る
        :RuleActionOverrides [{:Name "SizeRestrictions_BODY"
                               :ActionToUse {:Count {}}}]}}
      :VisibilityConfig (visibility "recapcoach-common-rules")}]}})

(defn resource-web-acl-association []
  {:Type "AWS::WAFv2::WebACLAssociation"
   :Condition "UseWaf"
   :Properties
   {:WebACLArn (get-att :WebAcl :Arn)
    :ResourceArn (sub "arn:aws:apigateway:${AWS::Region}::/restapis/${Api}/stages/${Api.Stage}")}})

(defn resource-api-domain-name []
  {:Type "AWS::ApiGateway::DomainName"
   :Condition "UseApiDomain"
   :Properties
   {:DomainName {:Ref :ApiDomainName}
    :RegionalCertificateArn {:Ref :ApiCertificateArn}
    :EndpointConfiguration {:Types ["REGIONAL"]}
    :SecurityPolicy "TLS_1_2"}})

(defn resource-api-base-path-mapping []
  {:Type "AWS::ApiGateway::BasePathMapping"
   :Condition "UseApiDomain"
   :Properties
   {:DomainName {:Ref :ApiDomain}
    :RestApiId {:Ref :Api}
    :Stage {:Ref "Api.Stage"}}})

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     {:AiGatewayApiKey {:Type "AWS::SSM::Parameter::Value<String>"
                        :Description "Vercel AI Gateway の API キーを置いた SSM パラメータ (String) の名前"}
      :AiGatewayModel {:Type "AWS::SSM::Parameter::Value<String>"
                       :Description "AI Gateway のモデル ID (anthropic/claude-sonnet-4.6 など) を置いた SSM パラメータ (String) の名前"}

      :EnableWaf {:Type "String"
                  :AllowedValues ["true" "false"]
                  :Default "false"
                  :Description "true で AWS WAF (Web ACL) を REST API のステージに付ける。固定費が月 7 USD 前後かかる。既定は付けない (Cloudflare の背後に置く前提)"}

      ;; Cloudflare の背後に置くための準備。既定は空 / false で、何も作らない
      :DisableExecuteApiEndpoint {:Type "String"
                                  :AllowedValues ["true" "false"]
                                  :Default "false"
                                  :Description "true で既定の execute-api のエンドポイントを無効にする (カスタムドメインを作ってから)"}
      :ApiDomainName {:Type "String"
                      :Default ""
                      :Description "リージョナルのカスタムドメイン (例 dev-recapcoach-api.sancode.dev)。空なら作らない"}
      :ApiCertificateArn {:Type "String"
                          :Default ""
                          :Description "カスタムドメインの ACM 証明書 (API と同じリージョン) の ARN。空なら作らない"}})

    :Conditions
    {:UseWaf {"Fn::Equals" [{:Ref :EnableWaf} "true"]}
     :DisableExecuteApi {"Fn::Equals" [{:Ref :DisableExecuteApiEndpoint} "true"]}
     :UseApiDomain {"Fn::And" [{"Fn::Not" [{"Fn::Equals" [{:Ref :ApiDomainName} ""]}]}
                               {"Fn::Not" [{"Fn::Equals" [{:Ref :ApiCertificateArn} ""]}]}]}}

    :Resources
    {:ApiLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-recapcoach-api"))
     :WorkerLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-recapcoach-worker"))
     :ApiAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-recapcoach-api-access"))

     :UsersTable (resource-users-table)
     :UploadsBucket (resource-uploads-bucket)
     :UploadsBucketPolicy (resource-uploads-bucket-policy)

     :JobsTable (resource-jobs-table)
     :JobsTopic (resource-jobs-topic)
     :JobsDlq (resource-jobs-dlq)
     :JobsQueue (resource-jobs-queue)
     :JobsQueuePolicy (resource-jobs-queue-policy)
     :JobsDlqPolicy (resource-jobs-dlq-policy)
     :JobsSubscription (resource-jobs-subscription)

     :ApiFunctionRole (resource-api-function-role)
     :WorkerFunctionRole (resource-worker-function-role)
     :ApiFunction (resource-api-function (:code-uri param))
     :WorkerFunction (resource-worker-function (:code-uri param))

     :ApiGatewayAccountRole (resource-api-gateway-account-role)
     :ApiGatewayAccount (resource-api-gateway-account)
     :Api (resource-api (:cloudflare-only? param))

     :ApiDomain (resource-api-domain-name)
     :ApiBasePathMapping (resource-api-base-path-mapping)

     :WebAcl (resource-web-acl)
     :WebAclAssociation (resource-web-acl-association)}

    :Outputs
    (merge
     (a.cfn/list-outputs
     {:RecapcoachApiUrl (sub (str "https://${Api}.execute-api.${AWS::Region}.amazonaws.com/${Api.Stage}"))
      :RecapcoachApiFunction {:Ref :ApiFunction}
      :RecapcoachWorkerFunction {:Ref :WorkerFunction}
      :RecapcoachUsersTable {:Ref :UsersTable}
      :RecapcoachUploadsBucket {:Ref :UploadsBucket}
      :RecapcoachJobsTable {:Ref :JobsTable}
      :RecapcoachJobsDlqUrl {:Ref :JobsDlq}})
     ;; Cloudflare の CNAME の向け先 (カスタムドメインを作ったときだけ)
     {:RecapcoachApiDomainTarget {:Condition "UseApiDomain"
                                  :Value (get-att :ApiDomain :RegionalDomainName)}})}))

(defn- api-certificate-arn
  "`deploy recapcoach-certificate` が ap-northeast-1 に作った証明書の ARN。まだ無ければ nil (カスタムドメインを作らない)。"
  [prefix]
  (let [out (:out (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1"
                                 "--query" (format "Exports[?Name=='%s-RecapcoachCertificateArn'].Value | [0]" prefix)
                                 "--output" "text"))
        arn (str/trim out)]
    (when (str/starts-with? arn "arn:") arn)))

(defn deploy [param]
  (let [file (fs/file "target/cfn/recapcoach.json")
        prefix (-> param :prefix)
        stack-name (str prefix "-" "recapcoach")
        api-cert (or (System/getenv "RECAPCOACH_API_CERTIFICATE_ARN") (api-certificate-arn prefix))
        code-uri (str (fs/absolutize
                       (or (System/getenv "RECAPCOACH_DIST")
                           (fs/expand-home "~/ghq/github.com/conao3/ts-recapcoach/apps/api/dist/lambda"))))]
    (doseq [entry ["index.mjs" "worker.mjs"]]
      (when-not (fs/exists? (fs/path code-uri entry))
        (throw (ex-info (format "build first: pnpm --filter @recapcoach/api build (%s/%s)" code-uri entry)
                        {:code-uri code-uri :entry entry}))))
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn (assoc param :code-uri code-uri :cloudflare-only? (boolean api-cert)))
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    ;; AI Gateway のキーとモデル ID は SSM パラメータ (String) に先に置いておく。ここでは名前だけを渡す
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
                                 :AiGatewayApiKey (format "/%s/recapcoach/ai-gateway-api-key" prefix)
                                 :AiGatewayModel (format "/%s/recapcoach/ai-gateway-model" prefix)
                                 ;; WAF は固定費がかかるので、RECAPCOACH_ENABLE_WAF=true のときだけ付ける
                                 :EnableWaf (if (= "true" (System/getenv "RECAPCOACH_ENABLE_WAF")) "true" "false")}
                          ;; カスタムドメインは、証明書のスタック (`deploy recapcoach-certificate`) があれば自動で作る。
                          ;; RECAPCOACH_API_DOMAIN_NAME / RECAPCOACH_API_CERTIFICATE_ARN で上書きできる
                          api-cert
                          (assoc :ApiDomainName (or (System/getenv "RECAPCOACH_API_DOMAIN_NAME")
                                                    (:recapcoach-domain-name param))
                                 :ApiCertificateArn api-cert)
                          ;; カスタムドメインがあれば既定の execute-api のエンドポイントは塞ぐ (RECAPCOACH_DISABLE_EXECUTE_API_ENDPOINT=false で残す)
                          (and api-cert (not= "false" (System/getenv "RECAPCOACH_DISABLE_EXECUTE_API_ENDPOINT")))
                          (assoc :DisableExecuteApiEndpoint "true"))
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
