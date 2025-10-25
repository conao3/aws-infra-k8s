(ns conao3.aws-infra-k8s
  (:require
   [conao3.aws-infra-k8s.network :as c.network]
   [conao3.aws-infra-k8s.routing :as c.routing]
   [conao3.aws-infra-k8s.security-group :as c.security-group]
   [conao3.aws-infra-k8s.ssh-tunnel :as c.ssh-tunnel]
   [conao3.aws-infra-k8s.eice :as c.eice])
  (:gen-class))

(defn -main [& args]
  (let [env "dev"
        prefix (format "%s-%s" env "k8s")
        param {:env env :prefix prefix}
        [command & rest] args]
    (case command
      "deploy" (let [target (first rest)]
                 (case target
                   "network" (c.network/deploy (merge
                                                param
                                                {:vpc "10.0.0.0/16"
                                                 :subnet-pub-a "10.0.0.0/24"
                                                 :subnet-pri-a "10.0.10.0/24"
                                                 :subnet-pub-c "10.0.20.0/24"
                                                 :subnet-pri-c "10.0.30.0/24"}))
                   "routing" (c.routing/deploy param)
                   "security-group" (c.security-group/deploy param)
                   "ssh-tunnel" (c.ssh-tunnel/deploy param)
                   "eice" (c.eice/deploy param)
                   "all" (do
                           (-main "deploy" "network")
                           (-main "deploy" "routing")))))))
