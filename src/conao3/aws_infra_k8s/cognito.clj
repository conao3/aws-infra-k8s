(ns conao3.aws-infra-k8s.cognito
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
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
  {:Condition "IsPrd"
  :Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain {"Fn::If" ["IsPrd"
                       "auth-k8s.sancode.dev"
                       {"Fn::Sub" "${Env}-auth-k8s.sancode.dev"}]}
    :CustomDomainConfig
    {:CertificateArn {"Ref" "CertificateArn"}}
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-identity-provider-google []
  {:Type "AWS::Cognito::UserPoolIdentityProvider"
   :Properties
   {:ProviderName "Google"
    :ProviderType "Google"
    :UserPoolId {:Ref :UserPool}
    :ProviderDetails
    {:authorize_scopes "email openid profile"
     :client_id {"Ref" "GoogleClientId"}
     :client_secret {"Ref" "GoogleClientSecret"}}
    :AttributeMapping
    {:email "email"
     :username "sub"}}})

(defn resource-user-pool-client []
  {:Type "AWS::Cognito::UserPoolClient"
   :DependsOn ["UserPoolIdentityProviderGoogle"]
   :Properties
   {:ClientName (a.cfn/prefix "user-pool-client")
    :UserPoolId {:Ref :UserPool}
    :GenerateSecret false
    :ExplicitAuthFlows ["ALLOW_USER_PASSWORD_AUTH"
                        "ALLOW_USER_SRP_AUTH"
                        "ALLOW_ADMIN_USER_PASSWORD_AUTH"
                        "ALLOW_REFRESH_TOKEN_AUTH"]
    :PreventUserExistenceErrors "ENABLED"
    :CallbackURLs {"Fn::If" ["IsPrd"
                             ["https://sanplan.sancode.dev/auth/callback"]
                             ["http://localhost:5173/auth/callback"
                              {"Fn::Sub" "https://${Env}-sanplan.sancode.dev/auth/callback"}]]}
    :LogoutURLs {"Fn::If" ["IsPrd"
                            ["https://sanplan.sancode.dev/login"]
                            ["http://localhost:5173/login"
                             {"Fn::Sub" "https://${Env}-sanplan.sancode.dev/login"}]]}
    :AllowedOAuthFlows ["code" "implicit"]
    :AllowedOAuthScopes ["email" "openid" "profile"]
    :AllowedOAuthFlowsUserPoolClient true
    :SupportedIdentityProviders ["COGNITO" "Google"]}})

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
     [:Env :Prefix :CertificateArn :GoogleClientId :GoogleClientSecret])

    :Conditions
    {:IsPrd {"Fn::Equals" [{"Ref" "Env"} "prd"]}}

    :Resources
    {:UserPool (resource-user-pool)
     :UserPoolDomain (resource-user-pool-domain)
     :UserPoolIdentityProviderGoogle (resource-user-pool-identity-provider-google)
     :UserPoolClient (resource-user-pool-client)
     :IdentityPool (resource-identity-pool)
     :IdentityPoolRoleAuthenticated (resource-identity-pool-role-authenticated)
     :IdentityPoolRoleUnauthenticated (resource-identity-pool-role-unauthenticated)
     :IdentityPoolRoleAttachment (resource-identity-pool-role-attachment)}

    :Outputs
    (a.cfn/list-outputs
     {:UserPool {:Ref :UserPool}
      :UserPoolDomain {"Fn::If" ["IsPrd"
                                 "auth-k8s.sancode.dev"
                                 {"Fn::Sub" "${Env}-auth-k8s.sancode.dev"}]}
      :UserPoolClient {:Ref :UserPoolClient}
      :IdentityPool {:Ref :IdentityPool}})}))

(defn- ensure-dev-user-pool-domain! [param cert-arn]
  (when-not (= "prd" (:env param))
    (let [domain (format "%s-auth-k8s.sancode.dev" (:env param))
          user-pool-id (-> (c.util/eshell {:out :string}
                                          "aws" "cloudformation" "describe-stacks"
                                          "--stack-name" (str (:prefix param) "-cognito")
                                          "--query" "Stacks[0].Outputs[?OutputKey==`UserPool`].OutputValue | [0]"
                                          "--output" "text")
                           :out
                           str/trim)
          out (c.util/eshell {:out :string :err :string}
                             "aws" "cognito-idp" "describe-user-pool-domain"
                             "--domain" domain)
          domain-description (some-> (:out out) not-empty (json/parse-string keyword) :DomainDescription)
          status (:Status domain-description)
          current-user-pool-id (:UserPoolId domain-description)]
      (cond
        (and (= current-user-pool-id user-pool-id) (not (str/blank? status)))
        (c.util/eprintln (format "Cognito custom domain already exists: %s (%s)" domain status))

        (and current-user-pool-id (not= current-user-pool-id user-pool-id))
        (throw (ex-info (format "Cognito custom domain %s is attached to another user pool: %s" domain current-user-pool-id)
                        {:domain domain
                         :current-user-pool-id current-user-pool-id
                         :expected-user-pool-id user-pool-id}))

        :else
        (do
          (c.util/eprintln (format "Create Cognito custom domain: %s" domain))
          (c.util/eshell "aws" "cognito-idp" "create-user-pool-domain"
                         "--domain" domain
                         "--user-pool-id" user-pool-id
                         "--custom-domain-config" (format "CertificateArn=%s" cert-arn)))))))

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
    (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "us-east-1")
                           :out
                           (json/parse-string keyword))
                       :Exports
                       (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                       (into {}))
          cert-arn (get exports (keyword (format "%s-%s" (-> param :prefix) "CertificateArn")))
          secret-json (-> (c.util/eshell {:out :string}
                                         "aws" "secretsmanager" "get-secret-value"
                                         "--secret-id" (format "%s-secret" (:prefix param))
                                         "--query" "SecretString"
                                         "--output" "text")
                          :out
                          (json/parse-string keyword))
          google-client-id (or (get secret-json :google-client-id)
                               (throw (ex-info "Missing google-client-id in Secrets Manager"
                                               {:secret-id (format "%s-secret" (:prefix param))})))
          google-client-secret (or (get secret-json :google-client-secret)
                                   (throw (ex-info "Missing google-client-secret in Secrets Manager"
                                                   {:secret-id (format "%s-secret" (:prefix param))})))]
      (c.util/eshell "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (format "Env=\"%s\" Prefix=\"%s\" CertificateArn=\"%s\" GoogleClientId=\"%s\" GoogleClientSecret=\"%s\""
                             (-> param :env)
                             (-> param :prefix)
                             cert-arn
                             google-client-id
                             google-client-secret))
      (ensure-dev-user-pool-domain! param cert-arn))))
