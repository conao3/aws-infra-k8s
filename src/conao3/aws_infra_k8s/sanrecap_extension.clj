(ns conao3.aws-infra-k8s.sanrecap-extension
  "sanrecap の Chrome 拡張 ID。cognito (アプリクライアントのコールバック URL) と
  sanrecap (CORS の許可オリジン) が同じ一覧を使う。")

;; 1 つ目は manifest の `key` で固定した開発用の ID (sanrecap の README)、
;; 2 つ目は Chrome ウェブストアに登録した拡張機能 (投稿者 Sancode) の ID。
(def extension-ids
  ["afdhfhegibphoakedhmbheihahnnipef"
   "fjdpjogbcgikfchgmohbgfghildfghci"])

(defn callback-urls []
  (mapv #(format "https://%s.chromiumapp.org/" %) extension-ids))

(defn origins []
  (mapv #(str "chrome-extension://" %) extension-ids))

;; API 自身が配信するアカウントページ (GET /account)。ログインのコールバック URL になる。
;; 条件式 `IsPrd` (パラメータ Env) が要る。ドメインは aws_infra_k8s.clj の sanrecap-domain と同じ
(def account-url
  {"Fn::If" ["IsPrd"
             "https://sanrecap.sancode.dev/account"
             "https://dev-sanrecap.sancode.dev/account"]})
