(ns conao3.aws-infra-k8s.ssh-tunnel 
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
    (-> [:Env :Prefix
         :SubnetPubA :SecurityGroupSshTunnel]
        a.cfn/list-string-parameters
        (assoc :ImageIdAmazonLinux
               {:Type "AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>"
                :Default "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-6.1-x86_64"}))

    :Resources
    {:InstanceSshTunnel
     {:Type "AWS::EC2::Instance"
      :Properties
      (a.cfn/tag-name
       {:TagName (a.cfn/prefix "SshTunnel")
        :ImageId {:Ref :ImageIdAmazonLinux}
        :InstanceType "t3.micro"
        :SubnetId {:Ref :SubnetPubA}
        :SecurityGroupIds [{:Ref :SecurityGroupSshTunnel}]})}}

    :Outputs
    (a.cfn/list-outputs
     {:InstanceSshTunnel {:Ref :InstanceSshTunnel}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/ssh-tunnel.json")
        stack-name (str (-> param :prefix) "-" "ssh-tunnel")]
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
                           :SecurityGroupSshTunnel (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupSshTunnel))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
