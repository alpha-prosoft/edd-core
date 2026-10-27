(ns edd.el.query
  (:require [lambda.util :as util]
            [lambda.request :as req]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [edd.ctx :as edd-ctx]
            [edd.schema :as schema]))

(defn call-query-fn
  [ctx cmd query-fn]
  (if (fn? query-fn)
    (query-fn ctx cmd)
    query-fn))

(defn- service-query? [{:keys [query-id ref]}]
  (and (some? query-id) (nil? ref)))

(defn- service-rtf? [{:keys [query-id ref]}]
  (and (nil? query-id) (some? ref)))

(defn with-cache
  [f & params]
  (if (req/is-scoped)
    (if-let [hint
             (get-in
              @req/*request*
              [:edd-core :http-cache params])]
      hint
      (do
        (swap! req/*request*
               #(assoc-in % [:edd-core
                             :http-cache
                             params]
                          (apply f params)))
        (get-in
         @req/*request*
         [:edd-core :http-cache params])))
    (apply f params)))

(defn- resolve-with-resolver
  [ctx request]
  (let [{:keys [resolver] :as config}
        (edd-ctx/deps-resolver ctx)]
    (when-not resolver
      (throw (ex-info "No deps resolver registered" {:service (:service request)})))
    ((:resolve resolver) ctx config request)))

(defn resolve-remote-dependency
  "Builds the dependency request {:service :query :meta :request-id
   :interaction-id} and resolves it through the registered deps resolver,
   which answers {:result value} or {:error error}. A query fn returning
   neither :query-id nor :ref skips the dependency."
  [ctx cmd {:keys [service query]}]
  (util/d-time
   (format "Resolving remote dependency: %s %s" service (:cmd-id cmd))
   (let [resolved-query
         (call-query-fn ctx cmd query)]
     (if (or (service-query? resolved-query)
             (service-rtf? resolved-query))
       (let [request
             {:service service
              :query resolved-query
              :meta (:meta ctx)
              :request-id (:request-id ctx)
              :interaction-id (:interaction-id ctx)}

             {:keys [result error]}
             (with-cache #(resolve-with-resolver ctx %) request)]
         (when error
           (throw (ex-info (str "Error resolving dependency from " service)
                           {:error (merge {:to-service service
                                           :from-service (:service-name ctx)
                                           :query-id (:query-id resolved-query)
                                           :ref (:ref resolved-query)}
                                          error)})))
         result)
       (do
         (log/info "Skipping remote dependency, query has no query-id or ref")
         nil)))))

(defn- validate-response-schema-setting [ctx]
  (let [setting (or (:response-schema-validation ctx)
                    (:response-schema-validation (:meta ctx)))]
    (assert #{nil :log-on-error :throw-on-error} setting)
    setting))

(defn- validate-response-schema? [ctx]
  (contains? #{:log-on-error :throw-on-error}
             (validate-response-schema-setting ctx)))

(defn- maybe-validate-response [ctx produces response]
  (when (and produces
             (validate-response-schema? ctx))
    (let [wrapped {:result response}]
      (when-not (m/validate produces wrapped)
        (let [error (schema/explain-error produces wrapped)]
          (condp = (validate-response-schema-setting ctx)
            :throw-on-error
            (throw (ex-info "Invalid response" {:error    error
                                                :response wrapped}))
            :log-on-error
            (log/warnf "Invalid response %s" (pr-str {:error error
                                                      :response wrapped}))))))))

(declare handle-query)

(defn resolve-local-dependency
  [ctx cmd query-fn]
  (util/d-time
   "Resolving local dependency"
   (let [query (call-query-fn ctx cmd query-fn)]
     (when query
       (let [ctx (assoc-in ctx [:edd-core :dependency-resolution] true)
             resp (handle-query ctx {:query query})]
         (if (:error resp)
           (throw (ex-info "Failed to resolve local deps" {:error resp}))
           resp))))))

(defn fetch-dependencies-for-deps
  "
  Return a copy of context with the resolved deps
  added into it.
  "
  [ctx deps request]
  (let [deps (if (vector? deps)
               (partition 2 deps)
               deps)]
    (reduce
     (fn [acc [key req]]
       (if (contains? acc key)
         acc ;; skip if exists
         (util/d-time
          (format "Query for dependency %s %s" key (:service req "locally"))
          (if-let [dep-value
                   (try (if (:service req)
                          (resolve-remote-dependency
                           acc
                           request
                           req)
                          (resolve-local-dependency
                           acc
                           request
                           req))
                        (catch AssertionError _e
                          (log/warnf "Assertion error for deps: %s" key)
                          nil))]
            (assoc acc key dep-value)
            acc))))
     ctx
     deps)))

(defn- deps->pairs
  [deps]
  (if (vector? deps)
    (partition 2 deps)
    (seq deps)))

(defn merge-feature-deps
  "Merges registration deps with the deps of every registered feature
  into one resolution list, registration deps first. Key conflicts are
  rejected at init-features, so the merge is unambiguous."
  [ctx deps]
  (concat
   (deps->pairs deps)
   (mapcat
    (fn [feature]
      (deps->pairs (:deps feature)))
    (edd-ctx/get-features ctx))))

(defn- dependency-resolution?
  [ctx]
  (true? (get-in ctx [:edd-core :dependency-resolution])))

(defn handle-query
  [ctx body]
  (let [query (:query body)
        query-id (keyword (:query-id query))

        {:keys [consumes
                handler
                produces]} (get-in ctx [:edd-core :queries query-id])]

    (util/d-metrics ctx [:query (or query-id :unknown)]
                    (do
                      (log/debugf "Handling query: %s" query-id)
                      (when-not handler
                        (throw (ex-info "No handler found"
                                        {:error    "No handler found"
                                         :query-id query-id})))
                      (when-not (m/validate consumes query)
                        (throw (ex-info "Invalid request"
                                        {:error (schema/explain-error consumes query)})))
                      (let [{:keys [deps]} (edd-ctx/get-query ctx query-id)
                            for-dependency (dependency-resolution? ctx)
                            deps (if for-dependency
                                   deps
                                   (merge-feature-deps ctx deps))
                            ctx (fetch-dependencies-for-deps ctx deps query)
                            invoke-handler (fn [ctx query]
                                             (util/d-time
                                              (str "handling-query: " query-id)
                                              (handler ctx query)))
                            resp (if for-dependency
                                   (invoke-handler ctx query)
                                   (edd-ctx/run-filters ctx
                                                        :query-filter
                                                        invoke-handler
                                                        query))]
                        (log/debug "Query response" resp)
                        (maybe-validate-response ctx produces resp)
                        resp)))))
