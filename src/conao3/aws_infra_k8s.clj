(ns conao3.aws-infra-k8s
  (:require
   [clojure.string :as str]
   [conao3.aws-infra-k8s.network :as c.network]
   [conao3.aws-infra-k8s.routing :as c.routing]
   [conao3.aws-infra-k8s.security-group :as c.security-group]
   [conao3.aws-infra-k8s.cluster :as c.cluster]
   [conao3.aws-infra-k8s.alb :as c.alb]
   [conao3.aws-infra-k8s.ssh-tunnel :as c.ssh-tunnel]
   [conao3.aws-infra-k8s.ami-builder :as c.ami-builder]
   [conao3.aws-infra-k8s.eice :as c.eice]
   [conao3.aws-infra-k8s.cognito :as c.cognito]
   [conao3.aws-infra-k8s.s3 :as c.s3]
   [conao3.aws-infra-k8s.rds :as c.rds]
   [conao3.aws-infra-k8s.efs :as c.efs]
   [conao3.aws-infra-k8s.vm-import :as c.vm-import]
   [conao3.aws-infra-k8s.ecr :as c.ecr]
   [conao3.aws-infra-k8s.github-oidc :as c.github-oidc]
   [conao3.aws-infra-k8s.sanplan-resources :as c.sanplan-resources]
   [conao3.aws-infra-k8s.sancode-resources :as c.sancode-resources]
   [conao3.aws-infra-k8s.certificate :as c.certificate])
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
                   ;; network
                   "network" (c.network/deploy
                              (merge param {:vpc "10.0.0.0/16"}))
                   "routing" (c.routing/deploy param)
                   "security-group" (c.security-group/deploy param)

                   ;; other independent stacks
                   "cognito" (c.cognito/deploy param)
                   "s3" (c.s3/deploy param)
                   "vm-import" (c.vm-import/deploy param)
                   "github-oidc" (c.github-oidc/deploy param)
                   "ecr" (c.ecr/deploy param)

                   ;; depends on network
                   "cluster" (c.cluster/deploy param)
                   "alb" (c.alb/deploy param)
                   "ssh-tunnel" (c.ssh-tunnel/deploy param)
                   "ami-builder" (c.ami-builder/deploy param)
                   "eice" (c.eice/deploy param)
                   "rds" (c.rds/deploy param)
                   "efs" (c.efs/deploy param)
                   "sanplan-resources" (c.sanplan-resources/deploy param)
                   "sancode-resources" (c.sancode-resources/deploy param)
                   "certificate" (c.certificate/deploy param)
                   "all" (do
                           (run ["deploy" "network"] param)
                           (run ["deploy" "routing"] param)
                           (run ["deploy" "security-group"] param)
                           (run ["deploy" "cognito"] param)
                           (run ["deploy" "s3"] param)
                           (run ["deploy" "vm-import"] param)
                           (run ["deploy" "github-oidc"] param)
                           (run ["deploy" "ecr"] param)
                           ;; (run ["deploy" "budget"] param)
                           (run ["deploy" "cluster"] param)
                           (run ["deploy" "alb"] param)
                           ;; (run ["deploy" "ssh-tunnel"] param)
                           ;; (run ["deploy" "ami-builder"] param)
                           (run ["deploy" "eice"] param)
                           (run ["deploy" "efs"] param)
                           (run ["deploy" "sanplan-resources"] param)
                           ))))))

(defn -main [& args]
  (let [env (or (System/getenv "DEPLOY_ENV") "dev")
        prefix (format "%s-%s" env "k8s")
        cognito-domain (if (= env "prd") "auth-k8s.sancode.dev" (format "auth-%s.sancode.dev" prefix))
        param {:env env
               :prefix prefix
               :domain-name cognito-domain
               :alb-certificate-domain-name "*.sancode.dev"
               :domain-aliases (str/join "," (map #(format "%s.sancode.dev" %)
                                                  ["app1" "app2" "app3" "admin" "sanplan"]))}]
    (run args param)
    (shutdown-agents)))
