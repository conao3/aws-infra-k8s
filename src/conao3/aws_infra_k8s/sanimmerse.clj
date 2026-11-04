(ns conao3.aws-infra-k8s.sanimmerse
  "sanimmerse (sancode-org/sanimmerse) のクラウド。Cloudflare (proxied) の背後の API Gateway の HTTP API から、
  Lambda `api` (Hono。Web アプリ・GraphQL・同期・課金の入口) を呼ぶ。認証は共有の Cognito ユーザープール
  (cognito スタック。先にデプロイする) の ID トークン、Sign in with Apple の identity トークン、端末トークンで、アプリが検証する。
  データは DynamoDB の users・data・tokens・shared-rules の 4 表、エージェントの配布物は S3 の downloads バケット。
  Stripe の契約イベントは、SSM `/<prefix>/sanimmerse/stripe-*` がそろっているときだけ、
  パートナーイベントソースのバス → ルール → SQS (+DLQ) → 同じ成果物の Lambda `billing`。
  Sign in with Apple の REST API の鍵は、SSM `/<prefix>/sanimmerse/apple-*` がそろっているときだけ api に渡す。k8s には載せない。
  コードは sanimmerse の apps/api/dist/lambda (index.mjs・billing.mjs・web/)。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

;; HTTP API の統合タイムアウトの上限は 30 秒。api の Lambda はそれより短くする
(def api-timeout-sec 29)
(def api-memory-mb 1024)
(def http-integration-timeout-ms 30000)
(def billing-timeout-sec 30)
(def billing-memory-mb 256)
;; SQS の可視性タイムアウトは関数のタイムアウトの 6 倍
(def billing-visibility-timeout-sec (* 6 billing-timeout-sec))
(def log-retention-days 30)
(def runtime "nodejs24.x")
;; Sign in with Apple の identity トークンの aud (iOS アプリの bundle ID)
(def apple-audience "dev.sancode.sanimmerse")
;; 成果物のディレクトリに要るもの
(def dist-entries ["index.mjs" "billing.mjs" "web"])

;; 表の項目の操作。TransactWriteItems は中の項目ごとの操作 (PutItem・UpdateItem・DeleteItem・ConditionCheckItem) で許可される
(def item-actions
  ["dynamodb:GetItem" "dynamodb:PutItem" "dynamodb:UpdateItem" "dynamodb:DeleteItem"
   "dynamodb:Query" "dynamodb:BatchGetItem" "dynamodb:BatchWriteItem" "dynamodb:ConditionCheckItem"])

;; --- SSM パラメータ (String、`/<prefix>/sanimmerse/<名前>`)。Stripe・Apple のそれぞれ全部あるときだけ使う ---
;; [名前 環境変数 渡す関数]。環境変数が nil のものはスタックのパラメータとして読む

(def stripe-parameters
  [["stripe-secret-key" "STRIPE_SECRET_KEY" #{:api}]
   ["stripe-price-pro" "STRIPE_PRICE_PRO" #{:api :billing}]
   ["stripe-price-max" "STRIPE_PRICE_MAX" #{:api :billing}]
   ["stripe-price-ultra" "STRIPE_PRICE_ULTRA" #{:api :billing}]
   ["stripe-portal-config" "STRIPE_PORTAL_CONFIG" #{:api}]
   ["stripe-event-source" nil #{}]])

(def apple-parameters
  [["apple-key-id" "APPLE_KEY_ID" #{:api}]
   ["apple-team-id" "APPLE_TEAM_ID" #{:api}]
   ["apple-private-key" "APPLE_PRIVATE_KEY" #{:api}]])

(defn- ssm-name [prefix name] (format "/%s/sanimmerse/%s" prefix name))

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})
(defn- prefix-name [suffix] (sub (str "${Prefix}-sanimmerse-" suffix)))

;; 共有の Cognito ユーザープール (cognito スタックの Export)。sanimmerse → cognito の順に消す
(defn- import-value [export-suffix]
  {"Fn::ImportValue" (sub (str "${Prefix}-" export-suffix))})

(defn- ssm-ref
  "SSM パラメータの動的参照。versions にバージョンがあれば固定する (値を変えるとテンプレートが変わり、次の deploy で Lambda に届く)。"
  [versions name]
  (sub (str "{{resolve:ssm:/${Prefix}/sanimmerse/" name
            (when-let [v (get versions name)] (str ":" v))
            "}}")))

(defn- ssm-env [parameters versions function]
  (into {}
        (for [[name env-name functions] parameters
              :when (and env-name (functions function))]
          [(keyword env-name) (ssm-ref versions name)])))

(defn- secure-transport-deny [action resource-arn]
  {:Sid "DenyInsecureTransport"
   :Effect "Deny"
   :Principal "*"
   :Action action
   :Resource resource-arn
   :Condition {:Bool {"aws:SecureTransport" "false"}}})

(defn resource-log-group [name]
  {:Type "AWS::Logs::LogGroup"
   :Properties {:LogGroupName name
                :RetentionInDays log-retention-days}})

;; --- データ ---

(defn- table [suffix keys & {:keys [protect? pitr?]}]
  {:Type "AWS::DynamoDB::Table"
   :Properties
   (cond-> {:TableName (prefix-name suffix)
            :BillingMode "PAY_PER_REQUEST"
            :AttributeDefinitions (mapv (fn [[attr]] {:AttributeName attr :AttributeType "S"}) keys)
            :KeySchema (mapv (fn [[attr key-type]] {:AttributeName attr :KeyType key-type}) keys)}
     pitr? (assoc :PointInTimeRecoverySpecification {:PointInTimeRecoveryEnabled true})
     protect? (assoc :DeletionProtectionEnabled true))})

;; 小文字のメールアドレス。属性は userId・plan・Stripe の顧客と契約
(defn resource-users-table []
  (table "users" [["email" "HASH"]] :protect? true :pitr? true))

;; アカウントの項目。sk は A#<活動 ID>・R#<field>#<service>#<value>・S・O#<端末>#<アプリ>・T#<トークンのハッシュ>
(defn resource-data-table []
  (table "data" [["userId" "HASH"] ["sk" "RANGE"]] :protect? true :pitr? true))

;; 端末トークン。キーはトークンの SHA-256。属性は userId・name・createdAt
(defn resource-tokens-table []
  (table "tokens" [["tokenHash" "HASH"]] :protect? true))

;; 共有されたルール。key は <field>#<service>#<value>。data の R# の写しなので作り直せる
(defn resource-shared-rules-table []
  (table "shared-rules" [["key" "HASH"] ["userId" "RANGE"]]))

;; --- 配布物 (エージェントの実行ファイル)。api が署名付き URL へ転送する ---

(defn resource-downloads-bucket []
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
    {:Rules [{:Id "abort-incomplete-uploads"
              :Status "Enabled"
              :AbortIncompleteMultipartUpload {:DaysAfterInitiation 1}}]}}})

(defn resource-downloads-bucket-policy []
  {:Type "AWS::S3::BucketPolicy"
   :Properties
   {:Bucket {:Ref :DownloadsBucket}
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny "s3:*" [(get-att :DownloadsBucket :Arn)
                                                (sub "${DownloadsBucket.Arn}/*")])]}}})

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
      (secure-transport-deny "sqs:*" (get-att :BillingQueue :Arn))]}}})

(defn resource-billing-dlq-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :BillingDlq}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement [(secure-transport-deny "sqs:*" (get-att :BillingDlq :Arn))]}}})

;; --- IAM (関数ごと。ロググループ・表・バケットは使うものだけ) ---

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
   "sanimmerse-api"
   :ApiLogGroup
   [{:Effect "Allow"
     :Action item-actions
     :Resource [(get-att :UsersTable :Arn)
                (get-att :DataTable :Arn)
                (get-att :TokensTable :Arn)
                (get-att :SharedRulesTable :Arn)]}
    ;; 共有ルールは表ごと読んで 10 分キャッシュする
    {:Effect "Allow"
     :Action "dynamodb:Scan"
     :Resource (get-att :SharedRulesTable :Arn)}
    ;; 署名付き URL の転送先。ListBucket があると無いキーが 404 (NoSuchKey) になる
    {:Effect "Allow"
     :Action "s3:GetObject"
     :Resource (sub "${DownloadsBucket.Arn}/*")}
    {:Effect "Allow"
     :Action "s3:ListBucket"
     :Resource (get-att :DownloadsBucket :Arn)}]))

(defn resource-billing-function-role []
  (lambda-role
   "sanimmerse-billing"
   :BillingLogGroup
   [{:Effect "Allow"
     :Action ["dynamodb:GetItem" "dynamodb:UpdateItem"]
     :Resource (get-att :UsersTable :Arn)}
    {:Effect "Allow"
     :Action ["dynamodb:GetItem" "dynamodb:Query" "dynamodb:PutItem" "dynamodb:UpdateItem"]
     :Resource (get-att :DataTable :Arn)}
    {:Effect "Allow"
     :Action ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"]
     :Resource (get-att :BillingQueue :Arn)}]))

;; --- Lambda (同じ成果物、ハンドラだけ違う) ---

(defn- public-url []
  {"Fn::If" ["UseApiDomain"
             (sub "https://${ApiDomainName}")
             (get-att :HttpApi :ApiEndpoint)]})

(defn resource-api-function [code-uri stripe? apple? versions]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["ApiLogGroup"]
   :Properties
   {:FunctionName (prefix-name "api")
    :Runtime runtime
    :Architectures ["arm64"]
    :Handler "index.handler"
    :CodeUri code-uri
    :LoggingConfig {:LogFormat "JSON"}
    :Timeout api-timeout-sec
    :MemorySize api-memory-mb
    :Role (get-att :ApiFunctionRole :Arn)
    :Environment
    {:Variables
     (merge
      {:USERS_TABLE {:Ref :UsersTable}
       :DATA_TABLE {:Ref :DataTable}
       :TOKENS_TABLE {:Ref :TokensTable}
       :SHARED_RULES_TABLE {:Ref :SharedRulesTable}
       :DOWNLOADS_BUCKET {:Ref :DownloadsBucket}
       :COGNITO_USER_POOL_ID (import-value "UserPool")
       ;; ID トークンの aud がこのクライアントのものか、API で確かめる
       :COGNITO_CLIENT_ID (import-value "SanimmerseUserPoolClient")
       :AUTH_DOMAIN (import-value "UserPoolDomain")
       :APPLE_AUDIENCE apple-audience
       :PUBLIC_URL (public-url)
       ;; カスタムドメインがあるとき、Cloudflare の IP 範囲の外からの要求をアプリが 403 で拒む
       :ALLOWED_SOURCES {"Fn::If" ["UseApiDomain" "cloudflare" "any"]}}
      (when stripe? (ssm-env stripe-parameters versions :api))
      (when apple? (ssm-env apple-parameters versions :api)))}}})

(defn resource-billing-function [code-uri versions]
  {:Type "AWS::Serverless::Function"
   :DependsOn ["BillingLogGroup"]
   :Properties
   {:FunctionName (prefix-name "billing")
    :Runtime runtime
    :Architectures ["arm64"]
    :Handler "billing.handler"
    :CodeUri code-uri
    :LoggingConfig {:LogFormat "JSON"}
    :Timeout billing-timeout-sec
    :MemorySize billing-memory-mb
    :Role (get-att :BillingFunctionRole :Arn)
    :Environment
    {:Variables
     (merge
      {:USERS_TABLE {:Ref :UsersTable}
       :DATA_TABLE {:Ref :DataTable}}
      (ssm-env stripe-parameters versions :billing))}
    :Events
    {:Stripe {:Type "SQS"
              :Properties {:Queue (get-att :BillingQueue :Arn)
                           :BatchSize 1
                           :FunctionResponseTypes ["ReportBatchItemFailures"]}}}}})

;; --- API Gateway: HTTP API (`$default` の 1 ルート。認証はアプリ) ---

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
    ;; Authorization・Cookie・クエリ・本文は出さない
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

;; --- テンプレート ---

(defn cfn
  "code-uri は成果物のディレクトリ。stripe? で Stripe の経路を作り、apple? で api に Apple の鍵を渡す。ssm-versions は SSM パラメータの名前からバージョンへの写像 (nil ならバージョンを付けない)。"
  [{:keys [code-uri stripe? apple? ssm-versions]}]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     {:ApiDomainName {:Type "String" :Default ""}
      :CertificateArn {:Type "String" :Default ""}
      ;; Stripe のイベント送信先 (Amazon EventBridge) が作るパートナーイベントソースの名前 (aws.partner/stripe.com/ed_...)。
      ;; 既定は使われない値 (Stripe の経路を作らないとき、リソースごと無い)。空だと cfn-lint が EventBus の名前の長さで落ちる
      :StripeEventSourceName {:Type "String" :Default "aws.partner/stripe.com/not-configured"}})

    :Conditions
    {:UseApiDomain {"Fn::And" [{"Fn::Not" [{"Fn::Equals" [{:Ref :ApiDomainName} ""]}]}
                               {"Fn::Not" [{"Fn::Equals" [{:Ref :CertificateArn} ""]}]}]}}

    :Resources
    (merge
     {:ApiLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-sanimmerse-api"))
      :HttpAccessLogGroup (resource-log-group (sub "/aws/apigateway/${Prefix}-sanimmerse-http-access"))

      :UsersTable (resource-users-table)
      :DataTable (resource-data-table)
      :TokensTable (resource-tokens-table)
      :SharedRulesTable (resource-shared-rules-table)
      :DownloadsBucket (resource-downloads-bucket)
      :DownloadsBucketPolicy (resource-downloads-bucket-policy)

      :ApiFunctionRole (resource-api-function-role)
      :ApiFunction (resource-api-function code-uri stripe? apple? ssm-versions)

      :HttpApi (resource-http-api)
      :HttpIntegration (resource-http-integration)
      :HttpDefaultRoute (resource-http-route)
      :HttpStage (resource-http-stage)
      :HttpInvokePermission (resource-invoke-permission)
      :HttpDomain (resource-domain-name)
      :HttpApiMapping (resource-api-mapping)}
     ;; Stripe の設定 (SSM) が揃っているときだけ、契約イベントの経路を作る
     (when stripe?
       {:BillingLogGroup (resource-log-group (sub "/aws/lambda/${Prefix}-sanimmerse-billing"))
        :BillingDlq (resource-billing-dlq)
        :BillingQueue (resource-billing-queue)
        :BillingQueuePolicy (resource-billing-queue-policy)
        :BillingDlqPolicy (resource-billing-dlq-policy)
        :StripeEventBus (resource-stripe-event-bus)
        :StripeRule (resource-stripe-rule)
        :BillingFunctionRole (resource-billing-function-role)
        :BillingFunction (resource-billing-function code-uri ssm-versions)}))

    :Outputs
    (merge
     (a.cfn/list-outputs
      {:SanimmersePublicUrl (public-url)
       :SanimmerseUsersTable {:Ref :UsersTable}
       :SanimmerseDataTable {:Ref :DataTable}
       :SanimmerseTokensTable {:Ref :TokensTable}
       :SanimmerseSharedRulesTable {:Ref :SharedRulesTable}
       :SanimmerseDownloadsBucket {:Ref :DownloadsBucket}
       :SanimmerseApiFunction {:Ref :ApiFunction}})
     (when stripe?
       {:SanimmerseBillingFunction {:Value {:Ref :BillingFunction}}
        :SanimmerseBillingDlqUrl {:Value {:Ref :BillingDlq}}})
     ;; Cloudflare の CNAME の向け先 (カスタムドメインを作ったときだけ)
     {:SanimmerseApiDomainTarget {:Condition "UseApiDomain"
                                  :Value (get-att :HttpDomain :RegionalDomainName)}})}))

;; --- デプロイ ---

(defn- export-value
  "ap-northeast-1 の CloudFormation の Export の値。無ければ nil。"
  [export-name]
  (let [out (str/trim (:out (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1"
                                           "--query" (format "Exports[?Name=='%s'].Value | [0]" export-name)
                                           "--output" "text")))]
    (when-not (contains? #{"" "None"} out) out)))

(defn- ssm-values
  "parameters の SSM `/<prefix>/sanimmerse/<名前>` が全部あれば、名前から {:version :value} への写像を返す。1 つでも無ければ nil
   (その設定を使わない)。"
  [prefix parameters]
  (let [names (mapv (fn [[name]] (ssm-name prefix name)) parameters)
        found (->> (-> (apply c.util/eshell {:out :string} "aws" "ssm" "get-parameters" "--region" "ap-northeast-1" "--names"
                              (concat names ["--query" "Parameters[*].{name:Name,version:Version,value:Value}" "--output" "json"]))
                       :out
                       (json/parse-string true))
                   (into {} (map (fn [{:keys [name version value]}]
                                   [(last (str/split name #"/")) {:version version :value value}]))))]
    (when (= (count found) (count names)) found)))

(defn- code-uri []
  (let [dir (str (fs/absolutize
                  (or (System/getenv "SANIMMERSE_DIST")
                      (fs/expand-home "~/ghq/github.com/sancode-org/sanimmerse/apps/api/dist/lambda"))))]
    (doseq [entry dist-entries]
      (when-not (fs/exists? (fs/path dir entry))
        (throw (ex-info (format "build the Lambda bundle of sanimmerse first (%s/%s)" dir entry)
                        {:code-uri dir :entry entry}))))
    dir))

(defn- write-template [file input]
  (fs/create-dirs (fs/parent file))
  (c.util/eprintln (format "Write: %s" (fs/path file)))
  (with-open [writer (io/writer file)]
    (json/generate-stream (cfn input) writer)))

(defn template
  "AWS を呼ばずにテンプレートを target/cfn/sanimmerse.json (Stripe と Apple あり) と sanimmerse-without-ssm.json (どちらもなし) に書く
   (検証用。CodeUri は仮の値、SSM のバージョンは付けない)。"
  [_param]
  (doseq [[file ssm?] [["target/cfn/sanimmerse.json" true]
                       ["target/cfn/sanimmerse-without-ssm.json" false]]]
    (write-template (fs/file file) {:code-uri "dist/lambda" :stripe? ssm? :apple? ssm? :ssm-versions nil})
    (println (str (fs/absolutize file)))))

(defn deploy [param]
  (let [file (fs/file "target/cfn/sanimmerse.json")
        prefix (:prefix param)
        stack-name (str prefix "-sanimmerse")
        dir (code-uri)
        stripe (ssm-values prefix stripe-parameters)
        apple (ssm-values prefix apple-parameters)
        cert (or (System/getenv "SANIMMERSE_CERTIFICATE_ARN")
                 (export-value (str prefix "-SanimmerseCertificateArn")))]
    (write-template file {:code-uri dir
                          :stripe? (boolean stripe)
                          :apple? (boolean apple)
                          :ssm-versions (update-vals (merge stripe apple) :version)})
    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-trimmed-ssm-values (format "/%s/sanimmerse/" prefix))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--capabilities" "CAPABILITY_IAM"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (->> (cond-> {:Env (:env param)
                                 :Prefix prefix}
                          ;; カスタムドメインは、証明書のスタック (`deploy sanimmerse-certificate`) があれば作る
                          cert
                          (assoc :ApiDomainName (:sanimmerse-domain-name param)
                                 :CertificateArn cert)
                          stripe
                          (assoc :StripeEventSourceName (get-in stripe ["stripe-event-source" :value])))
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
