(ns conao3.aws-infra-k8s.sanlang-test
  (:require
   [clojure.test :refer [deftest is run-tests testing]]
   [cheshire.core :as json]
   [conao3.aws-infra-k8s :as cli]
   [conao3.aws-infra-k8s.certificate :as certificate]
   [conao3.aws-infra-k8s.cognito :as cognito]
   [conao3.aws-infra-k8s.sanlang :as sanlang]
   [conao3.aws-infra-k8s.util :as util]))

(def param {:env "dev" :prefix "dev-k8s" :sanlang-web-origin "https://dev-sanlang.sancode.dev"})

(defn- properties [template id] (get-in template [:Resources id :Properties]))
(defn- of-type [template type]
  (into {} (filter (fn [[_ value]] (= type (:Type value))) (:Resources template))))

(deftest offline-generation
  (with-redefs [util/eshell (fn [& _] (throw (ex-info "External commands forbidden" {})))]
    (is (map? (sanlang/cfn param)))
    (is (map? (sanlang/repository-cfn param)))
    (is (map? (certificate/cfn param :SanlangCertificateArn nil)))
    (is (map? (cognito/cfn param)))))

(deftest lambda-contract
  (let [template (sanlang/cfn param)
        translate (properties template :TranslateFunction)
        api (properties template :ApiFunction)]
    (is (= ["arm64"] (:Architectures translate) (:Architectures api)))
    (is (= [10240 300 "Image"] ((juxt :MemorySize :Timeout :PackageType) translate)))
    (is (= [256 29 "Zip" "nodejs24.x" "api.handler"] ((juxt :MemorySize :Timeout :PackageType :Runtime :Handler) api)))
    (is (= {"Fn::Join" [":" [{"Fn::ImportValue" {"Fn::Sub" "${Prefix}-SanlangRepositoryUri"}} {:Ref :ImageTag}]]}
           (get-in translate [:Code :ImageUri])))
    (is (= {:Ref :ApiCodeBucket} (get-in api [:Code :S3Bucket])))
    (is (= {:Ref :ApiCodeKey} (get-in api [:Code :S3Key])))
    (is (= {"Fn::If" ["HasCodeVersion" {:Ref :ApiCodeVersion} {:Ref "AWS::NoValue"}]}
           (get-in api [:Code :S3ObjectVersion])))
    (is (= "" (get-in template [:Parameters :ApiCodeVersion :Default])))
    (is (not (contains? translate :ReservedConcurrentExecutions)))
    (doseq [function [translate api]]
      (let [env (get-in function [:Environment :Variables])]
        (is (= #{:COGNITO_USER_POOL_ID :COGNITO_CLIENT_ID :AUTH_DOMAIN :USAGE_TABLE
                 :ALLOWED_SOURCES :ALLOWED_ORIGINS :MAX_INPUT_CHARACTERS :MONTHLY_CHARACTER_LIMIT}
               (set (remove #{:LLAMA_SERVER :LLAMA_PORT :MODEL_PATH :ADAPTER_PATH} (keys env)))))
        (is (= {:Ref :WebOrigin} (:ALLOWED_ORIGINS env)))
        (is (= {"Fn::ImportValue" {"Fn::Sub" "${Prefix}-SanlangUserPoolClient"}} (:COGNITO_CLIENT_ID env)))))
    (is (= {:LLAMA_SERVER "/opt/bin/llama-server" :LLAMA_PORT "8081"
            :MODEL_PATH "/opt/models/model.gguf" :ADAPTER_PATH "/opt/models/adapter.gguf"}
           (select-keys (get-in translate [:Environment :Variables]) [:LLAMA_SERVER :LLAMA_PORT :MODEL_PATH :ADAPTER_PATH])))
    (is (= "cloudflare" (get-in template [:Parameters :AllowedSources :Default])))
    (is (= ["cloudflare" "any"] (get-in template [:Parameters :AllowedSources :AllowedValues])))
    (is (= 10000 (get-in template [:Parameters :MaxInputCharacters :Default])))
    (is (= 100000 (get-in template [:Parameters :MonthlyCharacterLimit :Default])))
    (is (= 30 (:RetentionInDays (properties template :TranslateLogGroup))))
    (is (= 30 (:RetentionInDays (properties template :ApiLogGroup))))))

(deftest least-privilege-and-usage
  (let [template (sanlang/cfn param)
        table (properties template :UsageTable)]
    (is (= "PAY_PER_REQUEST" (:BillingMode table)))
    (is (= [{:AttributeName "userId" :KeyType "HASH"} {:AttributeName "period" :KeyType "RANGE"}] (:KeySchema table)))
    (is (= true (:DeletionProtectionEnabled table) (get-in table [:PointInTimeRecoverySpecification :PointInTimeRecoveryEnabled])))
    (is (= "Retain" (get-in template [:Resources :UsageTable :DeletionPolicy])))
    (doseq [[id action log] [[:TranslateRole "dynamodb:UpdateItem" :TranslateLogGroup]
                             [:ApiRole "dynamodb:GetItem" :ApiLogGroup]]]
      (is (= [{:Effect "Allow" :Action ["logs:CreateLogStream" "logs:PutLogEvents"] :Resource {"Fn::GetAtt" [log :Arn]}}
              {:Effect "Allow" :Action [action] :Resource {"Fn::GetAtt" [:UsageTable :Arn]}}]
             (get-in (properties template id) [:Policies 0 :PolicyDocument :Statement]))))
    (is (empty? (of-type template "AWS::Lambda::Url")))
    (is (empty? (of-type template "AWS::SQS::Queue")))))

(deftest rest-streaming-contract
  (let [template (sanlang/cfn param)
        methods (of-type template "AWS::ApiGateway::Method")
        streaming (get-in methods [:TranslatePost :Properties :Integration])
        deployment (first (keys (of-type template "AWS::ApiGateway::Deployment")))]
    (is (= 6 (count methods)))
    (is (= ["REGIONAL"] (get-in (properties template :RestApi) [:EndpointConfiguration :Types])))
    (is (true? (:DisableExecuteApiEndpoint (properties template :RestApi))))
    (is (= "STREAM" (:ResponseTransferMode streaming)))
    (is (= 300000 (:TimeoutInMillis streaming)))
    (is (= "arn:${AWS::Partition}:apigateway:${AWS::Region}:lambda:path/2021-11-15/functions/${TranslateFunction.Arn}/response-streaming-invocations"
           (get-in streaming [:Uri "Fn::Sub"])))
    (doseq [[id {:keys [Properties]}] methods]
      (is (= "NONE" (:AuthorizationType Properties)))
      (is (= "AWS_PROXY" (get-in Properties [:Integration :Type])))
      (is (= "POST" (get-in Properties [:Integration :IntegrationHttpMethod])))
      (when (not= id :TranslatePost)
        (is (= "BUFFERED" (get-in Properties [:Integration :ResponseTransferMode])))
        (is (= 29000 (get-in Properties [:Integration :TimeoutInMillis])))
        (is (re-find #"2015-03-31/functions/\$\{ApiFunction.Arn\}/invocations$" (get-in Properties [:Integration :Uri "Fn::Sub"])))))
    (is (= 6 (count (of-type template "AWS::Lambda::Permission"))))
    (is (= (set (map (comp name first) sanlang/routes))
           (set (get-in template [:Resources deployment :DependsOn]))))
    (is (= "${Env}-sanlang-api.sancode.dev" (get-in (properties template :ApiDomain) [:DomainName "Fn::Sub"])))
    (is (= {:Ref :CertificateArn} (:RegionalCertificateArn (properties template :ApiDomain))))
    (is (= false (get-in (properties template :Stage) [:MethodSettings 0 :DataTraceEnabled])))
    (testing "changes to routes force a new API deployment snapshot"
      (is (not= (sanlang/deployment-id methods)
                (sanlang/deployment-id (assoc-in methods [:TranslatePost :Properties :Integration :TimeoutInMillis] 200000)))))))

(deftest repository-contract
  (let [template (sanlang/repository-cfn param)
        repository (properties template :Repository)
        lifecycle (json/parse-string (get-in repository [:LifecyclePolicy :LifecyclePolicyText]) true)]
    (is (= {"Fn::Sub" "${Prefix}-sanlang-translate"} (:RepositoryName repository)))
    (is (true? (get-in repository [:ImageScanningConfiguration :ScanOnPush])))
    (is (= "IMMUTABLE" (:ImageTagMutability repository)))
    (is (= 10 (get-in lifecycle [:rules 0 :selection :countNumber])))
    (is (= "Retain" (get-in template [:Resources :Repository :DeletionPolicy])))
    (is (= ["ecr:BatchGetImage" "ecr:GetDownloadUrlForLayer"]
           (get-in repository [:RepositoryPolicyText :Statement 0 :Action])))))

(deftest public-cognito-client
  (let [template (cognito/cfn param)
        client (properties template :UserPoolClientSanlang)]
    (is (= {:Ref :UserPool} (:UserPoolId client)))
    (is (false? (:GenerateSecret client)))
    (is (= ["code"] (:AllowedOAuthFlows client)))
    (is (= ["email" "openid" "profile"] (:AllowedOAuthScopes client)))
    (is (= (:CallbackURLs client) (:LogoutURLs client)))
    (is (= "${SanlangWebOrigin}/" (get-in client [:CallbackURLs "Fn::If" 1 0 "Fn::Sub"])))
    (is (true? (:EnableTokenRevocation client)))
    (is (= {:Ref :UserPoolClientSanlang} (get-in template [:Outputs :SanlangUserPoolClient :Value])))
    (is (= "" (get-in template [:Parameters :SanlangWebOrigin :Default])))))

(deftest cli-dispatch-and-opt-in
  (let [calls (atom [])]
    (with-redefs [sanlang/template (fn [_] (swap! calls conj :template))
                  sanlang/deploy (fn [_] (swap! calls conj :backend))
                  sanlang/deploy-repository (fn [_] (swap! calls conj :repository))
                  certificate/deploy-sanlang (fn [_] (swap! calls conj :certificate))]
      (doseq [args [["template" "sanlang"] ["deploy" "sanlang"] ["deploy" "sanlang-repository"] ["deploy" "sanlang-certificate"]]]
        (cli/run args param)))
    (is (= [:template :backend :repository :certificate] @calls)))
  (let [run cli/run
        calls (atom [])]
    (with-redefs [cli/run (fn [args _] (swap! calls conj args))]
      (run ["deploy" "all"] param))
    (is (not-any? #(re-find #"^sanlang" (second %)) @calls))))

(deftest deployment-arguments
  (let [commands (atom [])
        stacks (atom [])]
    (with-redefs [util/eshell (fn [& args] (swap! commands conj (vec args)))
                  util/ensure-stack-deployable (fn [stack] (swap! stacks conj stack))]
      (with-out-str
        (sanlang/deploy (merge param {:image-tag "release-1" :api-code-bucket "artifacts"
                                      :api-code-key "sanlang/release-1.zip" :certificate-arn "arn:example"
                                      :cfn-lint "/approved/tools/cfn-lint"}))))
    (is (= [["/approved/tools/cfn-lint" "--regions" "ap-northeast-1" "--template" "target/cfn/sanlang.json"]]
           (take 1 @commands)))
    (is (= ["dev-k8s-sanlang"] @stacks))
    (is (= ["sam" "deploy" "--region" "ap-northeast-1"] (take 4 (second @commands))))
    (is (every? (set (second @commands)) ["ImageTag=release-1" "ApiCodeBucket=artifacts"
                                          "ApiCodeKey=sanlang/release-1.zip" "CertificateArn=arn:example"
                                          "WebOrigin=https://dev-sanlang.sancode.dev" "AllowedSources=cloudflare"]))
    (is (= 2 (count @commands)))))

(deftest deployment-requires-artifacts-before-any-command
  (let [required {:image-tag "release-1" :api-code-bucket "artifacts" :api-code-key "sanlang/release-1.zip" :certificate-arn "arn:example"}]
    (with-redefs [util/eshell (fn [& _] (throw (ex-info "External commands forbidden" {})))]
      (doseq [key (keys required)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required Sanlang deployment parameter"
                              (sanlang/deploy (merge param (assoc required key "")))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'conao3.aws-infra-k8s.sanlang-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
