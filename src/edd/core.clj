(ns edd.core
  (:require [clojure.tools.logging :as log]
            [lambda.request :as request]
            [edd.el.cmd :as cmd]
            [edd.schema :as s]
            [edd.el.event :as event]
            [edd.el.query :as query]
            [lambda.util :as util]
            [edd.view-store.common :as view-store]
            [malli.error :as me]
            [malli.core :as m]
            [edd.dal :as dal]
            [edd.ctx :as edd-ctx]))

(def EddCoreRegCmd
  (m/schema
   [:map
    [:handler [:fn fn?]]
    [:id-fn [:fn fn?]]
    [:deps [:or
            [:map]
            [:vector :any]]]
    [:consumes
     [:fn #(m/schema? (m/schema %))]]]))

(defn dps->deps [dps]
  (let [dps (if (vector? dps) (partition 2 dps) dps)
        wrap-query (fn [query] (fn [d cmd] (query (merge cmd d))))]
    (vec (mapcat (fn [[key query]]
                   [key (if (:service query)
                          {:query   (wrap-query (:query query))
                           :service (:service query)}
                          (wrap-query query))])
                 dps))))

(defn reg-cmd
  [ctx cmd-id reg-fn & rest]
  (log/debug "Registering cmd" cmd-id)
  (let [input-options (reduce
                       (fn [c [k v]]
                         (assoc c k v))
                       {}
                       (partition 2 rest))
        options input-options
        ; For compatibility
        options (-> options
                    (dissoc :spec)
                    (assoc :consumes (:spec options
                                            (:consumes options))))
        options (-> options
                    (assoc :id-fn (:id-fn options
                                          (fn [_ _] nil))))
        ; For compatibility
        options (-> options
                    (dissoc :dps)
                    (assoc :deps (if (:dps options)
                                   (dps->deps (:dps options))
                                   (:deps options {}))))
        options (update options
                        :consumes
                        #(s/merge-cmd-schema % cmd-id))
        options (assoc options :handler reg-fn)]

    (when (:dps input-options)
      (log/warn ":dps is deprecated and will be removed in future"))
    (when (:spec input-options)
      (log/warn ":spec is deprecated and will be removed in future"))

    (when-not (m/validate EddCoreRegCmd options)
      (throw (ex-info "Invalid command registration"
                      {:explain (-> (m/explain EddCoreRegCmd options)
                                    (me/humanize))})))
    (edd-ctx/put-cmd ctx
                     :cmd-id cmd-id
                     :options options)))

(defn reg-event
  [ctx event-id reg-fn]
  (log/debug "Registering apply" event-id)
  (update ctx :def-apply
          #(assoc % event-id (when reg-fn
                               (fn [& rest]
                                 (apply reg-fn rest))))))

(defn reg-agg-filter
  [ctx reg-fn]
  (log/debug "Registering aggregate filter")
  (assoc ctx :agg-filter
         (conj
          (get ctx :agg-filter [])
          (when reg-fn
            (fn [& rest]
              (apply reg-fn rest))))))

(def EddCoreRegQuery
  (m/schema
   [:map
    [:handler [:fn fn?]]
    [:produces
     [:fn #(m/schema? (m/schema %))]]
    [:consumes
     [:fn #(m/schema? (m/schema %))]]]))

(defn reg-query
  [ctx query-id reg-fn & rest]
  (log/debug "Registering query" query-id)
  (let [options (reduce
                 (fn [c [k v]]
                   (assoc c k v))
                 {}
                 (partition 2 rest))
        options (update options
                        :consumes
                        #(s/merge-query-consumes-schema % query-id))
        options (update options
                        :produces
                        #(s/merge-query-produces-schema %))
        options (assoc options :handler (when reg-fn
                                          (fn [& rest]
                                            (apply reg-fn rest))))]
    (when-not (m/validate EddCoreRegQuery options)
      (throw (ex-info "Invalid query registration"
                      {:explain (-> (m/explain EddCoreRegQuery options)
                                    (me/humanize))})))
    (assoc-in ctx [:edd-core :queries query-id] options)))

(defn reg-fx
  [ctx reg-fn]
  (update ctx :fx
          #(conj % (when reg-fn
                     (fn [& rest]
                       (apply reg-fn rest))))))

(defn event-fx-handler
  [ctx events]
  (mapv
   (fn [event]
     (let [handler (get-in ctx [:event-fx (:event-id event)])]
       (if handler
         (apply handler [ctx event])
         [])))
   events))

(defn reg-event-fx
  [ctx event-id reg-fn]
  (let [ctx (if (:event-fx ctx)
              ctx
              (reg-fx ctx event-fx-handler))]
    (update ctx
            :event-fx
            #(assoc % event-id (fn [& rest]
                                 (apply reg-fn rest))))))

(defn reg-service-schema
  "Register a service schema that will be serialised and returned when
  requested."
  [ctx schema]
  (assoc-in ctx [:edd-core :service-schema] schema))

(defn get-meta
  [ctx item]
  (merge
   (:meta ctx {})
   (:meta item {})))

(defn- add-log-level
  [attrs ctx item]
  (if-let [level (:log-level (get-meta ctx item))]
    (assoc attrs :log-level level)
    attrs))

(defn update-mdc-for-request
  [ctx item]
  (swap! request/*request* #(update % :mdc
                                    (fn [mdc]
                                      (-> (assoc mdc
                                                 :realm (:realm (get-meta ctx item))
                                                 :request-id (:request-id item)
                                                 :breadcrumbs (or (get item :breadcrumbs) [0])
                                                 :interaction-id (:interaction-id item))
                                          (add-log-level ctx item))))))

(defn try-parse-exception
  [e]
  (try (.getMessage e)
       (catch IllegalArgumentException e
         (log/error e)
         "Unknown error processing item")))

(defn dispatch-item
  [ctx item]
  (log/debug "Dispatching" item)
  (let [meta (get-meta ctx item)
        ctx (assoc ctx
                   :meta meta
                   :request-id (:request-id item)
                   :interaction-id (:interaction-id item))
        invocation-id (get-in @request/*request* [:mdc :invocation-id])
        log-level (get meta :log-level)
        mdc (cond-> {:realm (:realm (get-meta ctx item))
                     :request-id (:request-id item)
                     :breadcrumbs (or (get item :breadcrumbs) [0])
                     :interaction-id (:interaction-id item)}
              log-level (assoc :log-level log-level))]

    (swap! request/*request* #(update % :mdc merge mdc))
    (try
      (let [item (if (contains? item :command)
                   (-> item
                       (assoc :commands [(:command item)])
                       (dissoc :command))
                   item)
            resp (cond
                   (contains? item :apply) (event/handle-event ctx item)
                   (contains? item :query) (query/handle-query ctx item)
                   (contains? item :commands) (cmd/handle-commands ctx item)
                   (contains? item :error) item
                   :else (do
                           (log/warn item)
                           {:error :invalid-request}))]
        (if (:error resp)
          {:error          (:error resp)
           :invocation-id  invocation-id
           :request-id     (:request-id item)
           :interaction-id (:interaction-id ctx)}
          {:result         resp
           :invocation-id  invocation-id
           :request-id     (:request-id item)
           :interaction-id (:interaction-id ctx)}))

      (catch Exception e
        (do
          (log/error e)
          (let [data (ex-data e)]
            (cond
              (:error data) {:exception      (:error data)
                             :invocation-id  invocation-id
                             :request-id     (:request-id item)
                             :interaction-id (:interaction-id ctx)}

              data {:exception      data
                    :invocation-id  invocation-id
                    :request-id     (:request-id item)
                    :interaction-id (:interaction-id ctx)}
              :else {:exception      (try-parse-exception e)
                     :invocation-id  invocation-id
                     :request-id     (:request-id item)
                     :interaction-id (:interaction-id ctx)})))))))

(defn dispatch-request
  [ctx body]
  (util/d-time
   "Dispatching"
   (mapv
    #(dispatch-item ctx %)
    body)))

(def EddRequest
  (m/schema
   [:vector
    [:and
     [:map
      [:request-id uuid?]
      [:interaction-id uuid?]]
     [:or
      [:map
       [:command [:map]]]
      [:map
       [:commands sequential?]]
      [:map
       [:apply map?]]
      [:map
       [:query map?]]]]]))

(defn validate-request
  [_ctx body]
  (util/d-time
   "Validating request"
   (when-not (m/validate EddRequest body)
     (throw (ex-info "Some request are invalid"
                     {:message "Some requests are invalid"
                      :validation (->> body
                                       (m/explain EddRequest)
                                       (me/humanize))}))))
  body)

(defn with-stores
  [ctx body-fn]
  (view-store/with-init
    ctx
    #(dal/with-init
       % body-fn)))

(defn handler
  [ctx body]
  (if (:skip body)
    (do (log/info "Skipping request")
        {})
    (with-stores
      ctx
      #(let [single-request (map? body)
             body (if (map? body)
                    [body]
                    body)
             body (validate-request % body)
             response (dispatch-request % body)]
         (if single-request
           (first response)
           response)))))
