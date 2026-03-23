(ns conao3.aws-infra-k8s.platy-builder
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
   {:BucketName (a.cfn/prefix "platy-builder-cache")
    :LifecycleConfiguration
    {:Rules
     [{:Id "ExpireCache"
       :Status "Enabled"
       :ExpirationInDays 30}]}}})

(defn resource-codebuild-role []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "platy-builder-codebuild")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Service "codebuild.amazonaws.com"}
       :Action "sts:AssumeRole"}]}
    :Policies
    [{:PolicyName "CodeBuildPlatyBuilderPolicy"
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
          {"Fn::Sub" "${CacheBucket.Arn}/*"}]}]}}]}})

(defn resource-codebuild-project []
  {:Type "AWS::CodeBuild::Project"
   :Properties
   {:Name (a.cfn/prefix "platy-builder")
    :ServiceRole {"Fn::GetAtt" [:CodeBuildRole :Arn]}
    :Source
    {:Type "GITHUB"
     :Location "https://github.com/conao3/rust-platy.git"
     :BuildSpec "buildspec.yml"
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
    {:Type "S3"
     :Location {"Fn::Sub" "${CacheBucket}/platy-builder"}}
    :Artifacts
    {:Type "NO_ARTIFACTS"}
    :LogsConfig
    {:CloudWatchLogs
     {:Status "ENABLED"}}
    :TimeoutInMinutes 30}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters [:Env :Prefix])

    :Resources
    {:CacheBucket (resource-cache-bucket)
     :CodeBuildRole (resource-codebuild-role)
     :CodeBuildProject (resource-codebuild-project)}

    :Outputs
    (a.cfn/list-outputs
     {:PlatyBuilderProject {:Ref :CodeBuildProject}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/platy-builder.json")
        stack-name (str (-> param :prefix) "-" "platy-builder")]
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
