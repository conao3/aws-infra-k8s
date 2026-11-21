(ns conao3.aws-infra-k8s.sanlang
  "Optional Sanlang server inference. Repository and regional certificate precede the backend; never part of deploy all."
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

(defn- role [log-id action]
  {:Type "AWS::IAM::Role"
   :Properties
   {:AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement [{:Effect "Allow" :Principal {:Service "lambda.amazonaws.com"} :Action "sts:AssumeRole"}]}
    :Policies [{:PolicyName "Runtime"
                :PolicyDocument
                {:Version "2012-10-17"
                 :Statement [{:Effect "Allow" :Action ["logs:CreateLogStream" "logs:PutLogEvents"]
                              :Resource (get-att log-id :Arn)}
                             {:Effect "Allow" :Action [action]
                              :Resource (get-att :UsageTable :Arn)}]}}]}})

(defn- common-environment []
  {:COGNITO_USER_POOL_ID (imported "UserPool")
   :COGNITO_CLIENT_ID (imported "SanlangUserPoolClient")
   :AUTH_DOMAIN (imported "UserPoolDomain")
   :USAGE_TABLE (ref :UsageTable)
   :ALLOWED_SOURCES (ref :AllowedSources)
   :ALLOWED_ORIGINS (ref :WebOrigin)
   :MAX_INPUT_CHARACTERS (ref :MaxInputCharacters)
   :MONTHLY_CHARACTER_LIMIT (ref :MonthlyCharacterLimit)})

(def routes
  [[:TranslatePost :TranslateResource "POST" :TranslateFunction true]
   [:TranslateOptions :TranslateResource "OPTIONS" :ApiFunction false]
   [:ConfigGet :ConfigResource "GET" :ApiFunction false]
   [:ConfigOptions :ConfigResource "OPTIONS" :ApiFunction false]
   [:MeGet :MeResource "GET" :ApiFunction false]
   [:MeOptions :MeResource "OPTIONS" :ApiFunction false]])

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
                         method "/v1/" ({:TranslateResource "translate" :ConfigResource "config" :MeResource "me"} resource)))}})

(defn- path-resource [parent path]
  {:Type "AWS::ApiGateway::Resource"
   :Properties {:RestApiId (ref :RestApi) :ParentId parent :PathPart path}})

(defn- api-resources []
  (merge
   {:RestApi {:Type "AWS::ApiGateway::RestApi"
              :Properties {:Name (named "rest")
                           :EndpointConfiguration {:Types ["REGIONAL"]}
                           :DisableExecuteApiEndpoint true}}
    :V1Resource (path-resource (get-att :RestApi :RootResourceId) "v1")
    :TranslateResource (path-resource (ref :V1Resource) "translate")
    :ConfigResource (path-resource (ref :V1Resource) "config")
    :MeResource (path-resource (ref :V1Resource) "me")}
   (into {} (for [[id :as route] routes] [id (method-resource route)]))))

(defn deployment-id [resources]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (json/generate-string resources) "UTF-8"))]
    (keyword (str "Deployment" (subs (format "%064x" (BigInteger. 1 digest)) 0 16)))))

(defn cfn [_param]
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
        :MaxInputCharacters {:Type "Number" :Default 10000 :MinValue 1}
        :MonthlyCharacterLimit {:Type "Number" :Default 100000 :MinValue 1}})
      :Conditions {:HasCodeVersion {"Fn::Not" [{"Fn::Equals" [(ref :ApiCodeVersion) ""]}]}}
      :Resources
      (merge
       api
       {:UsageTable {:Type "AWS::DynamoDB::Table"
                     :DeletionPolicy "Retain" :UpdateReplacePolicy "Retain"
                     :Properties {:TableName (named "usage") :BillingMode "PAY_PER_REQUEST"
                                  :AttributeDefinitions [{:AttributeName "userId" :AttributeType "S"}
                                                         {:AttributeName "period" :AttributeType "S"}]
                                  :KeySchema [{:AttributeName "userId" :KeyType "HASH"}
                                              {:AttributeName "period" :KeyType "RANGE"}]
                                  :DeletionProtectionEnabled true
                                  :PointInTimeRecoverySpecification {:PointInTimeRecoveryEnabled true}}}
        :TranslateLogGroup (log-group "translate")
        :ApiLogGroup (log-group "api")
        :TranslateRole (role :TranslateLogGroup "dynamodb:UpdateItem")
        :ApiRole (role :ApiLogGroup "dynamodb:GetItem")
        :TranslateFunction {:Type "AWS::Lambda::Function"
                            :Properties {:FunctionName (named "translate") :PackageType "Image"
                                         :Code {:ImageUri {"Fn::Join" [":" [(imported "SanlangRepositoryUri") (ref :ImageTag)]]}} :Architectures ["arm64"]
                                         :Role (get-att :TranslateRole :Arn) :MemorySize 10240 :Timeout 300
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
                                   :Environment {:Variables (common-environment)}}}
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
       (into {} (for [[id :as route] routes] [(keyword (str (name id) "Permission")) (invoke-permission route)])))
      :Outputs
      (a.cfn/list-outputs
       {:SanlangApiUrl (sub "https://${Env}-sanlang-api.sancode.dev")
        :SanlangApiDomainTarget (get-att :ApiDomain :RegionalDomainName)
        :SanlangUsageTable (ref :UsageTable)
        :SanlangTranslateFunction (ref :TranslateFunction)
        :SanlangApiFunction (ref :ApiFunction)})})))

(defn- write-template [file value]
  (fs/create-dirs (fs/parent file))
  (with-open [writer (io/writer file)] (json/generate-stream value writer))
  (println (str (fs/absolutize file))))

(defn template [param]
  (doseq [[file value] [["sanlang.json" (cfn param)]
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

(defn deploy [param]
  (let [required {:ImageTag (or (:image-tag param) (System/getenv "SANLANG_IMAGE_TAG"))
                  :ApiCodeBucket (or (:api-code-bucket param) (System/getenv "SANLANG_API_CODE_BUCKET"))
                  :ApiCodeKey (or (:api-code-key param) (System/getenv "SANLANG_API_CODE_KEY"))
                  :CertificateArn (or (:certificate-arn param) (System/getenv "SANLANG_CERTIFICATE_ARN"))}]
    (doseq [[key value] required]
      (when (str/blank? value)
        (throw (ex-info (str "Required Sanlang deployment parameter: " (name key)) {:parameter key}))))
    (deploy-stack param "sanlang" (cfn param)
                  (merge required
                         {:WebOrigin (:sanlang-web-origin param)
                          :ApiCodeVersion (or (:api-code-version param) "")
                          :AllowedSources (or (:allowed-sources param) "cloudflare")
                          :MaxInputCharacters (or (:max-input-characters param) "10000")
                          :MonthlyCharacterLimit (or (:monthly-character-limit param) "100000")}))))
