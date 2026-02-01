(ns conao3.aws-infra-k8s.vm-import
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-vm-import-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName "vmimport"
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "vmie.amazonaws.com"}
       :Action "sts:AssumeRole"
       :Condition
       {:StringEquals
        {"sts:Externalid" "vmimport"}}}]}
    :Policies
    [{:PolicyName "vmimport"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["s3:GetBucketLocation"
          "s3:GetObject"
          "s3:ListBucket"]
         :Resource
         [{"Fn::Sub" ["arn:aws:s3:::${BucketName}" {:BucketName {:Ref :VmImportBucket}}]}
          {"Fn::Sub" ["arn:aws:s3:::${BucketName}/*" {:BucketName {:Ref :VmImportBucket}}]}]}
        {:Effect "Allow"
         :Action
         ["ec2:ModifySnapshotAttribute"
          "ec2:CopySnapshot"
          "ec2:RegisterImage"
          "ec2:Describe*"]
         :Resource "*"}]}}]}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix :VmImportBucket])

    :Resources
    {:VmImportRole (resource-vm-import-role)}

    :Outputs
    (a.cfn/list-outputs
     {:VmImportRole {:Ref :VmImportRole}})}))

(defn get-vm-import-bucket [prefix]
  (-> (c.util/eshell {:out :string}
                     "aws" "cloudformation" "list-exports"
                     "--query" (format "Exports[?Name=='%s-VmImportBucket'].Value" prefix)
                     "--output" "text")
      :out
      str/trim))

(defn deploy [param]
  (let [file (fs/file "target/cfn/vm-import.json")
        stack-name (str (-> param :prefix) "-" "vm-import")
        vm-import-bucket (get-vm-import-bucket (-> param :prefix))]
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
                         :Prefix (-> param :prefix)
                         :VmImportBucket vm-import-bucket}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
