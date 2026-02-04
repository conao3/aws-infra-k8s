(ns conao3.aws-infra-k8s.efs
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
      :SubnetPriA
      :SubnetPriC
      :SubnetPriD
      :SecurityGroupEfs])

    :Resources
    {:EfsFileSystem
     {:Type "AWS::EFS::FileSystem"
      :Properties
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "efs")
        :PerformanceMode "generalPurpose"
        :ThroughputMode "bursting"
        :Encrypted true})}

     :EfsMountTargetA
     {:Type "AWS::EFS::MountTarget"
      :Properties
      {:FileSystemId {:Ref :EfsFileSystem}
       :SubnetId {:Ref :SubnetPriA}
       :SecurityGroups [{:Ref :SecurityGroupEfs}]}}

     :EfsMountTargetC
     {:Type "AWS::EFS::MountTarget"
      :Properties
      {:FileSystemId {:Ref :EfsFileSystem}
       :SubnetId {:Ref :SubnetPriC}
       :SecurityGroups [{:Ref :SecurityGroupEfs}]}}

     :EfsMountTargetD
     {:Type "AWS::EFS::MountTarget"
      :Properties
      {:FileSystemId {:Ref :EfsFileSystem}
       :SubnetId {:Ref :SubnetPriD}
       :SecurityGroups [{:Ref :SecurityGroupEfs}]}}}

    :Outputs
    (a.cfn/list-outputs
     {:EfsFileSystemId {:Ref :EfsFileSystem}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/efs.json")
        stack-name (str (-> param :prefix) "-" "efs")]
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
                           :SubnetPriA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPriA))))
                           :SubnetPriC (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPriC))))
                           :SubnetPriD (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPriD))))
                           :SecurityGroupEfs (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupEfs))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
