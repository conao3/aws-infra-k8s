(ns conao3.aws-infra-root.chatbot
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
     [:Env :Prefix :SlackWorkspaceId :SlackChannelId :SnsTopicArn :ChatbotRoleArn])

    :Resources
    {:SlackChannelConfig
     {:Type "AWS::Chatbot::SlackChannelConfiguration"
      :Properties
      {:ConfigurationName (a.cfn/prefix "cost-alerts")
       :SlackWorkspaceId {:Ref :SlackWorkspaceId}
       :SlackChannelId {:Ref :SlackChannelId}
       :IamRoleArn {:Ref :ChatbotRoleArn}
       :SnsTopicArns [{:Ref :SnsTopicArn}]
       :LoggingLevel "INFO"}}}

    :Outputs
    (a.cfn/list-outputs
     {:SlackChannelConfig {:Ref :SlackChannelConfig}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn_root/chatbot.json")
        stack-name (str (-> param :prefix) "-" "chatbot")
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
                           :SlackWorkspaceId (get param :slack-workspace-id "")
                           :SlackChannelId (get param :slack-channel-id "")
                           :SnsTopicArn (get exports (keyword (format "%s-%s" (-> param :prefix) "CostAnomalySnsTopicArn")))
                           :ChatbotRoleArn (get exports (keyword (format "%s-%s" (-> param :prefix) "ChatbotRoleArn")))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
