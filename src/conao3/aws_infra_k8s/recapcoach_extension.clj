(ns conao3.aws-infra-k8s.recapcoach-extension
  "recapcoach の Chrome 拡張 ID。cognito (アプリクライアントのコールバック URL) と
  recapcoach (CORS の許可オリジン) が同じ一覧を使う。")

;; 1 つ目は manifest の `key` で固定した開発用の ID (ts-recapcoach の README)、
;; 2 つ目は Chrome ウェブストアに登録した拡張機能 (投稿者 Sancode) の ID。
(def extension-ids
  ["afdhfhegibphoakedhmbheihahnnipef"
   "fjdpjogbcgikfchgmohbgfghildfghci"])

(defn callback-urls []
  (mapv #(format "https://%s.chromiumapp.org/" %) extension-ids))

(defn origins []
  (mapv #(str "chrome-extension://" %) extension-ids))

;; sancode.dev のアカウントページ。dev は手元で配信して確かめる (localhost)。
;; どちらも CloudFormation の条件式 `IsPrd` (パラメータ Env) が要る
(def web-origin
  {"Fn::If" ["IsPrd" "https://sancode.dev" "http://localhost:8788"]})

(def web-callback-url
  {"Fn::If" ["IsPrd"
             "https://sancode.dev/services/recapcoach/account"
             "http://localhost:8788/services/recapcoach/account"]})
