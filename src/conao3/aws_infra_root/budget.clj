(ns conao3.aws-infra-root.budget
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
     [:Env :Prefix :SnsTopicArn])

    :Resources
    {:MainBudget
     {:Type "AWS::Budgets::Budget"
      :Properties
      {:Budget
       {:BudgetName (a.cfn/prefix "monthly-budget")
        :BudgetLimit {:Amount 60 :Unit "USD"}
        :TimeUnit "MONTHLY"
        :BudgetType "COST"
        :CostTypes {:IncludeTax true :IncludeSubscription true :UseBlended false}}
       :NotificationsWithSubscribers
       [{:Notification
         {:NotificationType "ACTUAL"
          :ComparisonOperator "GREATER_THAN"
          :Threshold 80}
         :Subscribers
         [{:SubscriptionType "SNS"
           :Address {:Ref :SnsTopicArn}}]}
        {:Notification
         {:NotificationType "ACTUAL"
          :ComparisonOperator "GREATER_THAN"
          :Threshold 100}
         :Subscribers
         [{:SubscriptionType "SNS"
           :Address {:Ref :SnsTopicArn}}]}
        {:Notification
         {:NotificationType "FORECASTED"
          :ComparisonOperator "GREATER_THAN"
          :Threshold 100}
         :Subscribers
         [{:SubscriptionType "SNS"
           :Address {:Ref :SnsTopicArn}}]}]}}

     :CostAnomalyMonitor
     {:Type "AWS::CE::AnomalyMonitor"
      :Properties
      {:MonitorName (a.cfn/prefix "cost-anomaly-monitor")
       :MonitorType "DIMENSIONAL"
       :MonitorDimension "SERVICE"}}

     :CostAnomalySubscription
     {:Type "AWS::CE::AnomalySubscription"
      :Properties
      {:SubscriptionName (a.cfn/prefix "cost-anomaly-subscription")
       :MonitorArnList [{:Ref :CostAnomalyMonitor}]
       :Subscribers
       [{:Type "SNS"
         :Address {:Ref :SnsTopicArn}}]
       :Threshold 100
       :Frequency "IMMEDIATE"}}}

    :Outputs
    (a.cfn/list-outputs
     {:MainBudget {:Ref :MainBudget}
      :CostAnomalyMonitor {:Ref :CostAnomalyMonitor}
      :CostAnomalySubscription {:Ref :CostAnomalySubscription}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn_root/budget.json")
        stack-name (str (-> param :prefix) "-" "budget")
        env (merge (into {} (System/getenv))
                   {"AWS_PROFILE" "conao3.root"})]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell {:env env} "sam" "validate" "--template-file" (str (fs/path file)))
    (let [exports (->> (-> (c.util/eshell {:out :string :env env} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1")
                           :out
                           (json/parse-string keyword))
                       :Exports
                       (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                       (into {}))]
      (c.util/eshell {:env env} "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--region" "us-east-1"
                     "--parameter-overrides"
                     (->> {:Env (-> param :env)
                           :Prefix (-> param :prefix)
                           :SnsTopicArn (get exports (keyword (format "%s-%s" (-> param :prefix) "CostAnomalySnsTopicArn")))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
