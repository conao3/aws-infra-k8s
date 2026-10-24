(ns conao3.aws-infra-k8s.recapcoach-extension
  "recapcoach の Chrome 拡張 ID。cognito (アプリクライアントのコールバック URL) と
  recapcoach (CORS の許可オリジン) が同じ一覧を使う。")

;; manifest の `key` で固定した開発用の ID (ts-recapcoach の README)。
;; Chrome ウェブストアで公開すると別の ID になるので、そのときここに足す。
(def extension-ids
  ["afdhfhegibphoakedhmbheihahnnipef"])

(defn callback-urls []
  (mapv #(format "https://%s.chromiumapp.org/" %) extension-ids))

(defn origins []
  (mapv #(str "chrome-extension://" %) extension-ids))
