(ns conao3.aws-infra-k8s.cognito
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [conao3.aws-infra-k8s.recapcoach-extension :as c.recapcoach-extension]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn- sub [s] {"Fn::Sub" s})
(defn- get-att [resource attr] {"Fn::GetAtt" [resource attr]})

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
                           :Priority 1}]}
    ;; ネイティブのセルフサインアップは禁止。テスト用のユーザーは admin-create-user で作る。
    ;; Google の連携ユーザーは初回サインインで作られるので、許可はアプリ側で判定する
    :AdminCreateUserConfig {:AllowAdminCreateUserOnly true}}})

(defn resource-user-pool-domain
  "prd はカスタムドメイン (ACM が要る)。"
  []
  {:Condition "IsPrd"
   :Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain "auth-k8s.sancode.dev"
    :CustomDomainConfig
    {:CertificateArn {"Ref" "CertificateArn"}}
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-domain-prefix
  "prd 以外は Cognito のプレフィックスドメイン (<prefix>-auth-<アカウント ID>.auth.<region>.amazoncognito.com)。"
  []
  {:Condition "IsNotPrd"
   :Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain (sub "${Prefix}-auth-${AWS::AccountId}")
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-identity-provider-google []
  {:Type "AWS::Cognito::UserPoolIdentityProvider"
   :Properties
   {:ProviderName "Google"
    :ProviderType "Google"
    :UserPoolId {:Ref :UserPool}
    :ProviderDetails
    {:authorize_scopes "email openid profile"
     ;; Secrets Manager の動的参照。値はコマンドラインにもパラメータにも出ない
     :client_id (sub "{{resolve:secretsmanager:${Prefix}-secret:SecretString:google-client-id}}")
     :client_secret (sub "{{resolve:secretsmanager:${Prefix}-secret:SecretString:google-client-secret}}")}
    :AttributeMapping
    {:email "email"
     :email_verified "email_verified"
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

(defn resource-user-pool-client-recapcoach
  "recapcoach の Chrome 拡張用。認可コード + PKCE (シークレットなし)。"
  []
  {:Type "AWS::Cognito::UserPoolClient"
   :DependsOn ["UserPoolIdentityProviderGoogle"]
   :Properties
   {:ClientName (a.cfn/prefix "user-pool-client-recapcoach")
    :UserPoolId {:Ref :UserPool}
    :GenerateSecret false
    :ExplicitAuthFlows ["ALLOW_ADMIN_USER_PASSWORD_AUTH"
                        "ALLOW_REFRESH_TOKEN_AUTH"]
    :PreventUserExistenceErrors "ENABLED"
    :EnableTokenRevocation true
    ;; chrome.identity.launchWebAuthFlow のリダイレクト先 (拡張機能 ID ごと)
    :CallbackURLs (c.recapcoach-extension/callback-urls)
    :LogoutURLs (c.recapcoach-extension/callback-urls)
    :AllowedOAuthFlows ["code"]
    :AllowedOAuthScopes ["email" "openid" "profile"]
    :AllowedOAuthFlowsUserPoolClient true
    :SupportedIdentityProviders ["COGNITO" "Google"]
    :TokenValidityUnits {:IdToken "minutes" :AccessToken "minutes" :RefreshToken "days"}
    :IdTokenValidity 60
    :AccessTokenValidity 60
    :RefreshTokenValidity 30}})

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
    (merge
     (a.cfn/list-string-parameters [:Env :Prefix])
     ;; prd のカスタムドメインの証明書。prd 以外は使わない
     {:CertificateArn {:Type "String" :Default ""}})

    :Conditions
    {:IsPrd {"Fn::Equals" [{"Ref" "Env"} "prd"]}
     :IsNotPrd {"Fn::Not" [{:Condition "IsPrd"}]}}

    :Resources
    {:UserPool (resource-user-pool)
     :UserPoolDomain (resource-user-pool-domain)
     :UserPoolDomainPrefix (resource-user-pool-domain-prefix)
     :UserPoolIdentityProviderGoogle (resource-user-pool-identity-provider-google)
     :UserPoolClient (resource-user-pool-client)
     :UserPoolClientRecapcoach (resource-user-pool-client-recapcoach)
     :IdentityPool (resource-identity-pool)
     :IdentityPoolRoleAuthenticated (resource-identity-pool-role-authenticated)
     :IdentityPoolRoleUnauthenticated (resource-identity-pool-role-unauthenticated)
     :IdentityPoolRoleAttachment (resource-identity-pool-role-attachment)}

    :Outputs
    (a.cfn/list-outputs
     {:UserPool {:Ref :UserPool}
      :UserPoolArn (get-att :UserPool :Arn)
      ;; ホスト UI のドメイン。prd はカスタムドメイン、それ以外はプレフィックスドメイン
      :UserPoolDomain {"Fn::If" ["IsPrd"
                                 "auth-k8s.sancode.dev"
                                 (sub "${Prefix}-auth-${AWS::AccountId}.auth.${AWS::Region}.amazoncognito.com")]}
      :UserPoolClient {:Ref :UserPoolClient}
      :RecapcoachUserPoolClient {:Ref :UserPoolClientRecapcoach}
      :IdentityPool {:Ref :IdentityPool}})}))

(defn- prd-certificate-arn [param]
  (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports" "--region" "us-east-1")
                         :out
                         (json/parse-string keyword))
                     :Exports
                     (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                     (into {}))]
    (or (get exports (keyword (format "%s-%s" (-> param :prefix) "CertificateArn")))
        (throw (ex-info "CertificateArn export not found in us-east-1 (deploy certificate first)"
                        {:prefix (:prefix param)})))))

(defn deploy [param]
  (let [file (fs/file "target/cfn/cognito.json")
        stack-name (str (-> param :prefix) "-" "cognito")
        prd? (= "prd" (:env param))]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    ;; Google の client_id / client_secret は、テンプレートが Secrets Manager `{prefix}-secret` の
    ;; 動的参照 (google-client-id / google-client-secret) で受けるので、ここでは取得しない
    (let [overrides (cond-> {:Env (-> param :env)
                             :Prefix (-> param :prefix)}
                      prd? (assoc :CertificateArn (prd-certificate-arn param)))]
      (c.util/eshell "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (->> overrides
                          (map (fn [[k v]] (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
