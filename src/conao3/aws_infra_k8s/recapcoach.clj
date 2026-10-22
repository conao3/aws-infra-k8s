(ns conao3.aws-infra-k8s.recapcoach
  "recapcoach (conao3/ts-recapcoach) のバックエンド。Lambda + Function URL。LLM は Vercel AI Gateway (Bedrock は使わない)。
  k8s には載せない。コードは ts-recapcoach の `pnpm --filter @recapcoach/api build` の成果物 (apps/api/dist/lambda)。"
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn- function-name []
  {"Fn::Sub" "${Prefix}-recapcoach-api"})

(defn resource-log-group []
  {:Type "AWS::Logs::LogGroup"
   :Properties
   {:LogGroupName {"Fn::Sub" "/aws/lambda/${Prefix}-recapcoach-api"}
    :RetentionInDays 30}})

(defn resource-function [code-uri]
  {:Type "AWS::Serverless::Function"
   :DependsOn "LogGroup"
   :Properties
   {:FunctionName (function-name)
    :Runtime "nodejs22.x"
    :Architectures ["arm64"]
    :Handler "index.handler"
    :CodeUri code-uri
    :Timeout 60
    :MemorySize 512
    :Environment
    {:Variables
     {:AI_GATEWAY_MODEL {:Ref :AiGatewayModel}
      :AI_GATEWAY_API_KEY {:Ref :AiGatewayApiKey}
      :API_TOKEN {:Ref :ApiToken}}}
    ;; 認証はアプリ側 (Authorization: Bearer)。字幕は保存せず、ログにも出さない
    :FunctionUrlConfig {:AuthType "NONE"}}})

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix :AiGatewayModel])
     {:ApiToken {:Type "String" :NoEcho true}
      :AiGatewayApiKey {:Type "String" :NoEcho true}})

    :Resources
    {:LogGroup (resource-log-group)
     :Function (resource-function (:code-uri param))}

    :Outputs
    (a.cfn/list-outputs
     {:RecapcoachApiUrl {"Fn::GetAtt" ["FunctionUrl" "FunctionUrl"]}
      :RecapcoachApiFunction {:Ref :Function}})}))

(defn- required-env [name]
  (let [v (System/getenv name)]
    (when (str/blank? v)
      (throw (ex-info (format "%s is not set" name) {:name name})))
    v))

(defn deploy [param]
  (let [file (fs/file "target/cfn/recapcoach.json")
        stack-name (str (-> param :prefix) "-" "recapcoach")
        code-uri (str (fs/absolutize
                       (or (System/getenv "RECAPCOACH_DIST")
                           (fs/expand-home "~/ghq/github.com/conao3/ts-recapcoach/apps/api/dist/lambda"))))
        model-id (required-env "RECAPCOACH_AI_GATEWAY_MODEL")
        gateway-key (required-env "RECAPCOACH_AI_GATEWAY_API_KEY")
        api-token (required-env "RECAPCOACH_API_TOKEN")]
    (when-not (fs/exists? (fs/path code-uri "index.mjs"))
      (throw (ex-info (format "build first: pnpm --filter @recapcoach/api build (%s)" code-uri)
                      {:code-uri code-uri})))
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn (assoc param :code-uri code-uri))
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--capabilities" "CAPABILITY_IAM"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (->> {:Env (-> param :env)
                         :Prefix (-> param :prefix)
                         :AiGatewayModel model-id
                         :AiGatewayApiKey gateway-key
                         :ApiToken api-token}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
