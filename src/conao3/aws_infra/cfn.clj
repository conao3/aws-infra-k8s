(ns conao3.aws-infra.cfn)

(defn template [m]
  (merge
   {:AWSTemplateFormatVersion "2010-09-09"
    :Transform "AWS::Serverless-2016-10-31"}
   m))

(defn prefix [x]
  {"Fn::Sub" (format "${Prefix}-%s" (name x))})

(defn tag-name [m]
  (-> (dissoc m :TagName)
      (update :Tags conj {:Key "Name"
                          :Value (:TagName m)})))

(defn list-string-parameters [l]
  (->> l
       (map (fn [x]
              [x {:Type "String"}]))
       (into {})))

(defn list-outputs [m]
  (->> m
       (map (fn [[k v]]
              [k {:Value v
                  :Export {:Name (prefix k)}}]))
       (into {})))
