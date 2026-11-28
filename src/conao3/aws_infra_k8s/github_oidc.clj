(ns conao3.aws-infra-k8s.github-oidc
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-oidc-provider []
  {:Type "AWS::IAM::OIDCProvider"
   :Properties
   {:Url "https://token.actions.githubusercontent.com"
    :ClientIdList ["sts.amazonaws.com"]
    :ThumbprintList ["6938fd4d98bab03faadb97b34396831e3780aea1"
                     "1c58a3a8518e8759bf075b76b750d4f2df264fcd"]}})

(defn resource-github-actions-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "github-actions")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Federated {"Fn::ImportValue" {"Fn::Sub" "${Prefix}-GitHubOIDCProviderArn"}}}
       :Action "sts:AssumeRoleWithWebIdentity"
       :Condition
       {:StringEquals
        {"token.actions.githubusercontent.com:aud" "sts.amazonaws.com"
         "token.actions.githubusercontent.com:sub"
         {"Fn::Sub" "repo:${GitHubRepoOwner}/${GitHubRepoName}:environment:${Env}"}}}}]}
    :Policies
    [{:PolicyName "PushAppAdminImage"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action ["ecr:GetAuthorizationToken"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ecr:BatchCheckLayerAvailability"
          "ecr:InitiateLayerUpload"
          "ecr:UploadLayerPart"
          "ecr:CompleteLayerUpload"
          "ecr:PutImage"]
         :Resource {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-app-admin"}}]}}]}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (-> [:Env :Prefix]
        a.cfn/list-string-parameters
        (assoc :GitHubRepoOwner
               {:Type "String"
                :Default "conao3"
                :Description "GitHub repository owner"}
               :GitHubRepoName
               {:Type "String"
                :Default "aws-infra-k8s"
                :Description "GitHub repository name"}))

    :Resources
    {:GitHubActionsRole (resource-github-actions-role)}

    :Outputs
    (a.cfn/list-outputs
     {:GitHubActionsRoleArn {"Fn::GetAtt" [:GitHubActionsRole :Arn]}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/github-oidc.json")
        stack-name (str (-> param :prefix) "-" "github-oidc")]
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
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (->> {:Env (-> param :env)
                           :Prefix (-> param :prefix)
                           :GitHubRepoOwner (or (-> param :github-repo-owner) "conao3")
                           :GitHubRepoName (or (-> param :github-repo-name) "aws-infra-k8s")}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " ")))))

(defn provider-cfn [_param]
  (a.cfn/template
   {:Parameters (a.cfn/list-string-parameters [:Env :Prefix])
    :Resources {:GitHubOIDCProvider (resource-oidc-provider)}
    :Outputs (a.cfn/list-outputs {:GitHubOIDCProviderArn {"Fn::GetAtt" [:GitHubOIDCProvider :Arn]}})}))

(defn deploy-provider [param]
  (let [file (fs/file "target/cfn/github-oidc-provider.json")
        stack-name (str (-> param :prefix) "-github-oidc-provider")]
    (fs/create-dirs (fs/parent file))
    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (json/generate-stream (provider-cfn param) writer))
    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    (c.util/eshell "sam" "deploy"
                   "--template-file" (str (fs/path file))
                   "--stack-name" stack-name
                   "--resolve-s3"
                   "--no-fail-on-empty-changeset"
                   "--on-failure" "DELETE"
                   "--parameter-overrides"
                   (format "Env=\"%s\" Prefix=\"%s\"" (-> param :env) (-> param :prefix)))))
