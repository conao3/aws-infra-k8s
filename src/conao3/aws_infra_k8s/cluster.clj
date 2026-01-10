(ns conao3.aws-infra-k8s.cluster
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-iam-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "cluster")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "ec2.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :ManagedPolicyArns
    ["arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
     "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
     "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"]}})

(defn resource-instance-profile []
  {:Type "AWS::IAM::InstanceProfile"
   :Properties
   {:InstanceProfileName (a.cfn/prefix "cluster")
    :Roles [{:Ref :IamRole}]}})

(defn resource-launch-template [name instance-type]
  {:Type "AWS::EC2::LaunchTemplate"
   :Properties
   {:LaunchTemplateName (a.cfn/prefix name)
    :LaunchTemplateData
    {:ImageId "ami-09aa74e80fadac3b7"
     :KeyName (a.cfn/prefix "keypair")
     :InstanceType instance-type
     :IamInstanceProfile {:Arn {"Fn::GetAtt" [:InstanceProfile :Arn]}}
     :MetadataOptions
     {:HttpTokens "optional"
      :HttpPutResponseHopLimit 2
      :HttpEndpoint "enabled"}
     :NetworkInterfaces
     [{:DeviceIndex 0
       :AssociatePublicIpAddress false
       :Ipv6AddressCount 1
       :Groups [{:Ref :SecurityGroupApp}]}]
     :TagSpecifications
     [{:ResourceType "instance"
       :Tags [{:Key "Name" :Value (a.cfn/prefix name)}]}]}}})

(defn resource-autoscaling-group [name subnet-id launch-template-ref]
  {:Type "AWS::AutoScaling::AutoScalingGroup"
   :Properties
   {:AutoScalingGroupName (a.cfn/prefix name)
    :MinSize 1
    :MaxSize 1
    :DesiredCapacity 1
    :VPCZoneIdentifier [{:Ref subnet-id}]
    :LaunchTemplate
    {:LaunchTemplateId {:Ref launch-template-ref}
     :Version {"Fn::GetAtt" [launch-template-ref :LatestVersionNumber]}}}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :SubnetPubA
      :SecurityGroupApp])

    :Resources
    {:IamRole (resource-iam-role)
     :InstanceProfile (resource-instance-profile)
     :LaunchTemplateNode (resource-launch-template "node" "t4g.medium")
     :AutoScalingGroupNode (resource-autoscaling-group "node" :SubnetPubA :LaunchTemplateNode)}

    :Outputs
    (a.cfn/list-outputs
     {:AutoScalingGroupNode {:Ref :AutoScalingGroupNode}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cluster.json")
        stack-name (str (-> param :prefix) "-" "cluster")]
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
                           :SubnetPubA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetPubA))))
                           :SecurityGroupApp (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupApp))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
