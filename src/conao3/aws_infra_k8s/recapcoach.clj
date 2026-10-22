(ns conao3.aws-infra-k8s.recapcoach
  "recapcoach (conao3/ts-recapcoach) のバックエンド。Lambda + Function URL。
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
     {:BEDROCK_MODEL_ID {:Ref :BedrockModelId}
      :API_TOKEN {:Ref :ApiToken}}}
    :Policies
    [{:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Action ["bedrock:InvokeModel"]
        ;; global. などの推論プロファイルは、複数リージョンの foundation-model を使う
        :Resource ["arn:aws:bedrock:*::foundation-model/*"
                   {"Fn::Sub" "arn:aws:bedrock:*:${AWS::AccountId}:inference-profile/*"}]}]}]
    ;; 認証はアプリ側 (Authorization: Bearer)。字幕は保存せず、ログにも出さない
    :FunctionUrlConfig {:AuthType "NONE"}}})

(defn cfn [param]
  (a.cfn/template
   {:Parameters
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix :BedrockModelId])
     {:ApiToken {:Type "String" :NoEcho true}})

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
        model-id (required-env "RECAPCOACH_BEDROCK_MODEL_ID")
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
                         :BedrockModelId model-id
                         :ApiToken api-token}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
