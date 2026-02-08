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
         ["ssm:GetParameter"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["lambda:InvokeFunction"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["events:PutRule"
          "events:PutTargets"
          "events:DescribeRule"
          "events:DeleteRule"
          "events:RemoveTargets"]
         :Resource "*"}]}}]}})

(defn resource-lambda-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "ami-builder-lambda")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "lambda.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :ManagedPolicyArns
    ["arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"]
    :Policies
    [{:PolicyName "AmiImporterPolicy"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["ec2:ImportImage"
          "ec2:DescribeImportImageTasks"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ssm:PutParameter"
          "ssm:GetParameter"]
         :Resource "*"}]}}]}})

(defn resource-lambda-function []
  {:Type "AWS::Serverless::Function"
   :Properties
   {:FunctionName (a.cfn/prefix "ami-importer")
    :Runtime "provided.al2023"
    :Handler "bootstrap"
    :CodeUri "lambda/ami-importer"
    :Role {"Fn::GetAtt" [:LambdaRole :Arn]}
    :Timeout 900
    :MemorySize 256
    :Architectures ["arm64"]}})

(defn resource-state-machine []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "ami-builder")
    :Role {"Fn::GetAtt" [:StepFunctionsRole :Arn]}
    :DefinitionSubstitutions
    {:Prefix {:Ref :Prefix}
     :LambdaFunctionArn {"Fn::GetAtt" [:LambdaFunction :Arn]}}
    :Definition
    {:Comment "Build AMI using CodeBuild, Lambda and Step Functions"
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
       :Next "GetS3Location"}
      :BuildFailed
      {:Type "Fail"
       :Error "CodeBuildFailed"
       :Cause "CodeBuild execution failed"}
      :GetS3Location
      {:Type "Parallel"
       :Branches
       [{:StartAt "GetS3Bucket"
         :States
         {:GetS3Bucket
          {:Type "Task"
           :Resource "arn:aws:states:::aws-sdk:ssm:getParameter"
           :Parameters
           {:Name "/${Prefix}/ami-builder/s3-bucket"}
           :End true}}}
        {:StartAt "GetS3Key"
         :States
         {:GetS3Key
          {:Type "Task"
           :Resource "arn:aws:states:::aws-sdk:ssm:getParameter"
           :Parameters
           {:Name "/${Prefix}/ami-builder/s3-key"}
           :End true}}}
        {:StartAt "GetTimestamp"
         :States
         {:GetTimestamp
          {:Type "Task"
           :Resource "arn:aws:states:::aws-sdk:ssm:getParameter"
           :Parameters
           {:Name "/${Prefix}/ami-builder/timestamp"}
           :End true}}}]
       :ResultPath "$.params"
       :Next "PrepareImportInput"}
      :PrepareImportInput
      {:Type "Pass"
       :Parameters
       {:prefix "${Prefix}"
        :s3_bucket.$ "$.params[0].Parameter.Value"
        :s3_key.$ "$.params[1].Parameter.Value"
        :timestamp.$ "$.params[2].Parameter.Value"}
       :Next "ImportImage"}
      :ImportImage
      {:Type "Task"
       :Resource "arn:aws:states:::lambda:invoke"
       :Parameters
       {:FunctionName "${LambdaFunctionArn}"
        :Payload.$ "$"}
       :ResultPath "$.import_result"
       :Retry
       [{:ErrorEquals ["ImportInProgress"]
         :IntervalSeconds 60
         :MaxAttempts 20
         :BackoffRate 1.0}]
       :Catch
       [{:ErrorEquals ["States.ALL"]
         :Next "ImportFailed"}]
       :End true}
      :ImportFailed
      {:Type "Fail"
       :Error "ImportImageFailed"
       :Cause "Import image task failed"}}}}})

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
     :LambdaRole (resource-lambda-role)
     :LambdaFunction (resource-lambda-function)
     :StepFunctionsRole (resource-stepfunctions-role)
     :StateMachine (resource-state-machine)}

    :Outputs
    (a.cfn/list-outputs
     {:CodeBuildProject {:Ref :CodeBuildProject}
      :LambdaFunction {:Ref :LambdaFunction}
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
