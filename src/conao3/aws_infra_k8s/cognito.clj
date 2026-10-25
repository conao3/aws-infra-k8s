(ns conao3.aws-infra-k8s.cognito
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [conao3.aws-infra-k8s.sanrecap-extension :as c.sanrecap-extension]
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
  "ホスト UI のカスタムドメイン (<env>-auth-k8s.sancode.dev、prd は auth-k8s.sancode.dev)。
   us-east-1 の ACM 証明書 (`deploy certificate`) が要る。"
  []
  {:Type "AWS::Cognito::UserPoolDomain"
   :Properties
   {:Domain {:Ref :DomainName}
    :CustomDomainConfig
    {:CertificateArn {:Ref :CertificateArn}}
    :UserPoolId {:Ref :UserPool}}})

(defn resource-user-pool-identity-provider-google []
  {:Type "AWS::Cognito::UserPoolIdentityProvider"
   :Properties
   {:ProviderName "Google"
    :ProviderType "Google"
    :UserPoolId {:Ref :UserPool}
    :ProviderDetails
    {:authorize_scopes "email openid profile"
     ;; SSM Parameter Store (String) の動的参照。値はコマンドラインにもパラメータにも出ない。
     ;; ssm-secure (SecureString) は UserPoolIdentityProvider に使えないので平文の String を読む
     :client_id (sub "{{resolve:ssm:/${Prefix}/cognito/google-client-id}}")
     :client_secret (sub "{{resolve:ssm:/${Prefix}/cognito/google-client-secret}}")}
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

(defn resource-user-pool-client-sanrecap
  "sanrecap の Chrome 拡張とアカウントページ用。認可コード + PKCE (シークレットなし)。"
  []
  {:Type "AWS::Cognito::UserPoolClient"
   :DependsOn ["UserPoolIdentityProviderGoogle"]
   :Properties
   {:ClientName (a.cfn/prefix "user-pool-client-sanrecap")
    :UserPoolId {:Ref :UserPool}
    :GenerateSecret false
    :ExplicitAuthFlows ["ALLOW_ADMIN_USER_PASSWORD_AUTH"
                        "ALLOW_REFRESH_TOKEN_AUTH"]
    :PreventUserExistenceErrors "ENABLED"
    :EnableTokenRevocation true
    ;; chrome.identity.launchWebAuthFlow のリダイレクト先 (拡張機能 ID ごと) と、API が配信するアカウントページ
    :CallbackURLs (conj (c.sanrecap-extension/callback-urls) c.sanrecap-extension/account-url)
    :LogoutURLs (conj (c.sanrecap-extension/callback-urls) c.sanrecap-extension/account-url)
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
     (a.cfn/list-string-parameters [:Env :Prefix :DomainName :CertificateArn]))

    :Conditions
    {:IsPrd {"Fn::Equals" [{"Ref" "Env"} "prd"]}}

    :Resources
    {:UserPool (resource-user-pool)
     :UserPoolDomain (resource-user-pool-domain)
     :UserPoolIdentityProviderGoogle (resource-user-pool-identity-provider-google)
     :UserPoolClient (resource-user-pool-client)
     :UserPoolClientSanrecap (resource-user-pool-client-sanrecap)
     :IdentityPool (resource-identity-pool)
     :IdentityPoolRoleAuthenticated (resource-identity-pool-role-authenticated)
     :IdentityPoolRoleUnauthenticated (resource-identity-pool-role-unauthenticated)
     :IdentityPoolRoleAttachment (resource-identity-pool-role-attachment)}

    :Outputs
    (a.cfn/list-outputs
     {:UserPool {:Ref :UserPool}
      :UserPoolArn (get-att :UserPool :Arn)
      ;; ホスト UI のドメイン (カスタムドメイン)
      :UserPoolDomain {:Ref :DomainName}
      :UserPoolClient {:Ref :UserPoolClient}
      :SanrecapUserPoolClient {:Ref :UserPoolClientSanrecap}
      :IdentityPool {:Ref :IdentityPool}})}))

(defn- certificate-arn [param]
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
        stack-name (str (-> param :prefix) "-" "cognito")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--lint" "--template-file" (str (fs/path file)))
    (c.util/ensure-stack-deployable stack-name)
    ;; Google の client_id / client_secret は、テンプレートが SSM パラメータ
    ;; `/{prefix}/cognito/google-client-{id,secret}` の動的参照で受けるので、ここでは取得しない
    (let [overrides {:Env (-> param :env)
                     :Prefix (-> param :prefix)
                     :DomainName (-> param :domain-name)
                     :CertificateArn (certificate-arn param)}]
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
