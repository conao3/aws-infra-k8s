(ns conao3.aws-infra-k8s.cloudfront
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
      :LoadBalancerDnsName])

    :Resources
    {:WebACL
     {:Type "AWS::WAFv2::WebACL"
      :Properties
      {:Name (a.cfn/prefix "waf")
       :Scope "CLOUDFRONT"
       :DefaultAction {:Allow {}}
       :Rules
       [{:Name "RateLimitRule"
         :Priority 1
         :Statement
         {:RateBasedStatement
          {:Limit 2000
           :AggregateKeyType "IP"}}
         :Action {:Block {}}
         :VisibilityConfig
         {:SampledRequestsEnabled true
          :CloudWatchMetricsEnabled true
          :MetricName (a.cfn/prefix "rate-limit")}}
        {:Name "GeoBlockRule"
         :Priority 2
         :Statement
         {:NotStatement
          {:Statement
           {:GeoMatchStatement
            {:CountryCodes ["JP" "US"]}}}}
         :Action {:Block {}}
         :VisibilityConfig
         {:SampledRequestsEnabled true
          :CloudWatchMetricsEnabled true
          :MetricName (a.cfn/prefix "geo-block")}}
        {:Name "SQLiProtectionRule"
         :Priority 3
         :Statement
         {:ManagedRuleGroupStatement
          {:VendorName "AWS"
           :Name "AWSManagedRulesSQLiRuleSet"}}
         :OverrideAction {:None {}}
         :VisibilityConfig
         {:SampledRequestsEnabled true
          :CloudWatchMetricsEnabled true
          :MetricName (a.cfn/prefix "sqli-protection")}}]
       :VisibilityConfig
       {:SampledRequestsEnabled true
        :CloudWatchMetricsEnabled true
        :MetricName (a.cfn/prefix "waf")}}}

     :Distribution
     {:Type "AWS::CloudFront::Distribution"
      :Properties
      {:DistributionConfig
       {:Enabled true
        :Comment (a.cfn/prefix "cdn")
        :HttpVersion "http2and3"
        :PriceClass "PriceClass_All"
        :IPV6Enabled true
        :WebACLId {"Fn::GetAtt" [:WebACL :Arn]}
        :Origins
        [{:Id "alb-origin"
          :DomainName {:Ref :LoadBalancerDnsName}
          :CustomOriginConfig
          {:HTTPPort 80
           :OriginProtocolPolicy "http-only"}}]
        :DefaultCacheBehavior
        {:TargetOriginId "alb-origin"
         :ViewerProtocolPolicy "redirect-to-https"
         :AllowedMethods ["GET" "HEAD" "OPTIONS" "PUT" "POST" "PATCH" "DELETE"]
         :CachedMethods ["GET" "HEAD"]
         :Compress true
         :CachePolicyId "658327ea-f89d-4fab-a63d-7e88639e58f6"
         :OriginRequestPolicyId "216adef6-5c7f-47e4-b989-5492eafa07d3"}}}}}

    :Outputs
    (a.cfn/list-outputs
     {:WebACL {:Ref :WebACL}
      :Distribution {:Ref :Distribution}
      :DistributionDomainName {"Fn::GetAtt" [:Distribution :DomainName]}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cloudfront.json")
        stack-name (str (-> param :prefix) "-" "cloudfront")]
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
                     "--region" "us-east-1"
                     "--parameter-overrides"
                     (->> {:Env (-> param :env)
                           :Prefix (-> param :prefix)
                           :LoadBalancerDnsName (get exports (keyword (format "%s-%s" (-> param :prefix) "LoadBalancerDnsName")))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
