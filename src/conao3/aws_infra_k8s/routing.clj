(ns conao3.aws-infra-k8s.routing
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn cfn [param]
  (conao3.aws-infra.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :Vpc
      :SubnetPubA :SubnetPriA
      :SubnetPubC :SubnetPriC])
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
        :VpcId {:Ref :Vpc}}}}

     {:RouteTablePub
      {:Type "AWS::EC2::RouteTable"
       :Properties
       (a.cfn/tag-name
        {:VpcId {:Ref :Vpc}
         :TagName (a.cfn/prefix "pub")})}

      :SubnetRouteTableAssociationPubA
      {:Type "AWS::EC2::SubnetRouteTableAssociation"
       :Properties
       {:RouteTableId {:Ref :RouteTablePub}
        :SubnetId {:Ref :SubnetPubA}}}

      :SubnetRouteTableAssociationPubC
      {:Type "AWS::EC2::SubnetRouteTableAssociation"
       :Properties
       {:RouteTableId {:Ref :RouteTablePub}
        :SubnetId {:Ref :SubnetPubC}}}

      :RoutePubAttachInternetGateway
      {:Type "AWS::EC2::Route"
       :DependsOn :AttachGateway
       :Properties
       {:DestinationCidrBlock "0.0.0.0/0"
        :RouteTableId {:Ref :RouteTablePub}
        :GatewayId {:Ref :InternetGateway}}}}

     {:RouteTablePriA
      {:Type "AWS::EC2::RouteTable"
       :Properties
       (a.cfn/tag-name
        {:VpcId {:Ref :Vpc}
         :TagName (a.cfn/prefix "pri-a")})}

      :SubnetRouteTableAssociationPriA
      {:Type "AWS::EC2::SubnetRouteTableAssociation"
       :Properties
       {:RouteTableId {:Ref :RouteTablePriA}
        :SubnetId {:Ref :SubnetPriA}}}}

     {:RouteTablePriC
      {:Type "AWS::EC2::RouteTable"
       :Properties
       (a.cfn/tag-name
        {:VpcId {:Ref :Vpc}
         :TagName (a.cfn/prefix "pri-app-c")})}

      :SubnetRouteTableAssociationPriC
      {:Type "AWS::EC2::SubnetRouteTableAssociation"
       :Properties
       {:RouteTableId {:Ref :RouteTablePriC}
        :SubnetId {:Ref :SubnetPriC}}}})

    :Outputs
    (a.cfn/list-outputs
     {:InternetGateway {:Ref :InternetGateway}
      :RouteTablePub {:Ref :RouteTablePub}
      :RouteTablePriA {:Ref :RouteTablePriA}
      :RouteTablePriC {:Ref :RouteTablePriC}})}))

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
                       (into {}))
          status (->> (or (-> (c.util/eshell {:out :string :continue true} "aws" "cloudformation" "describe-stacks" "--stack-name" stack-name)
                              :out
                              (json/parse-string keyword)
                              :Stacks)
                          [])
                      first
                      :StackStatus)]
      (println status)
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
                           :Vpc (get exports (keyword (format "%s-%s" (-> param :prefix) (name :Vpc))))
                           :SubnetPubA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPubA))))
                           :SubnetPriA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPriA))))
                           :SubnetPubC (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPubC))))
                           :SubnetPriC (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPriC))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " ")))))
  (shutdown-agents))
