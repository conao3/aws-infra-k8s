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
     "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"]
    :Policies
    [{:PolicyName "DataVolumeAccess"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action ["ec2:DescribeVolumes"]
         :Resource "*"}
        {:Effect "Allow"
         :Action ["ec2:AttachVolume" "ec2:DetachVolume"]
         :Resource ["arn:aws:ec2:*:*:volume/*"
                    "arn:aws:ec2:*:*:instance/*"]}]}}]}})

(defn resource-instance-profile []
  {:Type "AWS::IAM::InstanceProfile"
   :Properties
   {:InstanceProfileName (a.cfn/prefix "cluster")
    :Roles [{:Ref :IamRole}]}})

(defn resource-launch-template [name instance-type ami-id]
  {:Type "AWS::EC2::LaunchTemplate"
   :Properties
   {:LaunchTemplateName (a.cfn/prefix name)
    :LaunchTemplateData
    {:ImageId ami-id
     :KeyName (a.cfn/prefix "keypair")
     :InstanceType instance-type
     :IamInstanceProfile {:Arn {"Fn::GetAtt" [:InstanceProfile :Arn]}}
     :MetadataOptions
     {:HttpTokens "required"
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

(defn resource-data-volume []
  {:Type "AWS::EC2::Volume"
   :DeletionPolicy "Retain"
   :UpdateReplacePolicy "Retain"
   :Properties
   {:AvailabilityZone "ap-northeast-1a"
    :Size 20
    :VolumeType "gp3"
    :Encrypted true
    :Tags [{:Key "Name" :Value (a.cfn/prefix "data")}
           {:Key "k3s-data-volume" :Value "true"}]}})

(defn resource-autoscaling-group [name subnet-id launch-template-ref target-group-arn]
  {:Type "AWS::AutoScaling::AutoScalingGroup"
   :Properties
   (cond-> {:AutoScalingGroupName (a.cfn/prefix name)
            :MinSize 1
            :MaxSize 1
            :DesiredCapacity 1
            :VPCZoneIdentifier [{:Ref subnet-id}]
            :LaunchTemplate
            {:LaunchTemplateId {:Ref launch-template-ref}
             :Version {"Fn::GetAtt" [launch-template-ref :LatestVersionNumber]}}}
     target-group-arn (assoc :TargetGroupARNs [target-group-arn]))})

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (-> [:Env :Prefix
         :SubnetPubA
         :SecurityGroupApp]
        a.cfn/list-string-parameters
        (assoc :AmiId
               {:Type "AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>"
                :Default (format "/%s/custom-ami-id" (-> param :prefix))}))

    :Resources
    {:IamRole (resource-iam-role)
     :InstanceProfile (resource-instance-profile)
     :DataVolume (resource-data-volume)
     :LaunchTemplateNode (resource-launch-template "node" "t4g.medium" {:Ref :AmiId})
     :AutoScalingGroupNode (resource-autoscaling-group "node" :SubnetPubA :LaunchTemplateNode nil)}

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
                           :SecurityGroupApp (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupApp))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
