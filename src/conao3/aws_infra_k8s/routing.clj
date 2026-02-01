(ns conao3.aws-infra-k8s.routing
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]
   [camel-snake-kebab.core :as csk]))

(defn resources-pub-subnet-association [suffix]
  (let [key (keyword (format "SubnetRouteTableAssociationPub%s" suffix))
        subnet-key (keyword (format "SubnetPub%s" suffix))]
    {key
     {:Type "AWS::EC2::SubnetRouteTableAssociation"
      :Properties
      {:RouteTableId {:Ref :RouteTablePub}
       :SubnetId {:Ref subnet-key}}}}))

(defn resources-pri-route-table [suffix]
  (let [route-table-key (keyword (format "RouteTablePri%s" suffix))
        association-key (keyword (format "SubnetRouteTableAssociationPri%s" suffix))
        route-ipv4-key (keyword (format "RoutePri%sIpv4NatGateway" suffix))
        route-ipv6-key (keyword (format "RoutePri%sIpv6EgressOnly" suffix))
        subnet-key (keyword (format "SubnetPri%s" suffix))]
    {route-table-key
     {:Type "AWS::EC2::RouteTable"
      :Properties
      (a.cfn/tag-name
       {:VpcId {:Ref :Vpc}
        :TagName (a.cfn/prefix (str "pri-" (csk/->kebab-case suffix)))})}

     association-key
     {:Type "AWS::EC2::SubnetRouteTableAssociation"
      :Properties
      {:RouteTableId {:Ref route-table-key}
       :SubnetId {:Ref subnet-key}}}

     route-ipv4-key
     {:Type "AWS::EC2::Route"
      :Properties
      {:DestinationCidrBlock "0.0.0.0/0"
       :RouteTableId {:Ref route-table-key}
       :NatGatewayId {:Ref :NatGateway}}}

     route-ipv6-key
     {:Type "AWS::EC2::Route"
      :Properties
      {:DestinationIpv6CidrBlock "::/0"
       :RouteTableId {:Ref route-table-key}
       :EgressOnlyInternetGatewayId {:Ref :EgressOnlyInternetGateway}}}}))

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :Vpc
      :SubnetPubA :SubnetPriA
      :SubnetPubC :SubnetPriC
      :SubnetPubD :SubnetPriD])

    :Resources
    (merge
     {:InternetGateway
      {:Type "AWS::EC2::InternetGateway"
       :Properties
       (a.cfn/tag-name
        {:TagName (a.cfn/prefix "igw")})}

      :AttachGateway
      {:Type "AWS::EC2::VPCGatewayAttachment"
       :Properties
       {:InternetGatewayId {:Ref :InternetGateway}
        :VpcId {:Ref :Vpc}}}

      :EgressOnlyInternetGateway
      {:Type "AWS::EC2::EgressOnlyInternetGateway"
       :Properties
       (a.cfn/tag-name
        {:TagName (a.cfn/prefix "eigw")
         :VpcId {:Ref :Vpc}})}

      :NatGatewayEip
      {:Type "AWS::EC2::EIP"
       :DependsOn :AttachGateway
       :Properties
       {:Domain "vpc"
        :Tags [{:Key "Name" :Value (a.cfn/prefix "nat-gw-eip")}]}}

      :NatGateway
      {:Type "AWS::EC2::NatGateway"
       :Properties
       (a.cfn/tag-name
        {:TagName (a.cfn/prefix "nat-gw")
         :AllocationId {"Fn::GetAtt" [:NatGatewayEip :AllocationId]}
         :SubnetId {:Ref :SubnetPubA}})}

      :RouteTablePub
      {:Type "AWS::EC2::RouteTable"
       :Properties
       (a.cfn/tag-name
        {:VpcId {:Ref :Vpc}
         :TagName (a.cfn/prefix "pub")})}

      :RoutePubIpv4AttachInternetGateway
      {:Type "AWS::EC2::Route"
       :DependsOn :AttachGateway
       :Properties
       {:DestinationCidrBlock "0.0.0.0/0"
        :RouteTableId {:Ref :RouteTablePub}
        :GatewayId {:Ref :InternetGateway}}}

      :RoutePubIpv6AttachInternetGateway
      {:Type "AWS::EC2::Route"
       :DependsOn :AttachGateway
       :Properties
       {:DestinationIpv6CidrBlock "::/0"
        :RouteTableId {:Ref :RouteTablePub}
        :GatewayId {:Ref :InternetGateway}}}}

     (resources-pub-subnet-association "A")
     (resources-pub-subnet-association "C")
     (resources-pub-subnet-association "D")
     (resources-pri-route-table "A")
     (resources-pri-route-table "C")
     (resources-pri-route-table "D"))

    :Outputs
    (a.cfn/list-outputs
     {:InternetGateway {:Ref :InternetGateway}
      :EgressOnlyInternetGateway {:Ref :EgressOnlyInternetGateway}
      :NatGateway {:Ref :NatGateway}
      :RouteTablePub {:Ref :RouteTablePub}
      :RouteTablePriA {:Ref :RouteTablePriA}
      :RouteTablePriC {:Ref :RouteTablePriC}
      :RouteTablePriD {:Ref :RouteTablePriD}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/routing.json")
        stack-name (str (-> param :prefix) "-" "routing")]
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
                     (->> (merge
                           {:Env (-> param :env)
                            :Prefix (-> param :prefix)}
                           (->> [:Vpc :SubnetPubA :SubnetPriA :SubnetPubC :SubnetPriC :SubnetPubD :SubnetPriD]
                                (map (fn [k]
                                       [k (get exports (keyword (format "%s-%s" (-> param :prefix) (name k))))]))
                                (into {})))
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
