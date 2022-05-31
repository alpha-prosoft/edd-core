(ns edd.el.client
  (:require [lambda.util :as util]
            [lambda.http-client :as http-client]
            [edd.el.query :as query]
            [aws.aws :as aws]
            [clojure.tools.logging :as log]))

(defn calc-service-query-url
  [service]
  (str "https://api."
       (util/get-env "PrivateHostedZoneName")
       "/private/prod/"
       (name service)
       "/query"))

(defn call-query-fn
  [_ cmd query-fn deps]
  (query-fn deps cmd))

(defn resolve-remote-dependency
  [ctx cmd {:keys [service query]} deps]
  (log/info "Resolving remote dependency: " service (:cmd-id cmd))

  (let [query-fn query
        service-name (:service-name ctx)
        url (calc-service-query-url
             service)
        token (aws/get-token ctx)
        resolved-query (call-query-fn ctx cmd query-fn deps)
        response (when (or
                        (get-in resolved-query [:query-id])
                        (get-in resolved-query [:query :query-id]))
                   (http-client/retry-n
                    #(util/http-request
                      url
                      (http-client/request->with-timeouts
                       %
                       {:method :post
                        :body    (util/to-json
                                  {:query          resolved-query
                                   :meta           (:meta ctx)
                                   :request-id     (:request-id ctx)
                                   :interaction-id (:interaction-id ctx)})
                        :headers {"Content-Type"    "application/json"
                                  "X-Authorization" token}}
                       :idle-timeout 10000))
                    :meta {:to-service   service
                           :from-service service-name
                           :query-id     (:query-id resolved-query)}))]
    (when (:error response)
      (throw (ex-info (str "Error fetching dependency" service)
                      {:error {:to-service   service
                               :from-service service-name
                               :query-id     (:query-id resolved-query)
                               :message      (:error response)}})))
    (when (:error (get response :body))
      (throw (ex-info (str "Error response from service " service)
                      {:error {:to-service   service
                               :from-service service-name
                               :query-id     (:query-id resolved-query)
                               :message      {:response     (get response :body)
                                              :error-source service}}})))
    (if (> (:status response 0) 299)
      (throw (ex-info (str "Deps request error for " service)
                      {:error {:to-service   service
                               :from-service service-name
                               :service      service
                               :query-id     (:query-id resolved-query)
                               :status       (:status response)
                               :message      (str "Response status:" (:status response))}}))
      (get-in response [:body :result]))))

(defn resolve-local-dependency
  [ctx cmd query-fn deps]
  (log/debug "Resolving local dependency")
  (let [query (call-query-fn ctx cmd query-fn deps)]
    (when query
      (let [resp (query/handle-query ctx {:query query})]
        (if (:error resp)
          (throw (ex-info "Failed to resolve local deps" {:error resp}))
          resp)))))

(defn fetch
  [ctx deps request]
  (let [deps (if (vector? deps)
               (partition 2 deps)
               deps)
        dps-value (reduce
                   (fn [p [key req]]
                     (let [dep-value
                           (try (if (:service req)
                                  (resolve-remote-dependency
                                   ctx
                                   request
                                   req
                                   p)
                                  (resolve-local-dependency
                                   ctx
                                   request
                                   req
                                   p))
                                (catch AssertionError e
                                  (log/warn "Assertion error for deps " key)
                                  nil))]
                       (if dep-value
                         (assoc p key dep-value)
                         p)))
                   {}
                   deps)]
    dps-value))
