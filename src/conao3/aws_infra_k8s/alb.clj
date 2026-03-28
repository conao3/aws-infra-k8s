(ns conao3.aws-infra-k8s.alb
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
      :Vpc
      :SubnetPubA :SubnetPubC :SubnetPubD
      :SecurityGroupAlb
      :CertificateDomainName])

    :Resources
    {:Certificate
     {:Type "AWS::CertificateManager::Certificate"
      :Properties
      {:DomainName {:Ref :CertificateDomainName}
       :ValidationMethod "DNS"
       :Tags [{:Key "Name" :Value (a.cfn/prefix "alb-certificate")}]}}

     :TargetGroup
     {:Type "AWS::ElasticLoadBalancingV2::TargetGroup"
      :Properties
      {:Name (a.cfn/prefix "traefik")
       :Port 30080
       :Protocol "HTTP"
       :VpcId {:Ref :Vpc}
       :TargetType "instance"
       :HealthCheckEnabled true
       :HealthCheckPath "/"
       :HealthCheckProtocol "HTTP"
       :HealthCheckIntervalSeconds 30
       :HealthCheckTimeoutSeconds 5
       :HealthyThresholdCount 2
       :UnhealthyThresholdCount 2
       :Tags [{:Key "Name" :Value (a.cfn/prefix "traefik")}]}}

     :LoadBalancer
     {:Type "AWS::ElasticLoadBalancingV2::LoadBalancer"
      :Properties
      {:Name (a.cfn/prefix "alb")
       :Type "application"
       :Scheme "internet-facing"
       :IpAddressType "dualstack-without-public-ipv4"
       :SecurityGroups [{:Ref :SecurityGroupAlb}]
       :Subnets [{:Ref :SubnetPubA}
                 {:Ref :SubnetPubC}
                 {:Ref :SubnetPubD}]
       :Tags [{:Key "Name" :Value (a.cfn/prefix "alb")}]}}

     :ListenerHttps
     {:Type "AWS::ElasticLoadBalancingV2::Listener"
      :Properties
      {:LoadBalancerArn {:Ref :LoadBalancer}
       :Port 443
       :Protocol "HTTPS"
       :Certificates [{:CertificateArn {:Ref :Certificate}}]
       :SslPolicy "ELBSecurityPolicy-TLS13-1-2-2021-06"
       :DefaultActions
       [{:Type "forward"
         :TargetGroupArn {:Ref :TargetGroup}}]}}

     :ListenerHttp
     {:Type "AWS::ElasticLoadBalancingV2::Listener"
      :Properties
      {:LoadBalancerArn {:Ref :LoadBalancer}
       :Port 80
       :Protocol "HTTP"
       :DefaultActions
       [{:Type "redirect"
         :RedirectConfig
         {:Protocol "HTTPS"
          :Port "443"
          :StatusCode "HTTP_301"}}]}}}

    :Outputs
    (a.cfn/list-outputs
     {:LoadBalancer {:Ref :LoadBalancer}
      :LoadBalancerDnsName {"Fn::GetAtt" [:LoadBalancer :DNSName]}
      :TargetGroup {:Ref :TargetGroup}
      :CertificateArn {:Ref :Certificate}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/alb.json")
        stack-name (str (-> param :prefix) "-" "alb")]
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
                            :Prefix (-> param :prefix)
                            :CertificateDomainName (-> param :alb-certificate-domain-name)}
                           (->> [:Vpc :SubnetPubA :SubnetPubC :SubnetPubD :SecurityGroupAlb]
                                (map (fn [k]
                                       [k (get exports (keyword (format "%s-%s" (-> param :prefix) (name k))))]))
                                (into {})))
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " ")))

      (let [exports-after (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports")
                                   :out
                                   (json/parse-string keyword))
                               :Exports
                               (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                               (into {}))
            target-group-arn (get exports-after (keyword (format "%s-%s" (-> param :prefix) "TargetGroup")))
            asg-name (get exports-after (keyword (format "%s-%s" (-> param :prefix) "AutoScalingGroupNode")))]
        (c.util/eprintln (format "Attaching target group to AutoScaling Group: %s" asg-name))
        (c.util/eshell "aws" "autoscaling" "attach-load-balancer-target-groups"
                       "--auto-scaling-group-name" asg-name
                       "--target-group-arns" target-group-arn)))))
