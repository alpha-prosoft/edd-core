(ns lambda.elastic
  (:require
   [lambda.http-client :as client]
   [lambda.util :as util]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [sdk.aws.common :as common]))

(defn get-env
  [name & [default]]
  (get (System/getenv) name default))

(defn query
  [{:keys [method path body view-store aws]} & {:keys [ignored-status]}]
  (let [elastic-search (get-in view-store [:config])
        elastic-auth (util/get-env "ELASTIC_AUTH")
        req (cond-> {:method     method
                     :uri        path
                     :query      ""
                     :headers    {"Host"         (:url elastic-search)
                                  "Content-Type" "application/json"
                                  "X-Amz-Date" (common/create-date)}
                     :service    "es"
                     :region     (:region aws)
                     :access-key (:aws-access-key-id aws)
                     :secret-key (:aws-secret-access-key aws)}
              body (assoc :payload body))
        auth (cond
               elastic-auth elastic-auth
               aws (common/authorize req)
               :else "Basic YWRtaW46YWRtaW4=")
        request (cond-> {:headers   (-> (:headers req)
                                        (dissoc "Host")
                                        (assoc
                                         "X-Amz-Security-Token" (:aws-session-token aws)
                                         "Authorization" auth))
                         :keepalive 300000}
                  body (assoc :body body))
        url (str (or (:scheme elastic-search) "https")
                 "://"
                 (get (:headers req) "Host")
                 (:uri req))
        response (client/retry-n
                  #(let [request (client/request->with-timeouts
                                  %
                                  request
                                  :idle-timeout 20000)
                         request (assoc request
                                        :method (-> method
                                                    string/lower-case
                                                    keyword))]
                     (util/http-request
                      url
                      request
                      :raw true)))]

    (cond
      (contains? response :error) (do
                                    (log/error "Failed update" response)
                                    {:error {:error response}})
      (= (:status response) ignored-status) nil
      (> (:status response) 299) {:error {:message (:body response)
                                          :status  (:status response)}}
      :else (util/to-edn (:body response)))))
