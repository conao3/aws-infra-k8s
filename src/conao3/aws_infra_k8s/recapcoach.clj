(ns conao3.aws-infra-k8s.recapcoach
  "recapcoach (conao3/ts-recapcoach) のバックエンド。API Gateway (REST API) + Cognito + WAF の入口、
  モノリスの API Lambda、重い処理を SNS → SQS → コンシューマの Lambda で非同期に処理し、結果を DynamoDB (TTL) に置く。
  LLM は Vercel AI Gateway (キーとモデル ID は SSM パラメータを参照する)。k8s には載せない。
  コードは ts-recapcoach の `pnpm --filter @recapcoach/api build` の成果物 (apps/api/dist/lambda の index.mjs と worker.mjs)。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
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

;; --- Cognito (ユーザー作成が必須。ホスト UI + PKCE の認可コードフローで ID トークンを得る) ---

(defn resource-user-pool []
  {:Type "AWS::Cognito::UserPool"
   :Properties
   {:UserPoolName (prefix-name "users")
    :UsernameAttributes ["email"]
    :AutoVerifiedAttributes ["email"]
    :MfaConfiguration "OFF"
    :Policies {:PasswordPolicy {:MinimumLength 10
                                :RequireUppercase true
                                :RequireLowercase true
                                :RequireNumbers true
                                :RequireSymbols false
                                :TemporaryPasswordValidityDays 7}}
    :AccountRecoverySetting {:RecoveryMechanisms [{:Name "verified_email" :Priority 1}]}
    :AdminCreateUserConfig {:AllowAdminCreateUserOnly false}}})

(defn resource-user-pool-domain []
  {:Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain (sub "${Prefix}-recapcoach-${AWS::AccountId}")
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-client []
  {:Type "AWS::Cognito::UserPoolClient"
   :Properties
   {:ClientName (prefix-name "extension")
    :UserPoolId {:Ref :UserPool}
    :GenerateSecret false
    ;; ホスト UI の認可コードフローだけ。パスワードを直接送るフローは許可しない
    :ExplicitAuthFlows ["ALLOW_REFRESH_TOKEN_AUTH"]
    :AllowedOAuthFlows ["code"]
    :AllowedOAuthFlowsUserPoolClient true
    :AllowedOAuthScopes ["openid" "email" "profile"]
    :SupportedIdentityProviders ["COGNITO"]
    ;; Chrome 拡張の chrome.identity.launchWebAuthFlow のリダイレクト先
    :CallbackURLs [(sub "https://${ExtensionId}.chromiumapp.org/")]
    :LogoutURLs [(sub "https://${ExtensionId}.chromiumapp.org/")]
    :PreventUserExistenceErrors "ENABLED"
    :EnableTokenRevocation true
    :TokenValidityUnits {:IdToken "minutes" :AccessToken "minutes" :RefreshToken "days"}
    :IdTokenValidity 60
    :AccessTokenValidity 60
    :RefreshTokenValidity 30}})

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

(defn resource-api-function-role []
  (lambda-role
   :ApiLogGroup
   [{:Effect "Allow"
     :Action ["dynamodb:PutItem" "dynamodb:GetItem" "dynamodb:DeleteItem"]
     :Resource (get-att :JobsTable :Arn)}
    {:Effect "Allow"
     :Action "sns:Publish"
     :Resource {:Ref :JobsTopic}}]))

(defn resource-worker-function-role []
  (lambda-role
   :WorkerLogGroup
   [{:Effect "Allow"
     :Action ["dynamodb:GetItem" "dynamodb:UpdateItem"]
     :Resource (get-att :JobsTable :Arn)}
    {:Effect "Allow"
     :Action ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"]
     :Resource (get-att :JobsQueue :Arn)}]))

;; --- Lambda ---

(defn resource-api-function [code-uri]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["ApiLogGroup"]
   :Properties
   {:FunctionName (prefix-name "api")
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
      :ALLOWED_ORIGINS (sub "chrome-extension://${ExtensionId}")
      :JOBS_TABLE {:Ref :JobsTable}
      :JOBS_TOPIC_ARN {:Ref :JobsTopic}}}
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
      :JOBS_TABLE {:Ref :JobsTable}}}
    :Events
    {:Jobs {:Type "SQS"
            :Properties {:Queue (get-att :JobsQueue :Arn)
                         :BatchSize 1
                         :FunctionResponseTypes ["ReportBatchItemFailures"]
                         ;; LLM への同時リクエストの上限
                         :ScalingConfig {:MaximumConcurrency 5}}}}}})

;; --- API Gateway (REST API) ---

(defn resource-api []
  {:Type "AWS::Serverless::Api"
   ;; アクセスログの出力先ロールが設定されてからステージを作る
   :DependsOn ["ApiGatewayAccount"]
   :Properties
   {:Name (prefix-name "api")
    :StageName stage-name
    :OpenApiVersion "3.0.1"
    :EndpointConfiguration {:Type "REGIONAL"}
    :Auth {:DefaultAuthorizer "CognitoAuth"
           :Authorizers {:CognitoAuth {:UserPoolArn (get-att :UserPool :Arn)
                                       :Identity {:Header "Authorization"}}}}
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
   :Properties
   {:WebACLArn (get-att :WebAcl :Arn)
    :ResourceArn (sub "arn:aws:apigateway:${AWS::Region}::/restapis/${Api}/stages/${Api.Stage}")}})

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     {:AiGatewayApiKey {:Type "AWS::SSM::Parameter::Value<String>"
                        :Description "Vercel AI Gateway の API キーを置いた SSM パラメータ (String) の名前"}
      :AiGatewayModel {:Type "AWS::SSM::Parameter::Value<String>"
                       :Description "AI Gateway のモデル ID (anthropic/claude-sonnet-4.6 など) を置いた SSM パラメータ (String) の名前"}
      :ExtensionId {:Type "String"
                    :AllowedPattern "[a-p]{32}"
                    :Description "Chrome 拡張の ID。CORS の許可オリジンと Cognito のコールバック URL になる"}})

    :Resources
    {:ApiLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-recapcoach-api"))
     :WorkerLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-recapcoach-worker"))
     :ApiAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-recapcoach-api-access"))

     :UserPool (resource-user-pool)
     :UserPoolDomain (resource-user-pool-domain)
     :UserPoolClient (resource-user-pool-client)

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
     :Api (resource-api)

     :WebAcl (resource-web-acl)
     :WebAclAssociation (resource-web-acl-association)}

    :Outputs
    (a.cfn/list-outputs
     {:RecapcoachApiUrl (sub (str "https://${Api}.execute-api.${AWS::Region}.amazonaws.com/${Api.Stage}"))
      :RecapcoachApiFunction {:Ref :ApiFunction}
      :RecapcoachWorkerFunction {:Ref :WorkerFunction}
      :RecapcoachUserPoolId {:Ref :UserPool}
      :RecapcoachUserPoolClientId {:Ref :UserPoolClient}
      :RecapcoachHostedUiDomain (sub "${UserPoolDomain}.auth.${AWS::Region}.amazoncognito.com")
      :RecapcoachJobsTable {:Ref :JobsTable}
      :RecapcoachJobsDlqUrl {:Ref :JobsDlq}})}))

(defn- required-env [name]
  (let [v (System/getenv name)]
    (when (str/blank? v)
      (throw (ex-info (format "%s is not set" name) {:name name})))
    v))

(defn deploy [param]
  (let [file (fs/file "target/cfn/recapcoach.json")
        prefix (-> param :prefix)
        stack-name (str prefix "-" "recapcoach")
        code-uri (str (fs/absolutize
                       (or (System/getenv "RECAPCOACH_DIST")
                           (fs/expand-home "~/ghq/github.com/conao3/ts-recapcoach/apps/api/dist/lambda"))))
        extension-id (required-env "RECAPCOACH_EXTENSION_ID")]
    (doseq [entry ["index.mjs" "worker.mjs"]]
      (when-not (fs/exists? (fs/path code-uri entry))
        (throw (ex-info (format "build first: pnpm --filter @recapcoach/api build (%s/%s)" code-uri entry)
                        {:code-uri code-uri :entry entry}))))
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn (assoc param :code-uri code-uri))
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
                   (->> {:Env (-> param :env)
                         :Prefix prefix
                         :AiGatewayApiKey (format "/%s/recapcoach/ai-gateway-api-key" prefix)
                         :AiGatewayModel (format "/%s/recapcoach/ai-gateway-model" prefix)
                         :ExtensionId extension-id}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
