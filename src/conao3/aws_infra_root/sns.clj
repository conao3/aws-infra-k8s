(ns conao3.aws-infra-root.sns
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix :AlertEmail])

    :Resources
    {:CostAnomalySns
     {:Type "AWS::SNS::Topic"
      :Properties
      {:TopicName (a.cfn/prefix "cost-anomaly-alerts")
       :DisplayName "Cost Anomaly Alerts"}}

     :CostAnomalySnsPolicy
     {:Type "AWS::SNS::TopicPolicy"
      :Properties
      {:Topics [{:Ref :CostAnomalySns}]
       :PolicyDocument
       {:Version "2012-10-17"
        :Statement
        [{:Sid "AWSBudgetsSNSPublishingPermissions"
          :Effect "Allow"
          :Principal {:Service "budgets.amazonaws.com"}
          :Action "SNS:Publish"
          :Resource {:Ref :CostAnomalySns}
          :Condition
          {:StringEquals {"aws:SourceAccount" {:Ref "AWS::AccountId"}}
           :ArnLike {"aws:SourceArn" {"Fn::Sub" "arn:aws:budgets::${AWS::AccountId}:*"}}}}
         {:Sid "AWSAnomalyDetectionSNSPublishingPermissions"
          :Effect "Allow"
          :Principal {:Service "costalerts.amazonaws.com"}
          :Action "SNS:Publish"
          :Resource {:Ref :CostAnomalySns}
          :Condition
          {:StringEquals {"aws:SourceAccount" {:Ref "AWS::AccountId"}}}}]}}}

     :CostAnomalySnsEmail
     {:Type "AWS::SNS::Subscription"
      :Properties
      {:TopicArn {:Ref :CostAnomalySns}
       :Protocol "email"
       :Endpoint {:Ref :AlertEmail}}}

     :ChatbotRole
     {:Type "AWS::IAM::Role"
      :Properties
      {:RoleName (a.cfn/prefix "chatbot-role")
       :AssumeRolePolicyDocument
       {:Version "2012-10-17"
        :Statement
        [{:Effect "Allow"
          :Principal {:Service "chatbot.amazonaws.com"}
          :Action "sts:AssumeRole"}]}
       :ManagedPolicyArns
       ["arn:aws:iam::aws:policy/CloudWatchReadOnlyAccess"
        "arn:aws:iam::aws:policy/ReadOnlyAccess"]}}}

    :Outputs
    (a.cfn/list-outputs
     {:CostAnomalySnsTopicArn {"Fn::GetAtt" [:CostAnomalySns :TopicArn]}
      :ChatbotRoleArn {"Fn::GetAtt" [:ChatbotRole :Arn]}})}))

(defn- alert-email []
  (let [value (System/getenv "ROOT_ALERT_EMAIL")]
    (when (str/blank? value)
      (throw (ex-info "set ROOT_ALERT_EMAIL to the address that receives the cost alerts" {})))
    value))

(defn deploy [param]
  (let [file (fs/file "target/cfn_root/sns.json")
        stack-name (str (-> param :prefix) "-" "sns")
        env (merge (into {} (System/getenv))
                   {"AWS_PROFILE" "conao3.root"})]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell {:env env} "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/eshell {:env env} "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--capabilities" "CAPABILITY_NAMED_IAM"
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--region" "ap-northeast-1"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)
                         :AlertEmail (alert-email)}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
