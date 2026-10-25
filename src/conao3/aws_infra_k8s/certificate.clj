(ns conao3.aws-infra-k8s.certificate
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-certificate [domain-name]
  {:Type "AWS::CertificateManager::Certificate"
   :Properties
   {:DomainName domain-name
    :ValidationMethod "DNS"
    :Tags [{:Key "Name" :Value (a.cfn/prefix "certificate")}]}})

(defn cfn
  "output-key は Export 名の一部。同じリージョンの ALB の証明書 (`CertificateArn`) と名前を分ける。"
  [_param output-key]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix :DomainName])

    :Resources
    {:Certificate (resource-certificate {:Ref :DomainName})}

    :Outputs
    (a.cfn/list-outputs
     {output-key {:Ref :Certificate}})}))

(defn- deploy-stack
  "ACM 証明書のスタックを region に作る。証明書は DNS 検証なので、検証用 CNAME を Cloudflare に置くまで
   スタックの作成が終わらない (検証レコードは `aws acm describe-certificate` で読む)。"
  [param {:keys [stack-name region domain-name output-key]}]
  (let [file (fs/file (format "target/cfn/%s.json" stack-name))]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param output-key)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--region" region
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)
                         :DomainName domain-name}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))

(defn deploy
  "Cognito のカスタムドメイン用 (CloudFront 経由なので us-east-1)。"
  [param]
  (deploy-stack param {:stack-name (str (-> param :prefix) "-" "certificate")
                       :region "us-east-1"
                       :domain-name (-> param :domain-name)
                       :output-key :CertificateArn}))

(defn deploy-sanrecap
  "sanrecap の API Gateway (リージョナル) のカスタムドメイン用 (ap-northeast-1)。"
  [param]
  (deploy-stack param {:stack-name (str (-> param :prefix) "-" "sanrecap-certificate")
                       :region "ap-northeast-1"
                       :domain-name (-> param :sanrecap-domain-name)
                       :output-key :SanrecapCertificateArn}))
