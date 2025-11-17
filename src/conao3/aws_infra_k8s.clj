(ns conao3.aws-infra-k8s
  (:require
   [conao3.aws-infra-k8s.network :as c.network]
   [conao3.aws-infra-k8s.routing :as c.routing]
   [conao3.aws-infra-k8s.security-group :as c.security-group]
   [conao3.aws-infra-k8s.cluster :as c.cluster]
   [conao3.aws-infra-k8s.ssh-tunnel :as c.ssh-tunnel]
   [conao3.aws-infra-k8s.eice :as c.eice])
  (:gen-class))

(defn run [args param]
  (let [[command & rest] args]
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
                   "all" (do
                           (run ["deploy" "network"] param)
                           (run ["deploy" "routing"] param)
                           (run ["deploy" "security-group"] param)
                           (run ["deploy" "cluster"] param)
                           (run ["deploy" "ssh-tunnel"] param)
                           (run ["deploy" "eice"] param)))))))

(defn -main [& args]
  (let [env "dev"
        prefix (format "%s-%s" env "k8s")
        param {:env env :prefix prefix}]
    (run args param)
    (shutdown-agents)))
