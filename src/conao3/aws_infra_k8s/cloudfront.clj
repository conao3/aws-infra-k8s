(ns conao3.aws-infra-k8s.cloudfront
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn cfn [_param]
  (let [cloudflare-ips (-> (io/resource "cloudflare-ips.edn") slurp edn/read-string)]
    (a.cfn/template
     {:Parameters
      (merge
       (a.cfn/list-string-parameters
        [:Env :Prefix
         :LoadBalancerDnsName
         :CertificateArn])
       {:DomainAliases
        {:Type "CommaDelimitedList"
         :Description "Comma-delimited list of domain aliases"}})

      :Resources
      {:CloudflareIPSetV4
       {:Type "AWS::WAFv2::IPSet"
        :Properties
        {:Name (a.cfn/prefix "cloudflare-ipv4")
         :Scope "CLOUDFRONT"
         :IPAddressVersion "IPV4"
         :Addresses (:ipv4 cloudflare-ips)}}

       :CloudflareIPSetV6
       {:Type "AWS::WAFv2::IPSet"
        :Properties
        {:Name (a.cfn/prefix "cloudflare-ipv6")
         :Scope "CLOUDFRONT"
         :IPAddressVersion "IPV6"
         :Addresses (:ipv6 cloudflare-ips)}}

       :AdminAuthFunction
       {:Type "AWS::CloudFront::Function"
        :Properties
        {:Name (a.cfn/prefix "admin-auth")
         :AutoPublish true
         :FunctionConfig {:Comment "Cloudflare Access validation for admin subdomain" :Runtime "cloudfront-js-2.0"}
         :FunctionCode (str "function handler(event) {\n"
                            "  var request = event.request;\n"
                            "  var headers = request.headers;\n"
                            "  var host = headers.host ? headers.host.value : '';\n"
                            "  \n"
                            "  if (host !== 'admin.sancode.dev') {\n"
                            "    return request;\n"
                            "  }\n"
                            "  \n"
                            "  if (!headers['cf-access-jwt-assertion']) {\n"
                            "    return {\n"
                            "      statusCode: 403,\n"
                            "      statusDescription: 'Forbidden',\n"
                            "      headers: {\n"
                            "        'content-type': { value: 'text/plain' }\n"
                            "      },\n"
                            "      body: 'Access denied. Please use the authenticated domain.'\n"
                            "    };\n"
                            "  }\n"
                            "  \n"
                            "  return request;\n"
                            "}\n")}}

       :WebACL
       {:Type "AWS::WAFv2::WebACL"
        :Properties
        {:Name (a.cfn/prefix "waf")
         :Scope "CLOUDFRONT"
         :DefaultAction {:Allow {}}
         :Rules
         [{:Name "CloudflareIPOnlyRule"
           :Priority 0
           :Statement
           {:NotStatement
            {:Statement
             {:OrStatement
              {:Statements
               [{:IPSetReferenceStatement
                 {:Arn {"Fn::GetAtt" [:CloudflareIPSetV4 :Arn]}}}
                {:IPSetReferenceStatement
                 {:Arn {"Fn::GetAtt" [:CloudflareIPSetV6 :Arn]}}}]}}}}
           :Action {:Block {}}
           :VisibilityConfig
           {:SampledRequestsEnabled true
            :CloudWatchMetricsEnabled true
            :MetricName (a.cfn/prefix "cloudflare-ip-only")}}
          {:Name "RateLimitRule"
           :Priority 1
           :Statement
           {:RateBasedStatement
            {:Limit 2000
             :AggregateKeyType "FORWARDED_IP"
             :ForwardedIPConfig
             {:HeaderName "X-Forwarded-For"
              :FallbackBehavior "MATCH"}}}
           :Action {:Block {}}
           :VisibilityConfig
           {:SampledRequestsEnabled true
            :CloudWatchMetricsEnabled true
            :MetricName (a.cfn/prefix "rate-limit")}}
          {:Name "AWSManagedRulesCommonRuleSet"
           :Priority 2
           :Statement
           {:ManagedRuleGroupStatement
            {:VendorName "AWS"
             :Name "AWSManagedRulesCommonRuleSet"}}
           :OverrideAction {:None {}}
           :VisibilityConfig
           {:SampledRequestsEnabled true
            :CloudWatchMetricsEnabled true
            :MetricName (a.cfn/prefix "common-ruleset")}}
          {:Name "AWSManagedRulesKnownBadInputsRuleSet"
           :Priority 3
           :Statement
           {:ManagedRuleGroupStatement
            {:VendorName "AWS"
             :Name "AWSManagedRulesKnownBadInputsRuleSet"}}
           :OverrideAction {:None {}}
           :VisibilityConfig
           {:SampledRequestsEnabled true
            :CloudWatchMetricsEnabled true
            :MetricName (a.cfn/prefix "known-bad-inputs")}}]
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
          :Aliases {:Ref :DomainAliases}
          :ViewerCertificate
          {:AcmCertificateArn {:Ref :CertificateArn}
           :SslSupportMethod "sni-only"
           :MinimumProtocolVersion "TLSv1.2_2021"}
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
           :CachePolicyId "83da9c7e-98b4-4e11-a168-04f0df8e2c65"
           :OriginRequestPolicyId "216adef6-5c7f-47e4-b989-5492eafa07d3"
           :FunctionAssociations
           [{:EventType "viewer-request"
             :FunctionARN {"Fn::GetAtt" [:AdminAuthFunction :FunctionARN]}}]}}}}}

      :Outputs
      (a.cfn/list-outputs
       {:WebACL {:Ref :WebACL}
        :Distribution {:Ref :Distribution}
        :DistributionDomainName {"Fn::GetAtt" [:Distribution :DomainName]}})})))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cloudfront.json")
        stack-name (str (-> param :prefix) "-" "cloudfront")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)) "--region" "us-east-1")
    (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "ap-northeast-1")
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
                           :LoadBalancerDnsName (get exports (keyword (format "%s-%s" (-> param :prefix) "LoadBalancerDnsName")))
                           :CertificateArn (-> param :certificate-arn)
                           :DomainAliases (-> param :domain-aliases)}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
