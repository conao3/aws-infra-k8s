(ns conao3.aws-infra-k8s.util
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]))

(defn eprintln [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn eshell [& args]
  (let [[opts & rest] args]
    (if (map? opts)
      (apply eprintln rest)
      (apply eprintln args)))
  (apply process/shell args))

(defn get-stack-status [stack-name]
  (->> (or (-> (eshell {:out :string :continue true} "aws" "cloudformation" "describe-stacks" "--stack-name" stack-name)
               :out
               (json/parse-string keyword)
               :Stacks)
           [])
       first
       :StackStatus))

(defn ensure-stack-deployable [stack-name]
  (let [status (get-stack-status stack-name)]
    (case status
      "UPDATE_ROLLBACK_FAILED"
      (do
        (eprintln (format "Stack %s is in UPDATE_ROLLBACK_FAILED state. Attempting to continue rollback..." stack-name))
        (eshell "aws" "cloudformation" "continue-update-rollback" "--stack-name" stack-name "--resources-to-skip" "")
        (eprintln "Waiting for rollback to complete...")
        (eshell "aws" "cloudformation" "wait" "stack-rollback-complete" "--stack-name" stack-name)
        (eprintln "Rollback complete. Stack is now deployable."))

      "ROLLBACK_COMPLETE"
      (do
        (eprintln (format "Stack %s is in ROLLBACK_COMPLETE state. Deleting stack..." stack-name))
        (eshell "aws" "cloudformation" "delete-stack" "--stack-name" stack-name)
        (eprintln "Waiting for stack deletion to complete...")
        (eshell "aws" "cloudformation" "wait" "stack-delete-complete" "--stack-name" stack-name)
        (eprintln "Stack deleted. Ready to deploy."))

      "DELETE_FAILED"
      (do
        (eprintln (format "Stack %s is in DELETE_FAILED state. This requires manual intervention." stack-name))
        (eprintln "Please check the stack in AWS Console and manually resolve the issue."))

      "DELETE_IN_PROGRESS"
      (do
        (eprintln "Waiting for stack deletion to complete...")
        (eshell "aws" "cloudformation" "wait" "stack-delete-complete" "--stack-name" stack-name))

      (eprintln (format "Stack %s status: %s" stack-name (or status "DOES_NOT_EXIST"))))))
