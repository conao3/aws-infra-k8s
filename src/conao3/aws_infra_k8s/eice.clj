(ns conao3.aws-infra-k8s.eice
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
     [:Env :Prefix
      :SubnetPubA :SecurityGroupEice])

    :Resources
    {:InstanceConnectEndpoint
     {:Type "AWS::EC2::InstanceConnectEndpoint"
      :Properties
      {:SecurityGroupIds [{:Ref :SecurityGroupEice}]
       :SubnetId {:Ref :SubnetPubA}}}}

    :Outputs
    (a.cfn/list-outputs
     {:InstanceConnectEndpoint {:Ref :InstanceConnectEndpoint}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/eice.json")
        stack-name (str (-> param :prefix) "-" "eice")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports")
                           :out
                           (json/parse-string keyword))
                       :Exports
                       (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                       (into {}))]
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
                           :Prefix (-> param :prefix)
                           :SubnetPubA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPubA))))
                           :SecurityGroupEice (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupEice))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
