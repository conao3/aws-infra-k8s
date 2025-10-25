(ns conao3.aws-infra-k8s
  (:require
   [conao3.aws-infra-k8s.network :as c.network]
   [conao3.aws-infra-k8s.routing :as c.routing])
  (:gen-class))

(defn -main [& args]
  (let [env "dev"
        prefix (format "%s-%s" env "k8s")
        [command & rest] args]
    (case command
      "deploy" (let [target (first rest)]
                 (case target
                   "network" (c.network/deploy {:env env
                                                :prefix prefix
                                                :vpc "10.0.0.0/16"
                                                :subnet-pub-a "10.0.0.0/24"
                                                :subnet-pri-a "10.0.10.0/24"
                                                :subnet-pub-c "10.0.20.0/24"
                                                :subnet-pri-c "10.0.30.0/24"})
                   "routing" (c.routing/deploy {:env env
                                                :prefix prefix})
                   "all" (do
                           (-main "deploy" "network")
                           (-main "deploy" "routing")))))))
