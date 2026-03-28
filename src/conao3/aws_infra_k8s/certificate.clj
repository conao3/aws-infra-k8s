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

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix :DomainName])

    :Resources
    {:Certificate (resource-certificate {:Ref :DomainName})}

    :Outputs
    (a.cfn/list-outputs
     {:CertificateArn {:Ref :Certificate}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/certificate.json")
        stack-name (str (-> param :prefix) "-" "certificate")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--region" "us-east-1"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)
                         :DomainName (-> param :domain-name)}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
