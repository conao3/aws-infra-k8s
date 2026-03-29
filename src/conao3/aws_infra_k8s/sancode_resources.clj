(ns conao3.aws-infra-k8s.sancode-resources
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "sancode-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "SancodeCodeBuildPolicy"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["logs:CreateLogGroup"
          "logs:CreateLogStream"
          "logs:PutLogEvents"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["secretsmanager:GetSecretValue"]
         :Resource
         [{"Fn::Sub" "arn:aws:secretsmanager:${AWS::Region}:${AWS::AccountId}:secret:${Prefix}-secret-*"}]}]}}]}})

(defn resource-codebuild-project []
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix "sancode-deploy")
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/conao3/sancode.git"
     :BuildSpec "buildspecs/deploy.yml"
     :GitCloneDepth 1}
    :Environment
    {:Type "LINUX_LAMBDA_CONTAINER"
     :Image "aws/codebuild/amazonlinux-x86_64-lambda-standard:nodejs22"
     :ComputeType "BUILD_LAMBDA_1GB"
     :EnvironmentVariables
     [{:Name "PREFIX"
       :Value {:Ref :Prefix}}
      {:Name "CLOUDFLARE_API_TOKEN"
       :Type "SECRETS_MANAGER"
       :Value {"Fn::Sub" "${Prefix}-secret:sancode-cloudflare-api-token"}}]}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :TimeoutInMinutes 15}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix])

    :Resources
    {:CodeBuildRole (resource-codebuild-role)
     :CodeBuildProject (resource-codebuild-project)}}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/sancode-resources.json")
        stack-name (str (-> param :prefix) "-" "sancode-resources")]
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
                   (format "Env=\"%s\" Prefix=\"%s\""
                           (-> param :env)
                           (-> param :prefix)))))
