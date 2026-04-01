(ns conao3.aws-infra-k8s.electrobunmacs-resources
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "electrobunmacs-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "ElectrobunmacsCodeBuildPolicy"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["logs:CreateLogGroup"
          "logs:CreateLogStream"
          "logs:PutLogEvents"]
         :Resource "*"}]}}]}})

(defn resource-codebuild-runner []
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix "electrobunmacs-runner")
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/electrobunmacs/electrobunmacs.git"}
    :Environment
    {:Type "LINUX_CONTAINER"
     :Image "aws/codebuild/amazonlinux2-x86_64-standard:5.0"
     :ComputeType "BUILD_GENERAL1_MEDIUM"}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :Triggers
    {:Webhook true
     :FilterGroups
     [[{:Type "EVENT"
        :Pattern "WORKFLOW_JOB_QUEUED"}]]}
    :TimeoutInMinutes 60}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix])

    :Resources
    {:CodeBuildRole (resource-codebuild-role)
     :CodeBuildRunner (resource-codebuild-runner)}}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/electrobunmacs-resources.json")
        stack-name (str (-> param :prefix) "-" "electrobunmacs-resources")]
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
