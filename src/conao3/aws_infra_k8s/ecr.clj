(ns conao3.aws-infra-k8s.ecr
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-ecr-repository [name]
  {:Type "AWS::ECR::Repository"
   :Properties
   {:RepositoryName (a.cfn/prefix name)
    :ImageScanningConfiguration
    {:ScanOnPush true}
    :ImageTagMutability "MUTABLE"
    :LifecyclePolicy
    {:LifecyclePolicyText
     (json/generate-string
      {:rules
       [{:rulePriority 1
         :description "Keep last 10 images"
         :selection
         {:tagStatus "any"
          :countType "imageCountMoreThan"
          :countNumber 10}
         :action {:type "expire"}}]})}}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (-> [:Env :Prefix]
        a.cfn/list-string-parameters)

    :Resources
    {:EcrAppAdmin (resource-ecr-repository "app-admin")}

    :Outputs
    (a.cfn/list-outputs
     {:EcrAppAdminArn {"Fn::GetAtt" [:EcrAppAdmin :Arn]}
      :EcrAppAdminUri {"Fn::Sub" "${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com/${EcrAppAdmin}"}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/ecr.json")
        stack-name (str (-> param :prefix) "-" "ecr")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--capabilities" "CAPABILITY_NAMED_IAM"
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
