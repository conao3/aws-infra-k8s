(ns conao3.aws-infra-k8s.cognito
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-user-pool []
  {:Type "AWS::Cognito::UserPool"
   :Properties
   {:UserPoolName (a.cfn/prefix "user-pool")
    :AutoVerifiedAttributes ["email"]
    :UsernameAttributes ["email"]
    :Schema [{:Name "email"
              :AttributeDataType "String"
              :Required true}]
    ;; :Policies {:PasswordPolicy
    ;;            {:MinimumLength 8
    ;;             :RequireUppercase true
    ;;             :RequireLowercase true
    ;;             :RequireNumbers true
    ;;             :RequireSymbols false}}
    :AccountRecoverySetting
    {:RecoveryMechanisms [{:Name "verified_email"
                           :Priority 1}]}}})

(defn resource-user-pool-domain []
  {:Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain {"Fn::If" ["IsPrd"
                       "auth-k8s.sancode.dev"
                       {"Fn::Sub" "auth-${Prefix}.sancode.dev"}]}
    :CustomDomainConfig
    {:CertificateArn {"Ref" "CertificateArn"}}
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-client []
  {:Type "AWS::Cognito::UserPoolClient"
   :Properties
   {:ClientName (a.cfn/prefix "user-pool-client")
    :UserPoolId {:Ref :UserPool}
    :GenerateSecret false
    :ExplicitAuthFlows ["ALLOW_USER_PASSWORD_AUTH"
                        "ALLOW_USER_SRP_AUTH"
                        "ALLOW_ADMIN_USER_PASSWORD_AUTH"
                        "ALLOW_REFRESH_TOKEN_AUTH"]
    :PreventUserExistenceErrors "ENABLED"
    :CallbackURLs ["http://localhost:5173/auth/callback"
                   "https://sanplan.sancode.dev/auth/callback"]
    :LogoutURLs ["http://localhost:5173/login"
                 "https://sanplan.sancode.dev/login"]
    :AllowedOAuthFlows ["code" "implicit"]
    :AllowedOAuthScopes ["email" "openid" "profile"]
    :AllowedOAuthFlowsUserPoolClient true
    :SupportedIdentityProviders ["COGNITO"]}})

(defn resource-identity-pool []
  {:Type "AWS::Cognito::IdentityPool"
   :Properties
   {:IdentityPoolName (a.cfn/prefix "identity-pool")
    :AllowUnauthenticatedIdentities false
    :CognitoIdentityProviders
    [{:ClientId {:Ref :UserPoolClient}
      :ProviderName {"Fn::GetAtt" [:UserPool :ProviderName]}}]}})

(defn resource-identity-pool-role-authenticated []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "cognito-authenticated")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Federated "cognito-identity.amazonaws.com"}
       :Action "sts:AssumeRoleWithWebIdentity"
       :Condition
       {:StringEquals {"cognito-identity.amazonaws.com:aud" {:Ref :IdentityPool}}
        :ForAnyValue:StringLike {"cognito-identity.amazonaws.com:amr" "authenticated"}}}]}
    :ManagedPolicyArns []
    :Policies []}})

(defn resource-identity-pool-role-unauthenticated []
  {:Type "AWS::IAM::Role"
   :Properties
   {:RoleName (a.cfn/prefix "cognito-unauthenticated")
    :AssumeRolePolicyDocument
    {:Version "2012-10-17"
     :Statement
     [{:Effect "Allow"
       :Principal {:Federated "cognito-identity.amazonaws.com"}
       :Action "sts:AssumeRoleWithWebIdentity"
       :Condition
       {:StringEquals {"cognito-identity.amazonaws.com:aud" {:Ref :IdentityPool}}
        :ForAnyValue:StringLike {"cognito-identity.amazonaws.com:amr" "unauthenticated"}}}]}
    :ManagedPolicyArns []
    :Policies []}})

(defn resource-identity-pool-role-attachment []
  {:Type "AWS::Cognito::IdentityPoolRoleAttachment"
   :Properties
   {:IdentityPoolId {:Ref :IdentityPool}
    :Roles
    {:authenticated {"Fn::GetAtt" [:IdentityPoolRoleAuthenticated :Arn]}
     :unauthenticated {"Fn::GetAtt" [:IdentityPoolRoleUnauthenticated :Arn]}}}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix :CertificateArn])

    :Conditions
    {:IsPrd {"Fn::Equals" [{"Ref" "Env"} "prd"]}}

    :Resources
    {:UserPool (resource-user-pool)
     :UserPoolDomain (resource-user-pool-domain)
     :UserPoolClient (resource-user-pool-client)
     :IdentityPool (resource-identity-pool)
     :IdentityPoolRoleAuthenticated (resource-identity-pool-role-authenticated)
     :IdentityPoolRoleUnauthenticated (resource-identity-pool-role-unauthenticated)
     :IdentityPoolRoleAttachment (resource-identity-pool-role-attachment)}

    :Outputs
    (a.cfn/list-outputs
     {:UserPool {:Ref :UserPool}
      :UserPoolDomain {:Ref :UserPoolDomain}
      :UserPoolClient {:Ref :UserPoolClient}
      :IdentityPool {:Ref :IdentityPool}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cognito.json")
        stack-name (str (-> param :prefix) "-" "cognito")]
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
                   (format "Env=\"%s\" Prefix=\"%s\" CertificateArn=\"%s\""
                           (-> param :env)
                           (-> param :prefix)
                           (-> param :certificate-arn)))))
