(ns conao3.aws-infra-k8s.sanplan-resources
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
   {:BucketName (a.cfn/prefix "sanplan-cache")
    :LifecycleConfiguration
    {:Rules
     [{:Id "ExpireCache"
       :Status "Enabled"
       :ExpirationInDays 30}]}}})

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "sanplan-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "SanplanCodeBuildPolicy"
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
         [{"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-sanplan-backend"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-sanplan-backend-bun"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-sanplan-frontend"}
          {"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-sanplan-migrate"}]}
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
         [{"Fn::Sub" "arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${Prefix}-kubeconfig"}]}
        {:Effect "Allow"
         :Action
         ["ec2:CreateNetworkInterface"
          "ec2:DescribeDhcpOptions"
          "ec2:DescribeNetworkInterfaces"
          "ec2:DeleteNetworkInterface"
          "ec2:DescribeSubnets"
          "ec2:DescribeSecurityGroups"
          "ec2:DescribeVpcs"
          "ec2:CreateNetworkInterfacePermission"]
         :Resource "*"}]}}]}})

(defn resource-codebuild-project [name buildspec]
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix name)
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/conao3/rust-sanplan.git"
     :BuildSpec buildspec
     :GitCloneDepth 1}
    :Environment
    {:Type "ARM_CONTAINER"
     :Image "aws/codebuild/amazonlinux2-aarch64-standard:3.0"
     :ComputeType "BUILD_GENERAL1_LARGE"
     :PrivilegedMode true
     :EnvironmentVariables
     [{:Name "PREFIX"
       :Value {:Ref :Prefix}}
      {:Name "CACHE_BUCKET"
       :Value {:Ref :CacheBucket}}]}
    :Cache
    {:Type "NO_CACHE"}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :TimeoutInMinutes 60}})

(defn resource-codebuild-project-deploy [name buildspec]
  (-> (resource-codebuild-project name buildspec)
      (assoc-in [:Properties :VpcConfig]
                {:VpcId {:Ref :Vpc}
                 :Subnets [{:Ref :SubnetPriA}
                           {:Ref :SubnetPriC}
                           {:Ref :SubnetPriD}]
                 :SecurityGroupIds [{:Ref :SecurityGroupApp}]})
      (update-in [:Properties :Environment :EnvironmentVariables]
                 conj
                 {:Name "COGNITO_USER_POOL_ID"
                  :Value {:Ref :UserPool}}
                 {:Name "COGNITO_CLIENT_ID"
                  :Value {:Ref :UserPoolClient}}
                 {:Name "STRIPE_BILLING_QUEUE_URL"
                  :Value {:Ref :StripeBillingQueue}})))

(defn resource-sfn-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "sanplan-sfn")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "states.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "SanplanSfnPolicy"
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
   {:Name (a.cfn/prefix "sanplan-build")
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
   {:Name (a.cfn/prefix "sanplan-deploy")
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
   {:Name (a.cfn/prefix "sanplan-build-and-deploy")
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

(defn resource-stripe-billing-queue []
  {:Type "AWS::SQS::Queue"
   :Properties
   {:QueueName (a.cfn/prefix "sanplan-billing")}})

(defn resource-stripe-billing-queue-policy []
  {:Type "AWS::SQS::QueuePolicy"
   :Properties
   {:Queues [{:Ref :StripeBillingQueue}]
    :PolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "events.amazonaws.com"}
       :Action "sqs:SendMessage"
       :Resource {"Fn::GetAtt" [:StripeBillingQueue :Arn]}
       :Condition
       {:ArnEquals
        {"aws:SourceArn" {"Fn::GetAtt" [:StripeBillingRule :Arn]}}}}]}}})

(defn resource-stripe-billing-event-bus []
  {:Type "AWS::Events::EventBus"
   :Properties
   {:Name {:Ref :StripeEventSourceName}
    :EventSourceName {:Ref :StripeEventSourceName}}})

(defn resource-stripe-billing-rule []
  {:Type "AWS::Events::Rule"
   :Properties
   {:Name (a.cfn/prefix "sanplan-stripe-billing")
    :EventBusName {:Ref :StripeBillingEventBus}
    :EventPattern {"Fn::Sub" "{\"account\":[\"${AWS::AccountId}\"]}"}
    :State "ENABLED"
    :Targets
    [{:Id "StripeBillingQueue"
      :Arn {"Fn::GetAtt" [:StripeBillingQueue :Arn]}}]}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :Vpc
      :SubnetPriA :SubnetPriC :SubnetPriD
      :SecurityGroupApp
      :StripeEventSourceName
      :UserPool
      :UserPoolClient])

    :Resources
    {:CacheBucket (resource-cache-bucket)
     :CodeBuildRole (resource-codebuild-role)
     :CodeBuildProjectBackend (resource-codebuild-project "sanplan-build-backend" "buildspecs/backend.yml")
     :CodeBuildProjectMigrate (resource-codebuild-project "sanplan-build-migrate" "buildspecs/migrate.yml")
     :CodeBuildProjectBackendBun (resource-codebuild-project "sanplan-build-backend-bun" "buildspecs/backend-bun.yml")
     :CodeBuildProjectFrontend (resource-codebuild-project "sanplan-build-frontend" "buildspecs/frontend.yml")
     :CodeBuildProjectDeploy (resource-codebuild-project-deploy "sanplan-deploy" "buildspecs/deploy.yml")
     :SfnRole (resource-sfn-role)
     :SfnBuild (resource-sfn-build)
     :SfnDeploy (resource-sfn-deploy)
     :SfnBuildAndDeploy (resource-sfn-build-and-deploy)
     :StripeBillingQueue (resource-stripe-billing-queue)
     :StripeBillingQueuePolicy (resource-stripe-billing-queue-policy)
     :StripeBillingEventBus (resource-stripe-billing-event-bus)
     :StripeBillingRule (resource-stripe-billing-rule)}

    :Outputs
    (a.cfn/list-outputs
     {:SanplanBuildSfn {:Ref :SfnBuild}
      :SanplanDeploySfn {:Ref :SfnDeploy}
      :SanplanBuildAndDeploySfn {:Ref :SfnBuildAndDeploy}
      :SanplanBillingQueueUrl {:Ref :StripeBillingQueue}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/sanplan-resources.json")
        stack-name (str (-> param :prefix) "-" "sanplan-resources")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports")
                           :out
                           (json/parse-string keyword))
                       :Exports
                       (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                       (into {}))]
      (c.util/ensure-stack-deployable stack-name)
      (c.util/eshell "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (->> (merge
                           {:Env (-> param :env)
                            :Prefix (-> param :prefix)
                            :StripeEventSourceName (-> param :StripeEventSourceName)}
                           (->> [:Vpc :SubnetPriA :SubnetPriC :SubnetPriD :SecurityGroupApp
                                 :UserPool :UserPoolClient]
                                (map (fn [k]
                                       [k (get exports (keyword (format "%s-%s" (-> param :prefix) (name k))))]))
                                (into {})))
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
