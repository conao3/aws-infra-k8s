(ns conao3.aws-infra-k8s
  (:require
   [conao3.aws-infra-k8s.network :as c.network]
   [conao3.aws-infra-k8s.routing :as c.routing]
   [conao3.aws-infra-k8s.security-group :as c.security-group]
   [conao3.aws-infra-k8s.cluster :as c.cluster]
   [conao3.aws-infra-k8s.ssh-tunnel :as c.ssh-tunnel]
   [conao3.aws-infra-k8s.eice :as c.eice]
   [conao3.aws-infra-k8s.rds :as c.rds]
   [conao3.aws-infra-k8s.cognito :as c.cognito])
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
  (let [[command & rest] args
        parsed-args (parse-args rest)
        param (merge param parsed-args)]
    (case command
      "deploy" (let [target (first rest)]
                 (case target
                   "network" (c.network/deploy (merge
                                                param
                                                {:vpc "10.0.0.0/16"}))
                   "routing" (c.routing/deploy param)
                   "security-group" (c.security-group/deploy param)
                   "cluster" (c.cluster/deploy param)
                   "ssh-tunnel" (c.ssh-tunnel/deploy param)
                   "eice" (c.eice/deploy param)
                   "rds" (c.rds/deploy param)
                   "cognito" (c.cognito/deploy param)
                   "all" (do
                           (run ["deploy" "network"] param)
                           (run ["deploy" "routing"] param)
                           (run ["deploy" "security-group"] param)
                           (run ["deploy" "rds"] param)
                           (run ["deploy" "cognito"] param)
                           (run ["deploy" "cluster"] param)
                           (run ["deploy" "ssh-tunnel"] param)
                           (run ["deploy" "eice"] param)))))))

(defn -main [& args]
  (let [env "dev"
        prefix (format "%s-%s" env "k8s")
        param {:env env :prefix prefix}]
    (run args param)
    (shutdown-agents)))
