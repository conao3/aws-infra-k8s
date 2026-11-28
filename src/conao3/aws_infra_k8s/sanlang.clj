(ns conao3.aws-infra-k8s.sanlang
  "Optional Sanlang server inference. Repository and regional certificate precede the backend; never part of deploy all.
  Stripe billing (partner event bus → rule → SQS (+DLQ) → billing Lambda on a 5 minute schedule) exists only when
  the SSM parameters /<prefix>/sanlang/stripe-* all exist."
  (:refer-clojure :exclude [ref])
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.certificate :as c.certificate]
   [conao3.aws-infra-k8s.cognito :as c.cognito]
   [conao3.aws-infra-k8s.ecr :as c.ecr]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

(defn- sub [s] {"Fn::Sub" s})
(defn- ref [id] {:Ref id})
(defn- get-att [id attr] {"Fn::GetAtt" [id attr]})
(defn- named [suffix] (sub (str "${Prefix}-sanlang-" suffix)))
(defn- imported [suffix] {"Fn::ImportValue" (sub (str "${Prefix}-" suffix))})

(defn repository-cfn [_param]
  (a.cfn/template
   {:Parameters (a.cfn/list-string-parameters [:Env :Prefix])
    :Resources
    {:Repository
     (-> (c.ecr/resource-ecr-repository "sanlang-translate")
         (assoc :DeletionPolicy "Retain" :UpdateReplacePolicy "Retain")
         (assoc-in [:Properties :ImageTagMutability] "IMMUTABLE")
         (assoc-in [:Properties :RepositoryPolicyText]
                   {:Version "2012-10-17"
                    :Statement [{:Sid "LambdaImagePull"
                                 :Effect "Allow"
                                 :Principal {:Service "lambda.amazonaws.com"}
                                 :Action ["ecr:BatchGetImage" "ecr:GetDownloadUrlForLayer"]
                                 :Condition {:StringEquals {"aws:sourceAccount" (ref "AWS::AccountId")}
                                             :ArnLike {"aws:sourceArn" (sub "arn:${AWS::Partition}:lambda:${AWS::Region}:${AWS::AccountId}:function:${Prefix}-sanlang-translate")}}}]}))}
    :Outputs (a.cfn/list-outputs
              {:SanlangRepositoryUri (get-att :Repository :RepositoryUri)
               :SanlangRepositoryName (ref :Repository)})}))

(defn- log-group [suffix]
  {:Type "AWS::Logs::LogGroup"
   :DeletionPolicy "Retain" :UpdateReplacePolicy "Retain"
   :Properties {:LogGroupName (sub (str "/aws/lambda/${Prefix}-sanlang-" suffix))
                :RetentionInDays 30}})

(defn- allow [actions resource-id]
  {:Effect "Allow" :Action actions :Resource (get-att resource-id :Arn)})

(defn- role [log-id statements]
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow" :Principal {:Service "lambda.amazonaws.com"} :Action "sts:AssumeRole"}]}
    :Policies [{:PolicyName "Runtime"
                :PolicyDocument
                {:Version "2012-10-17"
                 :Statement (into [(allow ["logs:CreateLogStream" "logs:PutLogEvents"] log-id)] statements)}}]}})

(defn- table [suffix keys]
  {:Type "AWS::DynamoDB::Table"
   :DeletionPolicy "Retain" :UpdateReplacePolicy "Retain"
   :Properties {:TableName (named suffix) :BillingMode "PAY_PER_REQUEST"
                :AttributeDefinitions (mapv (fn [[attr]] {:AttributeName attr :AttributeType "S"}) keys)
                :KeySchema (mapv (fn [[attr key-type]] {:AttributeName attr :KeyType key-type}) keys)
                :DeletionProtectionEnabled true
                :PointInTimeRecoverySpecification {:PointInTimeRecoveryEnabled true}}})

(def stripe-parameters
  [["stripe-secret-key" "STRIPE_SECRET_KEY" #{:api}]
   ["stripe-price-pro" "STRIPE_PRICE_PRO" #{:api :billing}]
   ["stripe-portal-config" "STRIPE_PORTAL_CONFIG" #{:api}]
   ["stripe-event-source" nil #{}]])

(defn- ssm-ref [versions name]
  (sub (str "{{resolve:ssm:/${Prefix}/sanlang/" name
            (when-let [v (get versions name)] (str ":" v))
            "}}")))

(defn- ssm-env [versions function]
  (into {}
        (for [[name env-name functions] stripe-parameters
              :when (and env-name (functions function))]
          [(keyword env-name) (ssm-ref versions name)])))

(defn- common-environment []
  {:COGNITO_USER_POOL_ID (imported "UserPool")
   :COGNITO_CLIENT_ID (imported "SanlangUserPoolClient")
   :AUTH_DOMAIN (imported "UserPoolDomain")
   :USAGE_TABLE (ref :UsageTable)
   :USERS_TABLE (ref :UsersTable)
   :ALLOWED_SOURCES (ref :AllowedSources)
   :ALLOWED_ORIGINS (ref :WebOrigin)
   :MAX_INPUT_CHARACTERS (ref :MaxInputCharacters)
   :FREE_MONTHLY_CHARACTER_LIMIT (ref :FreeMonthlyCharacterLimit)
   :PRO_MONTHLY_CHARACTER_LIMIT (ref :ProMonthlyCharacterLimit)})

(def routes
  [[:TranslatePost :TranslateResource "POST" :TranslateFunction true]
   [:TranslateOptions :TranslateResource "OPTIONS" :ApiFunction false]
   [:ConfigGet :ConfigResource "GET" :ApiFunction false]
   [:ConfigOptions :ConfigResource "OPTIONS" :ApiFunction false]
   [:MeGet :MeResource "GET" :ApiFunction false]
   [:MeOptions :MeResource "OPTIONS" :ApiFunction false]
   [:BillingCheckoutPost :BillingCheckoutResource "POST" :ApiFunction false]
   [:BillingCheckoutOptions :BillingCheckoutResource "OPTIONS" :ApiFunction false]
   [:BillingPortalPost :BillingPortalResource "POST" :ApiFunction false]
   [:BillingPortalOptions :BillingPortalResource "OPTIONS" :ApiFunction false]])

(def path-resources
  [[:V1Resource nil "v1"]
   [:TranslateResource :V1Resource "translate"]
   [:ConfigResource :V1Resource "config"]
   [:MeResource :V1Resource "me"]
   [:BillingResource :V1Resource "billing"]
   [:BillingCheckoutResource :BillingResource "checkout"]
   [:BillingPortalResource :BillingResource "portal"]])

(defn resource-path [id]
  (let [[_ parent part] (first (filter #(= id (first %)) path-resources))]
    (if parent (str (resource-path parent) "/" part) (str "/" part))))

(defn- method-resource [[_ resource method function streaming?]]
  {:Type "AWS::ApiGateway::Method"
   :Properties
   {:RestApiId (ref :RestApi) :ResourceId (ref resource) :HttpMethod method
    :AuthorizationType "NONE"
    :Integration {:Type "AWS_PROXY" :IntegrationHttpMethod "POST"
                  :ResponseTransferMode (if streaming? "STREAM" "BUFFERED")
                  :TimeoutInMillis (if streaming? 300000 29000)
                  :Uri (sub (str "arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/"
                                 (if streaming? "2021-11-15" "2015-03-31")
                                 "/functions/${" (name function) ".Arn}/"
                                 (if streaming? "response-streaming-invocations" "invocations")))}}})

(defn- invoke-permission [[_ resource method function _]]
  {:Type "AWS::Lambda::Permission"
   :Properties
   {:Action "lambda:InvokeFunction" :FunctionName (ref function)
    :Principal "apigateway.amazonaws.com"
    :SourceAccount (ref "AWS::AccountId")
    :SourceArn (sub (str "arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${RestApi}/*/"
                         method (resource-path resource)))}})

(defn- path-resource [parent path]
  {:Type "AWS::ApiGateway::Resource"
   :Properties {:RestApiId (ref :RestApi)
                :ParentId (if parent (ref parent) (get-att :RestApi :RootResourceId))
                :PathPart path}})

(defn- api-resources []
  (merge
   {:RestApi {:Type "AWS::ApiGateway::RestApi"
              :Properties {:Name (named "rest")
                           :EndpointConfiguration {:Types ["REGIONAL"]}
                           :DisableExecuteApiEndpoint true}}}
   (into {} (for [[id parent part] path-resources] [id (path-resource parent part)]))
   (into {} (for [[id :as route] routes] [id (method-resource route)]))))

(defn deployment-id [resources]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (json/generate-string resources) "UTF-8"))]
    (keyword (str "Deployment" (subs (format "%064x" (BigInteger. 1 digest)) 0 16)))))

(defn- secure-transport-deny [resource-id]
  {:Sid "DenyInsecureTransport" :Effect "Deny" :Principal "*" :Action "sqs:*"
   :Resource (get-att resource-id :Arn)
   :Condition {:Bool {"aws:SecureTransport" "false"}}})

(defn- billing-resources [versions]
  {:BillingLogGroup (dissoc (log-group "billing") :DeletionPolicy :UpdateReplacePolicy)
   :BillingDlq {:Type "AWS::SQS::Queue"
                :Properties {:QueueName (named "billing-dlq") :MessageRetentionPeriod 1209600
                             :SqsManagedSseEnabled false}}
   :BillingQueue {:Type "AWS::SQS::Queue"
                  :Properties {:QueueName (named "billing") :VisibilityTimeout 180
                               :MessageRetentionPeriod 345600 :SqsManagedSseEnabled false
                               :RedrivePolicy {:deadLetterTargetArn (get-att :BillingDlq :Arn) :maxReceiveCount 5}}}
   :BillingQueuePolicy {:Type "AWS::SQS::QueuePolicy"
                        :Properties {:Queues [(ref :BillingQueue)]
                                     :PolicyDocument
                                     {:Version "2012-10-17"
                                      :Statement [{:Sid "AllowStripeRule" :Effect "Allow"
                                                   :Principal {:Service "events.amazonaws.com"}
                                                   :Action "sqs:SendMessage" :Resource (get-att :BillingQueue :Arn)
                                                   :Condition {:ArnEquals {"aws:SourceArn" (get-att :StripeRule :Arn)}}}
                                                  (secure-transport-deny :BillingQueue)]}}}
   :BillingDlqPolicy {:Type "AWS::SQS::QueuePolicy"
                      :Properties {:Queues [(ref :BillingDlq)]
                                   :PolicyDocument {:Version "2012-10-17"
                                                    :Statement [(secure-transport-deny :BillingDlq)]}}}
   :StripeEventBus {:Type "AWS::Events::EventBus"
                    :Properties {:Name (ref :StripeEventSourceName) :EventSourceName (ref :StripeEventSourceName)}}
   :StripeRule {:Type "AWS::Events::Rule"
                :Properties {:Name (named "stripe") :EventBusName (ref :StripeEventBus)
                             :EventPattern {:source [{:prefix "aws.partner/stripe.com"}]
                                            :detail {:data {:object {:metadata {:app ["sanlang"]}}}}}
                             :Targets [{:Id "BillingQueue" :Arn (get-att :BillingQueue :Arn)}]}}
   :BillingRole (role :BillingLogGroup
                      [(allow ["dynamodb:GetItem" "dynamodb:UpdateItem"] :UsersTable)
                       (allow ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"] :BillingQueue)])
   :BillingFunction {:Type "AWS::Serverless::Function"
                     :Properties {:FunctionName (named "billing") :Runtime "nodejs24.x" :Handler "billing.handler"
                                  :Architectures ["arm64"]
                                  :CodeUri {:Bucket (ref :ApiCodeBucket) :Key (ref :ApiCodeKey)
                                            :Version {"Fn::If" ["HasCodeVersion" (ref :ApiCodeVersion) (ref "AWS::NoValue")]}}
                                  :Role (get-att :BillingRole :Arn) :MemorySize 256 :Timeout 30
                                  :Environment {:Variables (merge {:USERS_TABLE (ref :UsersTable)
                                                                   :BILLING_QUEUE_URL (ref :BillingQueue)}
                                                                  (ssm-env versions :billing))}
                                  :Events {:Drain {:Type "Schedule"
                                                   :Properties {:Schedule "rate(5 minutes)" :Name (named "billing-drain")
                                                                :Description "Drains the billing queue on a timer instead of an SQS event source, which polls while the queue is empty."
                                                                :Enabled true}}}}}})

(defn cfn
  "stripe? adds the Stripe billing resources and variables; ssm-versions maps each SSM name to the version its dynamic reference pins."
  [{:keys [stripe? ssm-versions]}]
  (let [api (api-resources)
        deployment (deployment-id api)]
    (a.cfn/template
     {:Parameters
      (merge
       (a.cfn/list-string-parameters [:Env :Prefix :CertificateArn :ApiCodeBucket :ApiCodeKey])
       {:ImageTag {:Type "String" :AllowedPattern "[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}"}
        :WebOrigin {:Type "String" :Description "Web origin, without trailing slash; must match Cognito SanlangWebOrigin"
                    :AllowedPattern "https://[^/\\s]+|http://localhost(:[0-9]+)?"}
        :ApiCodeVersion {:Type "String" :Default ""}
        :AllowedSources {:Type "String" :Default "cloudflare" :AllowedValues ["cloudflare" "any"]}
        :TranslateMemorySize {:Type "Number" :Default 10240 :MinValue 128 :MaxValue 10240}
        :MaxInputCharacters {:Type "Number" :Default 10000 :MinValue 1}
        :FreeMonthlyCharacterLimit {:Type "Number" :Default 100000 :MinValue 1}
        :ProMonthlyCharacterLimit {:Type "Number" :Default 2000000 :MinValue 1}}
       (when stripe?
         {:StripeEventSourceName {:Type "String" :Default "aws.partner/stripe.com/not-configured"}}))
      :Conditions {:HasCodeVersion {"Fn::Not" [{"Fn::Equals" [(ref :ApiCodeVersion) ""]}]}}
      :Resources
      (merge
       api
       {:UsageTable (table "usage" [["userId" "HASH"] ["period" "RANGE"]])
        :UsersTable (table "users" [["userId" "HASH"]])
        :TranslateLogGroup (log-group "translate")
        :ApiLogGroup (log-group "api")
        :TranslateRole (role :TranslateLogGroup [(allow ["dynamodb:UpdateItem"] :UsageTable)
                                                 (allow ["dynamodb:GetItem"] :UsersTable)])
        :ApiRole (role :ApiLogGroup [(allow ["dynamodb:GetItem"] :UsageTable)
                                     (allow ["dynamodb:GetItem" "dynamodb:PutItem" "dynamodb:UpdateItem"] :UsersTable)])
        :TranslateFunction {:Type "AWS::Lambda::Function"
                            :Properties {:FunctionName (named "translate") :PackageType "Image"
                                         :Code {:ImageUri {"Fn::Join" [":" [(imported "SanlangRepositoryUri") (ref :ImageTag)]]}} :Architectures ["arm64"]
                                         :Role (get-att :TranslateRole :Arn) :MemorySize (ref :TranslateMemorySize) :Timeout 300
                                         :Environment {:Variables (merge (common-environment)
                                                                         {:LLAMA_SERVER "/opt/bin/llama-server"
                                                                          :LLAMA_PORT "8081"
                                                                          :MODEL_PATH "/opt/models/model.gguf"
                                                                          :ADAPTER_PATH "/opt/models/adapter.gguf"})}}}
        :ApiFunction {:Type "AWS::Lambda::Function"
                      :Properties {:FunctionName (named "api") :PackageType "Zip"
                                   :Runtime "nodejs24.x" :Handler "api.handler" :Architectures ["arm64"]
                                   :Code {:S3Bucket (ref :ApiCodeBucket) :S3Key (ref :ApiCodeKey)
                                          :S3ObjectVersion {"Fn::If" ["HasCodeVersion" (ref :ApiCodeVersion) (ref "AWS::NoValue")]}}
                                   :Role (get-att :ApiRole :Arn) :MemorySize 256 :Timeout 29
                                   :Environment {:Variables (merge (common-environment)
                                                                   {:PUBLIC_URL (ref :WebOrigin)}
                                                                   (when stripe? (ssm-env ssm-versions :api)))}}}
        deployment {:Type "AWS::ApiGateway::Deployment"
                    :DependsOn (mapv (comp name first) routes)
                    :Properties {:RestApiId (ref :RestApi)}}
        :Stage {:Type "AWS::ApiGateway::Stage"
                :Properties {:RestApiId (ref :RestApi) :DeploymentId (ref deployment) :StageName "api"
                             :MethodSettings [{:ResourcePath "/*" :HttpMethod "*" :DataTraceEnabled false
                                               :CachingEnabled false :MetricsEnabled true}]}}
        :ApiDomain {:Type "AWS::ApiGateway::DomainName"
                    :Properties {:DomainName (sub "${Env}-sanlang-api.sancode.dev")
                                 :EndpointConfiguration {:Types ["REGIONAL"]}
                                 :RegionalCertificateArn (ref :CertificateArn) :SecurityPolicy "TLS_1_2"}}
        :ApiMapping {:Type "AWS::ApiGateway::BasePathMapping"
                     :Properties {:DomainName (ref :ApiDomain) :RestApiId (ref :RestApi) :Stage (ref :Stage)}}}
       (into {} (for [[id :as route] routes] [(keyword (str (name id) "Permission")) (invoke-permission route)]))
       (when stripe? (billing-resources ssm-versions)))
      :Outputs
      (merge
       (a.cfn/list-outputs
        {:SanlangApiUrl (sub "https://${Env}-sanlang-api.sancode.dev")
         :SanlangApiDomainTarget (get-att :ApiDomain :RegionalDomainName)
         :SanlangUsageTable (ref :UsageTable)
         :SanlangUsersTable (ref :UsersTable)
         :SanlangTranslateFunction (ref :TranslateFunction)
         :SanlangApiFunction (ref :ApiFunction)})
       (when stripe?
         {:SanlangBillingFunction {:Value (ref :BillingFunction)}
          :SanlangBillingDlqUrl {:Value (ref :BillingDlq)}}))})))

(defn- write-template [file value]
  (fs/create-dirs (fs/parent file))
  (with-open [writer (io/writer file)] (json/generate-stream value writer))
  (println (str (fs/absolutize file))))

(defn template
  "Writes the templates without calling AWS: sanlang.json with the Stripe billing resources (unversioned SSM references) and sanlang-without-ssm.json without them."
  [param]
  (doseq [[file value] [["sanlang.json" (cfn (assoc param :stripe? true :ssm-versions nil))]
                        ["sanlang-without-ssm.json" (cfn (assoc param :stripe? false))]
                        ["sanlang-repository.json" (repository-cfn param)]
                        ["sanlang-certificate.json" (c.certificate/cfn param :SanlangCertificateArn nil)]
                        ["sanlang-cognito.json" (c.cognito/cfn param)]]]
    (write-template (fs/file "target/cfn" file) value)))

(defn- deploy-stack [param suffix value overrides]
  (let [file (fs/file "target/cfn" (str suffix ".json"))
        stack-name (str (:prefix param) "-" suffix)]
    (write-template file value)
    (c.util/eshell (or (:cfn-lint param) "cfn-lint") "--regions" "ap-northeast-1" "--template" (str file))
    (c.util/ensure-stack-deployable stack-name)
    (apply c.util/eshell
           (concat ["sam" "deploy" "--region" "ap-northeast-1" "--template-file" (str file) "--stack-name" stack-name
                    "--capabilities" "CAPABILITY_IAM" "--resolve-s3"
                    "--no-fail-on-empty-changeset" "--on-failure" "DELETE" "--parameter-overrides"]
                   (map (fn [[k v]] (str (name k) "=" v))
                        (merge {:Env (:env param) :Prefix (:prefix param)} overrides))))))

(defn deploy-repository [param]
  (deploy-stack param "sanlang-repository" (repository-cfn param) {}))

(defn ssm-values
  "Name to {:version :value} of the SSM parameters `/<prefix>/sanlang/<name>` of stripe-parameters when all of them exist, otherwise nil."
  [prefix]
  (let [names (mapv (fn [[name]] (format "/%s/sanlang/%s" prefix name)) stripe-parameters)
        found (->> (-> (apply c.util/eshell {:out :string} "aws" "ssm" "get-parameters" "--region" "ap-northeast-1" "--names"
                              (concat names ["--query" "Parameters[*].{name:Name,version:Version,value:Value}" "--output" "json"]))
                       :out
                       (json/parse-string true))
                   (into {} (map (fn [{:keys [name version value]}]
                                   [(last (str/split name #"/")) {:version version :value value}]))))]
    (when (= (count found) (count names)) found)))

(defn deploy [param]
  (let [required {:ImageTag (or (:image-tag param) (System/getenv "SANLANG_IMAGE_TAG"))
                  :ApiCodeBucket (or (:api-code-bucket param) (System/getenv "SANLANG_API_CODE_BUCKET"))
                  :ApiCodeKey (or (:api-code-key param) (System/getenv "SANLANG_API_CODE_KEY"))
                  :CertificateArn (or (:certificate-arn param) (System/getenv "SANLANG_CERTIFICATE_ARN"))}]
    (doseq [[key value] required]
      (when (str/blank? value)
        (throw (ex-info (str "Required Sanlang deployment parameter: " (name key)) {:parameter key}))))
    (let [stripe (ssm-values (:prefix param))]
      (when stripe
        (c.util/ensure-trimmed-ssm-values (format "/%s/sanlang/" (:prefix param))))
      (deploy-stack param "sanlang" (cfn (assoc param :stripe? (boolean stripe) :ssm-versions (update-vals stripe :version)))
                    (cond-> (merge required
                                   {:WebOrigin (:sanlang-web-origin param)
                                    :ApiCodeVersion (or (:api-code-version param) "")
                                    :AllowedSources (or (:allowed-sources param) "cloudflare")
                                    :TranslateMemorySize (or (:translate-memory-size param) (System/getenv "SANLANG_TRANSLATE_MEMORY_SIZE") "10240")
                                    :MaxInputCharacters (or (:max-input-characters param) "10000")
                                    :FreeMonthlyCharacterLimit (or (:free-monthly-character-limit param) "100000")
                                    :ProMonthlyCharacterLimit (or (:pro-monthly-character-limit param) "2000000")})
                      stripe (assoc :StripeEventSourceName (get-in stripe ["stripe-event-source" :value])))))))
