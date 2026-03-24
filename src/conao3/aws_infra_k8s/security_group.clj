(ns conao3.aws-infra-k8s.security-group
  (:require
   [camel-snake-kebab.core :as csk]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn cfn [_param]
  (let [security-group (fn [x]
                         {:Type "AWS::EC2::SecurityGroup"
                          :Properties
                          (a.cfn/tag-name
                           {:TagName (a.cfn/prefix (csk/->kebab-case x))
                            :VpcId {:Ref :Vpc}
                            :GroupName (a.cfn/prefix (csk/->kebab-case x))
                            :GroupDescription (format "Security Group for %s" (csk/->PascalCase x))})})]
    (a.cfn/template
     {:Parameters
      (a.cfn/list-string-parameters
       [:Env :Prefix
        :Vpc])

      :Resources
      {:SecurityGroupApp (security-group "App")
       :SecurityGroupAlb (security-group "Alb")
       :SecurityGroupSshTunnel (security-group "SshTunnel")
       :SecurityGroupEice (security-group "Eice")
       :SecurityGroupRds (security-group "Rds")
       :SecurityGroupEfs (security-group "Efs")
       :SecurityGroupIngressAppFromEice
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupApp}
         :IpProtocol "tcp"
         :FromPort 22
         :ToPort 22
         :SourceSecurityGroupId {:Ref :SecurityGroupEice}}}
       :SecurityGroupIngressAppFromAlb
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupApp}
         :IpProtocol "tcp"
         :FromPort 30000
         :ToPort 32767
         :SourceSecurityGroupId {:Ref :SecurityGroupAlb}}}
       :SecurityGroupIngressAlbFromCloudFront
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupAlb}
         :IpProtocol "tcp"
         :FromPort 80
         :ToPort 80
         :SourcePrefixListId "pl-58a04531"}}
       :SecurityGroupIngressAlbFromCloudFrontIPv6
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupAlb}
         :IpProtocol "tcp"
         :FromPort 80
         :ToPort 80
         :SourcePrefixListId "pl-b6a144df"}}
       :SecurityGroupIngressSshTunnelFromIpv6
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupSshTunnel}
         :IpProtocol "tcp"
         :FromPort 22
         :ToPort 22
         :CidrIpv6 "::/0"}}
       :SecurityGroupIngressSshTunnelFromEice
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupSshTunnel}
         :IpProtocol "tcp"
         :FromPort 22
         :ToPort 22
         :SourceSecurityGroupId {:Ref :SecurityGroupEice}}}
       :SecurityGroupIngressRdsFromApp
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupRds}
         :IpProtocol "tcp"
         :FromPort 5432
         :ToPort 5432
         :SourceSecurityGroupId {:Ref :SecurityGroupApp}}}
       :SecurityGroupIngressEfsFromApp
       {:Type "AWS::EC2::SecurityGroupIngress"
        :Properties
        {:GroupId {:Ref :SecurityGroupEfs}
         :IpProtocol "tcp"
         :FromPort 2049
         :ToPort 2049
         :SourceSecurityGroupId {:Ref :SecurityGroupApp}}}}

      :Outputs
      (a.cfn/list-outputs
       {:SecurityGroupApp {:Ref :SecurityGroupApp}
        :SecurityGroupAlb {:Ref :SecurityGroupAlb}
        :SecurityGroupSshTunnel {:Ref :SecurityGroupSshTunnel}
        :SecurityGroupEice {:Ref :SecurityGroupEice}
        :SecurityGroupRds {:Ref :SecurityGroupRds}
        :SecurityGroupEfs {:Ref :SecurityGroupEfs}})})))

(defn deploy [param]
  (let [file (fs/file "target/cfn/security-group.json")
        stack-name (str (-> param :prefix) "-" "security-group")]
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
                           :Vpc (get exports (keyword (format "%s-%s" (-> param :prefix) (name :Vpc))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
