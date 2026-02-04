(ns conao3.aws-infra-root
  (:require
   [clojure.string :as str]
   [conao3.aws-infra-root.budget :as c.budget]
   [conao3.aws-infra-root.sns :as c.sns]
   [conao3.aws-infra-root.chatbot :as c.chatbot])
  (:gen-class))

(defn parse-args [args]
  (loop [remaining args
         result {}]
    (if (empty? remaining)
      result
      (let [[flag value & rest] remaining]
        (if (str/starts-with? (or flag "") "--")
          (recur rest (assoc result (keyword (subs flag 2)) value))
          (recur rest result))))))

(defn run [args param]
  (let [[command & rest-args] args]
    (case command
      "deploy" (let [target (first rest-args)
                     parsed-args (parse-args (rest rest-args))
                     param (merge param parsed-args)]
                 (case target
                   "budget" (c.budget/deploy param)
                   "sns" (c.sns/deploy param)
                   "chatbot" (c.chatbot/deploy param)
                   "all" (do
                           (run ["deploy" "sns"] param)
                           (run ["deploy" "budget"] param)
                           (run ["deploy" "chatbot"] param)))))))

(defn -main [& args]
  (let [env "dev"
        prefix (format "%s-%s" env "root")
        param {:env env :prefix prefix}]
    (run args param)
    (shutdown-agents)))
