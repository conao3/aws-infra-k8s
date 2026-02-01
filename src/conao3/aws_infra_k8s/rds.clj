(ns conao3.aws-infra-k8s.rds
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [conao3.aws-infra-k8s.util :as c.util]
   [conao3.aws-infra.cfn :as a.cfn]))

(defn resource-db-subnet-group []
  {:Type "AWS::RDS::DBSubnetGroup"
   :Properties
   {:DBSubnetGroupName (a.cfn/prefix "rds")
    :DBSubnetGroupDescription "Subnet group for RDS cluster"
    :SubnetIds [{:Ref :SubnetDualA}
                {:Ref :SubnetDualC}
                {:Ref :SubnetDualD}]}})

(defn resource-db-cluster-parameter-group []
  {:Type "AWS::RDS::DBClusterParameterGroup"
   :Properties
   {:Description "Aurora PostgreSQL cluster parameter group"
    :Family "aurora-postgresql17"
    :Parameters {}}})

(defn resource-db-parameter-group []
  {:Type "AWS::RDS::DBParameterGroup"
   :Properties
   {:Description "Aurora PostgreSQL parameter group"
    :Family "aurora-postgresql17"
    :Parameters {}}})

(defn resource-db-cluster []
  {:Type "AWS::RDS::DBCluster"
   :Properties
   {:Engine "aurora-postgresql"
    :EngineVersion "17.7"
    :DatabaseName "postgres"
    :MasterUsername "postgres"
    :MasterUserPassword {"Fn::Sub" "{{resolve:secretsmanager:${Secret}:SecretString:rds-postgres-password}}"}
    :DBClusterIdentifier (a.cfn/prefix "rds")
    :DBSubnetGroupName {:Ref :DBSubnetGroup}
    :VpcSecurityGroupIds [{:Ref :SecurityGroupRds}]
    :DBClusterParameterGroupName {:Ref :DBClusterParameterGroup}
    :StorageEncrypted true
    :BackupRetentionPeriod 7
    :PreferredBackupWindow "03:00-04:00"
    :PreferredMaintenanceWindow "mon:04:00-mon:05:00"}})

(defn resource-db-instance [name az]
  {:Type "AWS::RDS::DBInstance"
   :Properties
   {:Engine "aurora-postgresql"
    :DBClusterIdentifier {:Ref :DBCluster}
    :DBInstanceClass "db.t4g.medium"
    :DBInstanceIdentifier (a.cfn/prefix name)
    :DBParameterGroupName {:Ref :DBParameterGroup}
    :AvailabilityZone az
    :PubliclyAccessible false}})

(defn cfn [_param]
  (a.cfn/template
   {:Parameters
    (a.cfn/list-string-parameters
     [:Env :Prefix
      :Secret
      :SubnetDualA
      :SubnetDualC
      :SubnetDualD
      :SecurityGroupRds])

    :Resources
    {:DBSubnetGroup (resource-db-subnet-group)
     :DBClusterParameterGroup (resource-db-cluster-parameter-group)
     :DBParameterGroup (resource-db-parameter-group)
     :DBCluster (resource-db-cluster)
     :DBInstanceA (resource-db-instance "rds-a" "ap-northeast-1a")
     ;; :DBInstanceC (resource-db-instance "rds-c" "ap-northeast-1c")
     }

    :Outputs
    (a.cfn/list-outputs
     {:DBCluster {:Ref :DBCluster}
      :DBClusterEndpoint {"Fn::GetAtt" [:DBCluster :Endpoint.Address]}
      :DBClusterReadEndpoint {"Fn::GetAtt" [:DBCluster :ReadEndpoint.Address]}})}))

(defn deploy [param]
  (let [file (fs/file "target/cfn/rds.json")
        stack-name (str (-> param :prefix) "-" "rds")]
    (fs/create-dirs (fs/parent file))

    (c.util/eprintln (format "Write: %s" (fs/path file)))
    (with-open [writer (io/writer file)]
      (-> (cfn param)
          (json/generate-stream writer)))

    (c.util/eshell "sam" "validate" "--template-file" (str (fs/path file)))
    (let [exports (->> (-> (c.util/eshell {:out :string} "aws" "cloudformation" "list-exports")
                           :out
                           (json/parse-string keyword))
                       :Exports
                       (map (fn [elm] [(keyword (:Name elm)) (:Value elm)]))
                       (into {}))]
      (c.util/ensure-stack-deployable stack-name)
      (c.util/eshell "sam" "deploy"
                     "--template-file" (str (fs/path file))
                     "--stack-name" stack-name
                     "--capabilities" "CAPABILITY_NAMED_IAM"
                     "--resolve-s3"
                     "--no-fail-on-empty-changeset"
                     "--on-failure" "DELETE"
                     "--parameter-overrides"
                     (->> {:Env (-> param :env)
                           :Prefix (-> param :prefix)
                           :Secret (str (-> param :prefix) "-secret")
                           :SubnetDualA (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetDualA))))
                           :SubnetDualC (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetDualC))))
                           :SubnetDualD (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SubnetDualD))))
                           :SecurityGroupRds (get exports (keyword (format "%s-%s" (-> param :prefix) (name :SecurityGroupRds))))}
                          (map (fn [[k v]]
                                 (format "%s=\"%s\"" (name k) v)))
                          (str/join " "))))))
