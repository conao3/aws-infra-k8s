(ns conao3.aws-infra-k8s.budget
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
     [:Env :Prefix :EmailAddress])

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
         [{:SubscriptionType "EMAIL"
           :Address {:Ref :EmailAddress}}]}
        {:Notification
         {:NotificationType "ACTUAL"
          :ComparisonOperator "GREATER_THAN"
          :Threshold 100}
         :Subscribers
         [{:SubscriptionType "EMAIL"
           :Address {:Ref :EmailAddress}}]}
        {:Notification
         {:NotificationType "FORECASTED"
          :ComparisonOperator "GREATER_THAN"
          :Threshold 100}
         :Subscribers
         [{:SubscriptionType "EMAIL"
           :Address {:Ref :EmailAddress}}]}]}}}

    :Outputs
    (a.cfn/list-outputs
     {:MainBudget {:Ref :MainBudget}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/budget.json")
        stack-name (str (-> param :prefix) "-" "budget")
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
                   "--region" "us-east-1"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)
                         :EmailAddress (get param :email-address "conao3@sancode.dev")}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
