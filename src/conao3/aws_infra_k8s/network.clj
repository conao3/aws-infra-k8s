(ns conao3.aws-infra-k8s.network
  (:require
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   [cheshire.core :as json]
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

(defn resource-ipv6-cidr-block []
  {:Type "AWS::EC2::VPCCidrBlock"
   :Properties
   {:VpcId {:Ref :Vpc}
    :AmazonProvidedIpv6CidrBlock true}})

(defn resources-subnet []
  (let [subnet (fn [m ipv6-index]
                 {:Type "AWS::EC2::Subnet"
                  :Properties
                  (-> m
                      (assoc :VpcId {:Ref :Vpc})
                      (assoc :Ipv6CidrBlock
                             {"Fn::Select" [ipv6-index
                                            {"Fn::Cidr" [{"Fn::Select" [0 {"Fn::GetAtt" [:Vpc :Ipv6CidrBlocks]}]}
                                                         256
                                                         64]}]})
                      (assoc :Ipv6Native true)
                      (assoc :AssignIpv6AddressOnCreation true))
                  :DependsOn [:VpcIpv6CidrBlock]})
        subnet-dual (fn [m ipv4-cidr ipv6-index]
                      {:Type "AWS::EC2::Subnet"
                       :Properties
                       (-> m
                           (assoc :VpcId {:Ref :Vpc})
                           (assoc :CidrBlock ipv4-cidr)
                           (assoc :Ipv6CidrBlock
                                  {"Fn::Select" [ipv6-index
                                                 {"Fn::Cidr" [{"Fn::Select" [0 {"Fn::GetAtt" [:Vpc :Ipv6CidrBlocks]}]}
                                                              256
                                                              64]}]})
                           (assoc :AssignIpv6AddressOnCreation true))
                       :DependsOn [:VpcIpv6CidrBlock]})]
    {:SubnetPubA
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pub-a")
        :AvailabilityZone :ap-northeast-1a})
      0)

     :SubnetPriA
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pri-a")
        :AvailabilityZone :ap-northeast-1a})
      1)

     :SubnetPubC
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pub-c")
        :AvailabilityZone :ap-northeast-1c})
      2)

     :SubnetPriC
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pri-c")
        :AvailabilityZone :ap-northeast-1c})
      3)

     :SubnetPubD
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pub-d")
        :AvailabilityZone :ap-northeast-1d})
      4)

     :SubnetPriD
     (subnet
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "pri-d")
        :AvailabilityZone :ap-northeast-1d})
      5)

     :SubnetDualA
     (subnet-dual
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "dual-a")
        :AvailabilityZone :ap-northeast-1a})
      "10.0.100.0/24"
      6)

     :SubnetDualC
     (subnet-dual
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "dual-c")
        :AvailabilityZone :ap-northeast-1c})
      "10.0.101.0/24"
      7)

     :SubnetDualD
     (subnet-dual
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "dual-d")
        :AvailabilityZone :ap-northeast-1d})
      "10.0.102.0/24"
      8)}))

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters [:Env :Prefix])
    :Resources
    (merge
     {:Vpc (resource-vpc param)
      :VpcIpv6CidrBlock (resource-ipv6-cidr-block)}
     (resources-subnet))
    :Outputs
    (a.cfn/list-outputs
     {:Vpc {:Ref "Vpc"}
      :SubnetPubA {:Ref :SubnetPubA}
      :SubnetPriA {:Ref :SubnetPriA}
      :SubnetPubC {:Ref :SubnetPubC}
      :SubnetPriC {:Ref :SubnetPriC}
      :SubnetPubD {:Ref :SubnetPubD}
      :SubnetPriD {:Ref :SubnetPriD}
      :SubnetDualA {:Ref :SubnetDualA}
      :SubnetDualC {:Ref :SubnetDualC}
      :SubnetDualD {:Ref :SubnetDualD}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/network.json")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/eshell "sam" "deploy"
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
