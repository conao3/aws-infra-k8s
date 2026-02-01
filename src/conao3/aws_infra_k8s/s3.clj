(ns conao3.aws-infra-k8s.s3
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-vm-import-bucket []
  {:Type "AWS::S3::Bucket"
   :Properties
   {:BucketName {"Fn::Sub" "${Prefix}-vm-import-${AWS::AccountId}"}
    :VersioningConfiguration
    {:Status "Enabled"}
    :LifecycleConfiguration
    {:Rules
     [{:Id "DeleteOldVersions"
       :Status "Enabled"
       :NoncurrentVersionExpirationInDays 7}
      {:Id "DeleteOldObjects"
       :Status "Enabled"
       :ExpirationInDays 30}]}
    :PublicAccessBlockConfiguration
    {:BlockPublicAcls true
     :BlockPublicPolicy true
     :IgnorePublicAcls true
     :RestrictPublicBuckets true}}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix])

    :Resources
    {:VmImportBucket (resource-vm-import-bucket)}

    :Outputs
    (a.cfn/list-outputs
     {:VmImportBucket {:Ref :VmImportBucket}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/s3.json")
        stack-name (str (-> param :prefix) "-" "s3")]
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
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
