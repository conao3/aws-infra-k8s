(ns conao3.aws-infra-k8s.network
  (:require
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [cheshire.core :as json]
   [conao3.aws-infra :as a]
   [conao3.aws-infra.cfn :as a.cfn]
   [conao3.aws-infra-k8s.util :as c.util]))

(defn resource-vpc [param]
  {:Type "AWS::EC2::VPC"
   :Properties
   (a.cfn/tag-name
    {:TagName (a.cfn/prefix "vpc")
     :CidrBlock (-> param :vpc)
     :EnableDnsSupport true
     :EnableDnsHostnames true})})

(defn resources-subnet [param]
  (let [subnet (fn [m]
                 {:Type "AWS::EC2::Subnet"
                  :Properties
                  (-> m (assoc :VpcId {:Ref "Vpc"}))})]
    {:SubnetPubA
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pub-a")
        :CidrBlock (-> param :subnet-pub-a)
        :AvailabilityZone "ap-northeast-1a"}))

     :SubnetPriA
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pri-a")
        :CidrBlock (-> param :subnet-pri-a)
        :AvailabilityZone "ap-northeast-1a"}))

     :SubnetPubC
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pub-c")
        :CidrBlock (-> param :subnet-pub-c)
        :AvailabilityZone "ap-northeast-1c"}))

     :SubnetPriC
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pri-c")
        :CidrBlock (-> param :subnet-pri-c)
        :AvailabilityZone "ap-northeast-1c"}))}))

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    {:Env {:Type "String"}
     :Prefix {:Type "String"}}
    :Resources
    (merge
     {:Vpc (resource-vpc param)}
     (resources-subnet param))}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/network.json")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/eshell "sam"
                   "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" (str (-> param :prefix) "-" "network")
                   "--capabilities" "CAPABILITY_NAMED_IAM"
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (format "Env=\"%s\" Prefix=\"%s\""
                           (-> param :env)
                           (-> param :prefix)))))
