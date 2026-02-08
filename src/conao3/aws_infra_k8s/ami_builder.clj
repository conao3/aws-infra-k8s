(ns conao3.aws-infra-k8s.ami-builder
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "ami-builder-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "CodeBuildAmiBuilderPolicy"
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
         ["ec2:ImportImage"
          "ec2:DescribeImportImageTasks"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["s3:PutObject"
          "s3:GetObject"
          "s3:ListBucket"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ssm:PutParameter"
          "ssm:GetParameter"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["cloudformation:ListExports"
          "cloudformation:DescribeStacks"]
         :Resource "*"}]}}]}})

(defn resource-stepfunctions-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "ami-builder-stepfunctions")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "states.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "StepFunctionsAmiBuilderPolicy"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["codebuild:StartBuild"
          "codebuild:BatchGetBuilds"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ec2:DescribeImportImageTasks"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ssm:PutParameter"
          "ssm:GetParameter"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["events:PutRule"
          "events:PutTargets"
          "events:DescribeRule"
          "events:DeleteRule"
          "events:RemoveTargets"]
         :Resource "*"}]}}]}})

(defn resource-state-machine []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "ami-builder")
    :Role {"Fn::GetAtt" [:StepFunctionsRole :Arn]}
    :DefinitionSubstitutions
    {:Prefix {:Ref :Prefix}}
    :Definition
    {:Comment "Build AMI using CodeBuild and Step Functions"
     :StartAt "StartCodeBuild"
     :States
     {:StartCodeBuild
      {:Type "Task"
       :Resource "arn:aws:states:::codebuild:startBuild.sync"
       :Parameters
       {:ProjectName "${Prefix}-ami-builder"}
       :Catch
       [{:ErrorEquals ["States.ALL"]
         :Next "BuildFailed"}]
       :Next "GetImportTaskId"}
      :BuildFailed
      {:Type "Fail"
       :Error "CodeBuildFailed"
       :Cause "CodeBuild execution failed"}
      :GetImportTaskId
      {:Type "Task"
       :Resource "arn:aws:states:::aws-sdk:ssm:getParameter"
       :Parameters
       {:Name "/${Prefix}/ami-builder/import-task-id"}
       :ResultPath "$.ImportTaskIdParam"
       :Next "WaitForImport"}
      :WaitForImport
      {:Type "Wait"
       :Seconds 30
       :Next "CheckImportStatus"}
      :CheckImportStatus
      {:Type "Task"
       :Resource "arn:aws:states:::aws-sdk:ec2:describeImportImageTasks"
       :Parameters
       {:ImportTaskIds.$ "States.Array($.ImportTaskIdParam.Parameter.Value)"}
       :ResultPath "$.ImportTaskResult"
       :Next "EvaluateImportStatus"}
      :EvaluateImportStatus
      {:Type "Choice"
       :Choices
       [{:Variable "$.ImportTaskResult.ImportImageTasks[0].Status"
         :StringEquals "completed"
         :Next "PutSSMParameter"}
        {:Variable "$.ImportTaskResult.ImportImageTasks[0].Status"
         :StringEquals "active"
         :Next "WaitForImport"}]
       :Default "ImportFailed"}
      :ImportFailed
      {:Type "Fail"
       :Error "ImportImageFailed"
       :Cause "Import image task failed or was deleted"}
      :PutSSMParameter
      {:Type "Task"
       :Resource "arn:aws:states:::aws-sdk:ssm:putParameter"
       :Parameters
       {:Name "/${Prefix}/custom-ami-id"
        :Value.$ "$.ImportTaskResult.ImportImageTasks[0].ImageId"
        :Type "String"
        :Overwrite true}
       :End true}}}}})

(defn resource-codebuild-project []
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix "ami-builder")
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/conao3/aws-infra-k8s.git"
     :BuildSpec "buildspec-ami.yml"
     :GitCloneDepth 1}
    :Environment
    {:Type "ARM_CONTAINER"
     :Image "aws/codebuild/amazonlinux2-aarch64-standard:3.0"
     :ComputeType "BUILD_GENERAL1_LARGE"
     :PrivilegedMode true
     :EnvironmentVariables
     [{:Name "PREFIX"
       :Value {:Ref :Prefix}}]}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :TimeoutInMinutes 120}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters [:Env :Prefix])

    :Resources
    {:CodeBuildRole (resource-codebuild-role)
     :CodeBuildProject (resource-codebuild-project)
     :StepFunctionsRole (resource-stepfunctions-role)
     :StateMachine (resource-state-machine)}

    :Outputs
    (a.cfn/list-outputs
     {:CodeBuildProject {:Ref :CodeBuildProject}
      :StateMachine {:Ref :StateMachine}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/ami-builder.json")
        stack-name (str (-> param :prefix) "-" "ami-builder")]
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
                         :Prefix (-> param :prefix)}
                        (map (fn [[k v]]
                               (format "%s=\"%s\"" (name k) v)))
                        (str/join " ")))))
