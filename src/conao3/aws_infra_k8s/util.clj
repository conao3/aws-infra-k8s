(ns conao3.aws-infra-k8s.util 
  (:require
    [babashka.process :as process]))

(defn eprintln [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn eshell [& args]
  (let [[opts & rest] args]
    (if (map? opts)
      (apply eprintln rest)
      (apply eprintln args)))
  (apply process/shell args))
