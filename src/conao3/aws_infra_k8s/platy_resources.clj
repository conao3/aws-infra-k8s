(ns conao3.aws-infra-k8s.platy-resources
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-cache-bucket []
  {:Type "AWS::S3::Bucket"
   :Properties
   {:BucketName (a.cfn/prefix "platy-cache")
    :LifecycleConfiguration
    {:Rules
     [{:Id "ExpireCache"
       :Status "Enabled"
       :ExpirationInDays 30}]}}})

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "platy-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "PlatyCodeBuildPolicy"
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
         ["ecr:GetAuthorizationToken"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["ecr:BatchCheckLayerAvailability"
          "ecr:GetDownloadUrlForLayer"
          "ecr:BatchGetImage"
          "ecr:PutImage"
          "ecr:InitiateLayerUpload"
          "ecr:UploadLayerPart"
          "ecr:CompleteLayerUpload"]
         :Resource
         [{"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-platy-backend"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-platy-backend-bun"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-platy-frontend"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-platy-migrate"}]}
        {:Effect "Allow"
         :Action
         ["cloudformation:ListExports"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["s3:GetObject"
          "s3:PutObject"
          "s3:GetBucketAcl"
          "s3:GetBucketLocation"]
         :Resource
         [{"Fn::GetAtt" [:CacheBucket :Arn]}
          {"Fn::Sub" "${CacheBucket.Arn}/*"}]}
        {:Effect "Allow"
         :Action
         ["ssm:GetParameter"]
         :Resource
         [{"Fn::Sub" "arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${Prefix}-kubeconfig"}]}]}}]}})

(defn resource-codebuild-project [name buildspec]
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix name)
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/conao3/rust-platy.git"
     :BuildSpec buildspec
     :GitCloneDepth 1}
    :Environment
    {:Type "ARM_CONTAINER"
     :Image "aws/codebuild/amazonlinux2-aarch64-standard:3.0"
     :ComputeType "BUILD_GENERAL1_LARGE"
     :PrivilegedMode true
     :EnvironmentVariables
     [{:Name "PREFIX"
       :Value {:Ref :Prefix}}]}
    :Cache
    {:Type "NO_CACHE"}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :TimeoutInMinutes 60}})

(defn resource-sfn-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "platy-sfn")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "states.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "PlatySfnPolicy"
      :PolicyDocument
      {:Version "2012-10-17"
       :Statement
       [{:Effect "Allow"
         :Action
         ["codebuild:StartBuild"
          "codebuild:StopBuild"
          "codebuild:BatchGetBuilds"
          "codebuild:BatchGetReports"]
         :Resource
         [{"Fn::GetAtt" [:CodeBuildProjectBackend :Arn]}
          {"Fn::GetAtt" [:CodeBuildProjectMigrate :Arn]}
          {"Fn::GetAtt" [:CodeBuildProjectBackendBun :Arn]}
          {"Fn::GetAtt" [:CodeBuildProjectFrontend :Arn]}
          {"Fn::GetAtt" [:CodeBuildProjectDeploy :Arn]}]}
        {:Effect "Allow"
         :Action
         ["events:PutTargets"
          "events:PutRule"
          "events:DescribeRule"]
         :Resource "*"}
        {:Effect "Allow"
         :Action
         ["states:StartExecution"
          "states:DescribeExecution"
          "states:StopExecution"]
         :Resource "*"}]}}]}})

(defn resource-sfn-build []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "platy-build")
    :Type "STANDARD"
    :Role {"Fn::GetAtt" [:SfnRole :Arn]}
    :Definition
    {:StartAt "BuildAll"
     :States
     {:BuildAll
      {:Type "Parallel"
       :End true
       :Branches
       [{:StartAt "BuildBackend"
         :States
         {:BuildBackend
          {:Type "Task"
           :Resource "arn:aws:states:::codebuild:startBuild.sync"
           :Parameters {"ProjectName" "${CodeBuildProjectBackend}"}
           :End true}}}
        {:StartAt "BuildMigrate"
         :States
         {:BuildMigrate
          {:Type "Task"
           :Resource "arn:aws:states:::codebuild:startBuild.sync"
           :Parameters {"ProjectName" "${CodeBuildProjectMigrate}"}
           :End true}}}
        {:StartAt "BuildBackendBun"
         :States
         {:BuildBackendBun
          {:Type "Task"
           :Resource "arn:aws:states:::codebuild:startBuild.sync"
           :Parameters {"ProjectName" "${CodeBuildProjectBackendBun}"}
           :End true}}}
        {:StartAt "BuildFrontend"
         :States
         {:BuildFrontend
          {:Type "Task"
           :Resource "arn:aws:states:::codebuild:startBuild.sync"
           :Parameters {"ProjectName" "${CodeBuildProjectFrontend}"}
           :End true}}}]}}}
    :DefinitionSubstitutions
    {:CodeBuildProjectBackend {"Ref" :CodeBuildProjectBackend}
     :CodeBuildProjectMigrate {"Ref" :CodeBuildProjectMigrate}
     :CodeBuildProjectBackendBun {"Ref" :CodeBuildProjectBackendBun}
     :CodeBuildProjectFrontend {"Ref" :CodeBuildProjectFrontend}}}})

(defn resource-sfn-deploy []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "platy-deploy")
    :Type "STANDARD"
    :Role {"Fn::GetAtt" [:SfnRole :Arn]}
    :Definition
    {:StartAt "Deploy"
     :States
     {:Deploy
      {:Type "Task"
       :Resource "arn:aws:states:::codebuild:startBuild.sync"
       :Parameters {"ProjectName" "${CodeBuildProjectDeploy}"}
       :End true}}}
    :DefinitionSubstitutions
    {:CodeBuildProjectDeploy {"Ref" :CodeBuildProjectDeploy}}}})

(defn resource-sfn-build-and-deploy []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "platy-build-and-deploy")
    :Type "STANDARD"
    :Role {"Fn::GetAtt" [:SfnRole :Arn]}
    :Definition
    {:StartAt "Build"
     :States
     {:Build
      {:Type "Task"
       :Resource "arn:aws:states:::states:startExecution.sync"
       :Parameters
       {:StateMachineArn {"Ref" :SfnBuild}
        "Input.$" "$"}
       :Next "Deploy"}
      :Deploy
      {:Type "Task"
       :Resource "arn:aws:states:::states:startExecution.sync"
       :Parameters
       {:StateMachineArn {"Ref" :SfnDeploy}
        "Input.$" "$"}
       :End true}}}
    :DefinitionSubstitutions
    {:SfnBuild {"Ref" :SfnBuild}
     :SfnDeploy {"Ref" :SfnDeploy}}}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters [:Env :Prefix])

    :Resources
    {:CacheBucket (resource-cache-bucket)
     :CodeBuildRole (resource-codebuild-role)
     :CodeBuildProjectBackend (resource-codebuild-project "platy-build-backend" "buildspecs/backend.yml")
     :CodeBuildProjectMigrate (resource-codebuild-project "platy-build-migrate" "buildspecs/migrate.yml")
     :CodeBuildProjectBackendBun (resource-codebuild-project "platy-build-backend-bun" "buildspecs/backend-bun.yml")
     :CodeBuildProjectFrontend (resource-codebuild-project "platy-build-frontend" "buildspecs/frontend.yml")
     :CodeBuildProjectDeploy (resource-codebuild-project "platy-deploy" "buildspecs/deploy.yml")
     :SfnRole (resource-sfn-role)
     :SfnBuild (resource-sfn-build)
     :SfnDeploy (resource-sfn-deploy)
     :SfnBuildAndDeploy (resource-sfn-build-and-deploy)}

    :Outputs
    (a.cfn/list-outputs
     {:PlatyBuildSfn {:Ref :SfnBuild}
      :PlatyDeploySfn {:Ref :SfnDeploy}
      :PlatyBuildAndDeploySfn {:Ref :SfnBuildAndDeploy}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/platy-resources.json")
        stack-name (str (-> param :prefix) "-" "platy-resources")]
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
