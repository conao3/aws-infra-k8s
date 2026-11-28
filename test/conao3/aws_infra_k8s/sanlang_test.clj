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
(def stripe-param (assoc param :stripe? true :ssm-versions {"stripe-secret-key" 3 "stripe-price-pro" 1
                                                            "stripe-portal-config" 2 "stripe-event-source" 1}))

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
    (is (= [{:Ref :TranslateMemorySize} 300 "Image"] ((juxt :MemorySize :Timeout :PackageType) translate)))
    (is (= {:Type "Number" :Default 10240 :MinValue 128 :MaxValue 10240}
           (get-in template [:Parameters :TranslateMemorySize])))
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
        (is (= #{:COGNITO_USER_POOL_ID :COGNITO_CLIENT_ID :AUTH_DOMAIN :USAGE_TABLE :USERS_TABLE
                 :ALLOWED_SOURCES :ALLOWED_ORIGINS :MAX_INPUT_CHARACTERS
                 :FREE_MONTHLY_CHARACTER_LIMIT :PRO_MONTHLY_CHARACTER_LIMIT}
               (set (remove #{:LLAMA_SERVER :LLAMA_PORT :MODEL_PATH :ADAPTER_PATH :PUBLIC_URL} (keys env)))))
        (is (= {:Ref :WebOrigin} (:ALLOWED_ORIGINS env)))
        (is (= {:Ref :UsersTable} (:USERS_TABLE env)))
        (is (= {:Ref :FreeMonthlyCharacterLimit} (:FREE_MONTHLY_CHARACTER_LIMIT env)))
        (is (= {:Ref :ProMonthlyCharacterLimit} (:PRO_MONTHLY_CHARACTER_LIMIT env)))
        (is (= {"Fn::ImportValue" {"Fn::Sub" "${Prefix}-SanlangUserPoolClient"}} (:COGNITO_CLIENT_ID env)))))
    (is (= {:Ref :WebOrigin} (get-in api [:Environment :Variables :PUBLIC_URL])))
    (is (not (contains? (get-in translate [:Environment :Variables]) :PUBLIC_URL)))
    (is (= {:LLAMA_SERVER "/opt/bin/llama-server" :LLAMA_PORT "8081"
            :MODEL_PATH "/opt/models/model.gguf" :ADAPTER_PATH "/opt/models/adapter.gguf"}
           (select-keys (get-in translate [:Environment :Variables]) [:LLAMA_SERVER :LLAMA_PORT :MODEL_PATH :ADAPTER_PATH])))
    (is (= "cloudflare" (get-in template [:Parameters :AllowedSources :Default])))
    (is (= ["cloudflare" "any"] (get-in template [:Parameters :AllowedSources :AllowedValues])))
    (is (= 10000 (get-in template [:Parameters :MaxInputCharacters :Default])))
    (is (= 100000 (get-in template [:Parameters :FreeMonthlyCharacterLimit :Default])))
    (is (= 2000000 (get-in template [:Parameters :ProMonthlyCharacterLimit :Default])))
    (is (not (contains? (:Parameters template) :MonthlyCharacterLimit)))
    (is (= 30 (:RetentionInDays (properties template :TranslateLogGroup))))
    (is (= 30 (:RetentionInDays (properties template :ApiLogGroup))))))

(defn- statement [actions resource] {:Effect "Allow" :Action actions :Resource {"Fn::GetAtt" [resource :Arn]}})

(deftest least-privilege-and-tables
  (let [template (sanlang/cfn stripe-param)
        usage (properties template :UsageTable)
        users (properties template :UsersTable)]
    (is (= {"Fn::Sub" "${Prefix}-sanlang-users"} (:TableName users)))
    (is (= [{:AttributeName "userId" :KeyType "HASH"} {:AttributeName "period" :KeyType "RANGE"}] (:KeySchema usage)))
    (is (= [{:AttributeName "userId" :KeyType "HASH"}] (:KeySchema users)))
    (is (= [{:AttributeName "userId" :AttributeType "S"}] (:AttributeDefinitions users)))
    (doseq [[id table] [[:UsageTable usage] [:UsersTable users]]]
      (is (= "PAY_PER_REQUEST" (:BillingMode table)))
      (is (= true (:DeletionProtectionEnabled table) (get-in table [:PointInTimeRecoverySpecification :PointInTimeRecoveryEnabled])))
      (is (= ["Retain" "Retain"] ((juxt :DeletionPolicy :UpdateReplacePolicy) (get-in template [:Resources id])))))
    (doseq [[id log statements]
            [[:TranslateRole :TranslateLogGroup [(statement ["dynamodb:UpdateItem"] :UsageTable)
                                                 (statement ["dynamodb:GetItem"] :UsersTable)]]
             [:ApiRole :ApiLogGroup [(statement ["dynamodb:GetItem"] :UsageTable)
                                     (statement ["dynamodb:GetItem" "dynamodb:PutItem" "dynamodb:UpdateItem"] :UsersTable)]]
             [:BillingRole :BillingLogGroup [(statement ["dynamodb:GetItem" "dynamodb:UpdateItem"] :UsersTable)
                                             (statement ["sqs:ReceiveMessage" "sqs:DeleteMessage" "sqs:GetQueueAttributes"] :BillingQueue)]]]]
      (is (= (into [(statement ["logs:CreateLogStream" "logs:PutLogEvents"] log)] statements)
             (get-in (properties template id) [:Policies 0 :PolicyDocument :Statement]))))
    (is (empty? (of-type template "AWS::Lambda::Url")))))

(deftest stripe-billing
  (let [template (sanlang/cfn stripe-param)
        billing (properties template :BillingFunction)
        api-env (get-in (properties template :ApiFunction) [:Environment :Variables])
        queue (properties template :BillingQueue)
        dlq (properties template :BillingDlq)]
    (is (= ["AWS::Serverless::Function" "nodejs24.x" "billing.handler" ["arm64"] 30 256]
           (into [(get-in template [:Resources :BillingFunction :Type])]
                 ((juxt :Runtime :Handler :Architectures :Timeout :MemorySize) billing))))
    (is (= {:Bucket {:Ref :ApiCodeBucket} :Key {:Ref :ApiCodeKey}
            :Version {"Fn::If" ["HasCodeVersion" {:Ref :ApiCodeVersion} {:Ref "AWS::NoValue"}]}}
           (:CodeUri billing)))
    (is (= {:USERS_TABLE {:Ref :UsersTable}
            :BILLING_QUEUE_URL {:Ref :BillingQueue}
            :STRIPE_PRICE_PRO {"Fn::Sub" "{{resolve:ssm:/${Prefix}/sanlang/stripe-price-pro:1}}"}}
           (get-in billing [:Environment :Variables])))
    (is (= {:Schedule "rate(5 minutes)" :Name {"Fn::Sub" "${Prefix}-sanlang-billing-drain"} :Enabled true}
           (select-keys (get-in billing [:Events :Drain :Properties]) [:Schedule :Name :Enabled])))
    (is (= "Schedule" (get-in billing [:Events :Drain :Type])))
    (is (= {:STRIPE_SECRET_KEY {"Fn::Sub" "{{resolve:ssm:/${Prefix}/sanlang/stripe-secret-key:3}}"}
            :STRIPE_PRICE_PRO {"Fn::Sub" "{{resolve:ssm:/${Prefix}/sanlang/stripe-price-pro:1}}"}
            :STRIPE_PORTAL_CONFIG {"Fn::Sub" "{{resolve:ssm:/${Prefix}/sanlang/stripe-portal-config:2}}"}}
           (select-keys api-env [:STRIPE_SECRET_KEY :STRIPE_PRICE_PRO :STRIPE_PORTAL_CONFIG])))
    (is (not-any? #(re-find #"^STRIPE_" (name %)) (keys (get-in (properties template :TranslateFunction) [:Environment :Variables]))))
    (is (= [{"Fn::Sub" "${Prefix}-sanlang-billing"} 180 345600 5 {"Fn::GetAtt" [:BillingDlq :Arn]}]
           [(:QueueName queue) (:VisibilityTimeout queue) (:MessageRetentionPeriod queue)
            (get-in queue [:RedrivePolicy :maxReceiveCount]) (get-in queue [:RedrivePolicy :deadLetterTargetArn])]))
    (is (= [{"Fn::Sub" "${Prefix}-sanlang-billing-dlq"} 1209600] ((juxt :QueueName :MessageRetentionPeriod) dlq)))
    (is (= {:Name {:Ref :StripeEventSourceName} :EventSourceName {:Ref :StripeEventSourceName}}
           (properties template :StripeEventBus)))
    (is (= "aws.partner/stripe.com/not-configured" (get-in template [:Parameters :StripeEventSourceName :Default])))
    (is (= {"Fn::Sub" "${Prefix}-sanlang-stripe"} (:Name (properties template :StripeRule))))
    (is (= {:source [{:prefix "aws.partner/stripe.com"}]
            :detail {:data {:object {:metadata {:app ["sanlang"]}}}}}
           (:EventPattern (properties template :StripeRule))))
    (let [[allow deny] (get-in (properties template :BillingQueuePolicy) [:PolicyDocument :Statement])]
      (is (= {:Service "events.amazonaws.com"} (:Principal allow)))
      (is (= {:ArnEquals {"aws:SourceArn" {"Fn::GetAtt" [:StripeRule :Arn]}}} (:Condition allow)))
      (is (= {:Bool {"aws:SecureTransport" "false"}} (:Condition deny))))
    (is (= "Deny" (get-in (properties template :BillingDlqPolicy) [:PolicyDocument :Statement 0 :Effect])))
    (testing "the billing log group is deleted with the billing resources"
      (is (= {"Fn::Sub" "/aws/lambda/${Prefix}-sanlang-billing"} (:LogGroupName (properties template :BillingLogGroup))))
      (is (not (contains? (get-in template [:Resources :BillingLogGroup]) :DeletionPolicy))))
    (is (= #{:SanlangBillingFunction :SanlangBillingDlqUrl}
           (set (filter #(re-find #"Billing" (name %)) (keys (:Outputs template))))))
    (testing "without the SSM parameters versions are not pinned"
      (is (= {"Fn::Sub" "{{resolve:ssm:/${Prefix}/sanlang/stripe-price-pro}}"}
             (get-in (properties (sanlang/cfn (assoc param :stripe? true)) :BillingFunction)
                     [:Environment :Variables :STRIPE_PRICE_PRO]))))))

(deftest without-stripe
  (let [template (sanlang/cfn param)
        resources (set (keys (:Resources template)))]
    (is (contains? resources :UsersTable))
    (is (not-any? resources [:BillingFunction :BillingRole :BillingLogGroup :BillingQueue :BillingDlq
                             :BillingQueuePolicy :BillingDlqPolicy :StripeEventBus :StripeRule]))
    (is (empty? (of-type template "AWS::SQS::Queue")))
    (is (not (contains? (:Parameters template) :StripeEventSourceName)))
    (is (not-any? #(re-find #"^STRIPE_" (name %)) (keys (get-in (properties template :ApiFunction) [:Environment :Variables]))))
    (is (not-any? #(re-find #"Billing(Function|DlqUrl)" (name %)) (keys (:Outputs template))))))

(deftest rest-streaming-contract
  (let [template (sanlang/cfn param)
        methods (of-type template "AWS::ApiGateway::Method")
        streaming (get-in methods [:TranslatePost :Properties :Integration])
        deployment (first (keys (of-type template "AWS::ApiGateway::Deployment")))]
    (is (= 10 (count methods)))
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
    (is (= 10 (count (of-type template "AWS::Lambda::Permission"))))
    (is (= {"Fn::Sub" "arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${RestApi}/*/POST/v1/billing/checkout"}
           (:SourceArn (properties template :BillingCheckoutPostPermission))))
    (is (= {"Fn::Sub" "arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${RestApi}/*/OPTIONS/v1/billing/portal"}
           (:SourceArn (properties template :BillingPortalOptionsPermission))))
    (is (= {"Fn::Sub" "arn:${AWS::Partition}:execute-api:${AWS::Region}:${AWS::AccountId}:${RestApi}/*/GET/v1/me"}
           (:SourceArn (properties template :MeGetPermission))))
    (is (= {:RestApiId {:Ref :RestApi} :ParentId {:Ref :BillingResource} :PathPart "checkout"}
           (properties template :BillingCheckoutResource)))
    (is (= {:RestApiId {:Ref :RestApi} :ParentId {:Ref :V1Resource} :PathPart "billing"}
           (properties template :BillingResource)))
    (is (= {:RestApiId {:Ref :RestApi} :ParentId {"Fn::GetAtt" [:RestApi :RootResourceId]} :PathPart "v1"}
           (properties template :V1Resource)))
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

(def deploy-param (merge param {:image-tag "release-1" :api-code-bucket "artifacts"
                                :api-code-key "sanlang/release-1.zip" :certificate-arn "arn:example"
                                :translate-memory-size "3008" :cfn-lint "/approved/tools/cfn-lint"}))

(defn- run-deploy [stripe]
  (let [commands (atom [])
        stacks (atom [])
        trimmed (atom [])]
    (with-redefs [util/eshell (fn [& args] (swap! commands conj (vec args)))
                  util/ensure-stack-deployable (fn [stack] (swap! stacks conj stack))
                  util/ensure-trimmed-ssm-values (fn [path] (swap! trimmed conj path))
                  sanlang/ssm-values (fn [prefix] (is (= "dev-k8s" prefix)) stripe)]
      (with-out-str (sanlang/deploy deploy-param)))
    {:commands @commands :stacks @stacks :trimmed @trimmed
     :template (json/parse-string (slurp "target/cfn/sanlang.json") true)}))

(deftest deployment-arguments
  (let [{:keys [commands stacks trimmed template]} (run-deploy nil)]
    (is (= [["/approved/tools/cfn-lint" "--regions" "ap-northeast-1" "--template" "target/cfn/sanlang.json"]]
           (take 1 commands)))
    (is (= ["dev-k8s-sanlang"] stacks))
    (is (= [] trimmed))
    (is (= ["sam" "deploy" "--region" "ap-northeast-1"] (take 4 (second commands))))
    (is (every? (set (second commands)) ["ImageTag=release-1" "ApiCodeBucket=artifacts"
                                         "ApiCodeKey=sanlang/release-1.zip" "CertificateArn=arn:example"
                                         "WebOrigin=https://dev-sanlang.sancode.dev" "AllowedSources=cloudflare"
                                         "TranslateMemorySize=3008" "FreeMonthlyCharacterLimit=100000"
                                         "ProMonthlyCharacterLimit=2000000"]))
    (is (not-any? #(re-find #"^(StripeEventSourceName|MonthlyCharacterLimit)=" %) (second commands)))
    (is (not (contains? (:Resources template) :BillingFunction)))
    (is (= 2 (count commands)))))

(deftest deployment-with-stripe
  (let [stripe {"stripe-secret-key" {:version 3 :value "rk_test"}
                "stripe-price-pro" {:version 1 :value "price_1"}
                "stripe-portal-config" {:version 2 :value "bpc_1"}
                "stripe-event-source" {:version 1 :value "aws.partner/stripe.com/ed_test"}}
        {:keys [commands trimmed template]} (run-deploy stripe)]
    (is (= ["/dev-k8s/sanlang/"] trimmed))
    (is (some #{"StripeEventSourceName=aws.partner/stripe.com/ed_test"} (second commands)))
    (is (contains? (:Resources template) :BillingFunction))
    (is (= {(keyword "Fn::Sub") "{{resolve:ssm:/${Prefix}/sanlang/stripe-secret-key:3}}"}
           (get-in template [:Resources :ApiFunction :Properties :Environment :Variables :STRIPE_SECRET_KEY])))
    (is (= 2 (count commands)))))

(deftest ssm-values-requires-every-parameter
  (let [response (fn [names] {:out (json/generate-string (for [n names] {:name (str "/dev-k8s/sanlang/" n) :version 1 :value "v"}))})
        all (map first sanlang/stripe-parameters)]
    (with-redefs [util/eshell (fn [& args]
                                (is (every? (set args) (map #(str "/dev-k8s/sanlang/" %) all)))
                                (response all))]
      (is (= (set all) (set (keys (sanlang/ssm-values "dev-k8s"))))))
    (with-redefs [util/eshell (fn [& _] (response (rest all)))]
      (is (nil? (sanlang/ssm-values "dev-k8s"))))))

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
