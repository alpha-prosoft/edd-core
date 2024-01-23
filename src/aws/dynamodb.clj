(ns aws.dynamodb
  (:require [clojure.tools.logging :as log]
            [lambda.util :as util]
            [clojure.set :as clojure-set]
            [clojure.string :as string]
            [sdk.aws.common :as common]))

(defn try-parse-message
  [{:keys [message]
    :as resp}]
  (cond-> resp
    (string/includes? message
                      "ConditionalCheckFailed")
    (assoc :key :concurrent-modification)))

(defn make-request
  [{:keys [aws action body]}]
  (log/info (format "Make request: %s" (:TableName body)))
  (let [req {:method     "POST"
             :uri        "/"
             :query      ""
             :payload    (util/to-json body)
             :headers    {"X-Amz-Target"         (str "DynamoDB_20120810." action)
                          "Host"                 (str "dynamodb." (:region aws) ".amazonaws.com")
                          "Content-Type"         "application/x-amz-json-1.0"
                          "X-Amz-Security-Token" (:aws-session-token aws)
                          "X-Amz-Date"           (common/create-date)}
             :service    "dynamodb"
             :region     (:region aws)
             :access-key (:aws-access-key-id aws)
             :secret-key (:aws-secret-access-key aws)}
        auth (common/authorize req)
        response (common/retry
                  #(util/http-request
                    (str "https://" (get (:headers req) "Host") "/")
                    {:method :post
                     :body    (:payload req)
                     :headers (-> (:headers req)
                                  (dissoc "Host")
                                  (assoc "Authorization" auth))
                     :timeout 5000})
                  3)]

    (when (contains? response :error)
      (throw (ex-info "Invocation error" (try-parse-message
                                          (:error response)))))
    (when (> (:status response) 399)
      (throw (ex-info "Invocation status error" (try-parse-message
                                                 (clojure-set/rename-keys
                                                  (:body response)
                                                  {:Message :message})))))
    (:body response)))

(defn list-tables
  [ctx]
  (make-request
   (assoc ctx
          :action "ListTables"
          :body {})))
