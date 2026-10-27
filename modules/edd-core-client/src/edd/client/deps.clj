(ns edd.client.deps
  "HTTP deps resolver: resolves remote deps by calling the /query endpoint of
   the target service (or the content service for :ref deps), authorized by a
   credentials provider (edd.client.credentials)."
  (:require [edd.client.credentials :as credentials]
            [edd.core :as edd]
            [edd.ctx :as edd-ctx]
            [lambda.http-client :as http-client]
            [lambda.util :as util]))

(defn query-url
  [service {:keys [realm]}]
  (str (or (util/get-env "ApiUrl")
           (str "https://api." (util/get-env "PrivateHostedZoneName")))
       "/private/"
       (if (= :test realm) "test" "prod")
       "/"
       (name service)
       "/query"))

(defn rtf-url
  [service {:keys [ref]}]
  (str "https://glms-content-svc."
       (util/get-env "PrivateHostedZoneName")
       "/load/"
       (name service)
       "/"
       ref))

(defn authorize
  "Adds the credentials of the registered resolver to an HTTP request."
  [ctx request]
  (let [{:keys [credentials credentials-state]}
        (edd-ctx/deps-resolver ctx)]
    (if credentials
      ((:authorize credentials) ctx credentials-state request)
      request)))

(defn- query-request
  [ctx {:keys [query-url]} {:keys [service query meta request-id interaction-id]}]
  (let [request
        {:body (util/to-json {:query query
                              :meta meta
                              :request-id request-id
                              :interaction-id interaction-id})
         :headers {"Content-Type" "application/json"}}]
    (http-client/retry-n
     #(util/http-post (query-url service meta)
                      (http-client/request->with-timeouts
                       %
                       (authorize ctx request)
                       :idle-timeout 10000))
     :meta {:to-service service
            :from-service (:service-name ctx)
            :query-id (:query-id query)})))

(defn- rtf-request
  [ctx {:keys [rtf-url]} {:keys [service query]}]
  (http-client/retry-n
   #(util/http-get (rtf-url service query)
                   (http-client/request->with-timeouts
                    %
                    (authorize ctx {:headers {"Accept" "*/*"}})
                    :idle-timeout 50000)
                   :raw true)
   :meta {:to-service service
          :from-service (:service-name ctx)
          :ref (:ref query)}))

(defn- ->response
  [service query {:keys [status body error]}]
  (cond
    error
    {:error {:message error}}

    (or (:error body) (:exception body))
    {:error {:message {:response body
                       :error-source service}}}

    (> (long (or status 0)) 299)
    {:error {:status status
             :message (str "Response status:" status)}}

    (:query-id query)
    {:result (:result body)}

    :else
    {:result body}))

(defn- resolve-dep
  [ctx config {:keys [service query] :as request}]
  (->response service
              query
              (if (:query-id query)
                (query-request ctx config request)
                (rtf-request ctx config request))))

(defn- init
  [ctx {:keys [credentials] :as config}]
  (assoc config :credentials-state ((:init credentials (constantly nil)) ctx)))

(def resolver
  {:init init
   :resolve resolve-dep})

(defn register
  "Resolves remote deps over HTTP. Config: :credentials (default
   edd.client.credentials/env), :query-url (fn [service meta]),
   :rtf-url (fn [service query])."
  ([ctx]
   (register ctx {}))
  ([ctx config]
   (edd/reg-deps-resolver ctx (merge {:resolver resolver
                                      :credentials (credentials/env)
                                      :query-url query-url
                                      :rtf-url rtf-url}
                                     config))))
