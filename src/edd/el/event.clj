(ns edd.el.event
  (:require
   [clojure.tools.logging :as log]
   [edd.dal :as dal]
   [edd.request-cache :as request-cache]
   [lambda.request :as request]
   [edd.view-store.common :as view-store]
   [lambda.util :as util]))

(defn apply-event
  [agr event func]
  (if func
    (assoc
     (apply func [agr event])
     :version (:event-seq event)
     :id (:id event))
    agr))

(defn create-aggregate
  [snapshot events apply-functions]
  (reduce
   (fn [agr event]
     (log/debug "Attempting to apply" event)
     (let [event-id (keyword (:event-id event))]

       (if (contains? apply-functions event-id)
         (apply-event
          agr
          event
          (event-id apply-functions))
         (assoc agr
                :version (:event-seq event)
                :id (:id event)))))
   snapshot
   events))

(defn apply-agg-filter
  [ctx aggregate]
  (reduce
   (fn [v f]
     (f (assoc
         ctx
         :agg v)))
   aggregate
   (get ctx :agg-filter [])))

(defn get-current-state
  [{:keys [id events snapshot] :as ctx}]
  {:pre [id events]}
  (log/info "Calculating current state for:" id)
  (log/debug "Events: " events)
  (log/debug "Snapshot: " snapshot)

  (cond
    (:error events) (throw (ex-info "Error fetching events" {:error events}))
    (> (count events) 0) (let [aggregate (create-aggregate snapshot events (:def-apply ctx))
                               result-agg (apply-agg-filter ctx aggregate)]
                           (log/info (format "New aggragate version: %s"
                                             (:version result-agg)))
                           (assoc
                            ctx
                            :aggregate result-agg))
    snapshot (do
               (log/info (format "No new events to taking snapshot: %s"
                                 (:version snapshot)))
               (assoc ctx :aggregate snapshot))
    :else (assoc ctx :aggregate nil)))

(defn fetch-snapshot
  [{:keys [id] :as ctx}]

  (util/d-time
   (str "Fetching snapshot: " id)
   (if-let [snapshot (view-store/get-snapshot ctx id)]
     (do
       (when snapshot
         (log/info "Found snapshot: " (:version snapshot)))
       (assoc ctx
              :snapshot snapshot
              :version (:version snapshot)))
     ctx)))

(defn get-events [ctx]
  (assoc ctx :events (dal/get-events ctx)))

(defn get-by-id
  [{:keys [id] :as ctx}]
  {:pre [(:id ctx)]}
  (let [cache-snapshot (request-cache/get-aggregate ctx id)]
    (if cache-snapshot
      (do
        (log/info "Using cached snapshot: " (:id cache-snapshot)
                  ", version: " (:version cache-snapshot))
        (assoc ctx :aggregate cache-snapshot))
      (-> ctx
          (fetch-snapshot)
          (get-events)
          (get-current-state)))))

(defn update-aggregate
  [{:keys [aggregate]
    :as ctx}]
  (if aggregate
    (do
      (log/info (format "Updating aggregate id: %s, to version %s"
                        (:id aggregate)
                        (:version aggregate)))
      (view-store/update-snapshot ctx (:aggregate ctx)))
    ctx))

(defn handle-event
  [ctx body]
  (let [apply-request (:apply body)
        meta (merge (:meta ctx)
                    (:meta body))
        ctx (assoc ctx :meta meta)
        realm (:realm meta)
        agg-id (:aggregate-id apply-request)]

    (util/d-time
     (str "handling-apply: " realm " " (:aggregate-id apply))
     (let [applied (get-in @request/*request* [:applied realm agg-id])]
       (when-not applied
         (-> ctx
             (assoc :id agg-id)
             (get-by-id)
             (update-aggregate))
         (swap! request/*request*
                #(assoc-in % [:applied realm agg-id] {:apply true}))))))
  {:apply true})
