(ns conao3.aws-infra-k8s.cli-proxy-api-resources
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
   {:RoleName (a.cfn/prefix "cli-proxy-api-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "CliProxyApiCodeBuildPolicy"
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
         :Action ["ecr:GetAuthorizationToken"]
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
         [{"Fn::Sub" "arn:aws:ecr:${AWS::Region}:${AWS::AccountId}:repository/${Prefix}-cli-proxy-api"}]}
        {:Effect "Allow"
         :Action ["ssm:GetParameter"]
         :Resource
         [{"Fn::Sub" "arn:aws:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${Prefix}-kubeconfig"}]}
        {:Effect "Allow"
         :Action ["secretsmanager:GetSecretValue"]
         :Resource
         [{"Fn::Sub" "arn:aws:secretsmanager:${AWS::Region}:${AWS::AccountId}:secret:${Prefix}-secret-*"}
          {"Fn::Sub" "arn:aws:secretsmanager:${AWS::Region}:${AWS::AccountId}:secret:${Prefix}-secret"}]}
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
     :Location "https://github.com/conao3/aws-cli-proxy-api.git"
     :BuildSpec buildspec
     :GitCloneDepth 1}
    :Environment
    {:Type "ARM_CONTAINER"
     :Image "aws/codebuild/amazonlinux2-aarch64-standard:3.0"
     :ComputeType "BUILD_GENERAL1_SMALL"
     :PrivilegedMode true
     :EnvironmentVariables
     [{:Name "PREFIX"
       :Value {:Ref :Prefix}}
      {:Name "ECR_REGISTRY"
       :Value {"Fn::Sub" "${AWS::AccountId}.dkr.ecr.${AWS::Region}.amazonaws.com"}}]}
    :Cache {:Type "NO_CACHE"}
    :Artifacts {:Type "NO_ARTIFACTS"}
    :LogsConfig {:CloudWatchLogs {:Status "ENABLED"}}
    :TimeoutInMinutes 30}})

(defn resource-codebuild-project-deploy [name buildspec]
  (-> (resource-codebuild-project name buildspec)
      (assoc-in [:Properties :VpcConfig]
                {:VpcId {:Ref :Vpc}
                 :Subnets [{:Ref :SubnetPriA}
                           {:Ref :SubnetPriC}
                           {:Ref :SubnetPriD}]
                 :SecurityGroupIds [{:Ref :SecurityGroupApp}]})))

(defn resource-sfn-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "cli-proxy-api-sfn")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "states.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "CliProxyApiSfnPolicy"
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
         [{"Fn::GetAtt" [:CodeBuildProjectBuild :Arn]}
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
   {:Name (a.cfn/prefix "cli-proxy-api-build")
    :Type "STANDARD"
    :Role {"Fn::GetAtt" [:SfnRole :Arn]}
    :Definition
    {:StartAt "Build"
     :States
     {:Build
      {:Type "Task"
       :Resource "arn:aws:states:::codebuild:startBuild.sync"
       :Parameters {"ProjectName" "${CodeBuildProjectBuild}"}
       :End true}}}
    :DefinitionSubstitutions
    {:CodeBuildProjectBuild {"Ref" :CodeBuildProjectBuild}}}})

(defn resource-sfn-deploy []
  {:Type "AWS::Serverless::StateMachine"
   :Properties
   {:Name (a.cfn/prefix "cli-proxy-api-deploy")
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
   {:Name (a.cfn/prefix "cli-proxy-api-build-and-deploy")
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
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :Vpc
      :SubnetPriA :SubnetPriC :SubnetPriD
      :SecurityGroupApp])

    :Resources
    {:CodeBuildRole (resource-codebuild-role)
     :CodeBuildProjectBuild (resource-codebuild-project "cli-proxy-api-build" "buildspecs/build.yml")
     :CodeBuildProjectDeploy (resource-codebuild-project-deploy "cli-proxy-api-deploy" "buildspecs/deploy.yml")
     :SfnRole (resource-sfn-role)
     :SfnBuild (resource-sfn-build)
     :SfnDeploy (resource-sfn-deploy)
     :SfnBuildAndDeploy (resource-sfn-build-and-deploy)}

    :Outputs
    (a.cfn/list-outputs
     {:CliProxyApiBuildSfn {:Ref :SfnBuild}
      :CliProxyApiDeploySfn {:Ref :SfnDeploy}
      :CliProxyApiBuildAndDeploySfn {:Ref :SfnBuildAndDeploy}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cli-proxy-api-resources.json")
        stack-name (str (-> param :prefix) "-" "cli-proxy-api-resources")]
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
                            :Prefix (-> param :prefix)}
                           (->> [:Vpc :SubnetPriA :SubnetPriC :SubnetPriD :SecurityGroupApp]
                                (map (fn [k]
                                       [k (get exports (keyword (format "%s-%s" (-> param :prefix) (name k))))]))
                                (into {})))
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
