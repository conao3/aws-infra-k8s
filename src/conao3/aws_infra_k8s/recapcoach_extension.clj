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
