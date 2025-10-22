(ns conao3.aws-infra.cfn)

(defn template [m]
  (merge
   {:AWSTemplateFormatVersion "2010-09-09"
    :Transform "AWS::Serverless-2016-10-31"}
   m))

(defn prefix [m]
  {"Fn::Sub" (format "${Prefix}-%s" m)})

(defn tag-name [m]
  (-> (dissoc m :TagName)
      (update :Tags conj {:Key "Name"
                          :Value (:TagName m)})))
