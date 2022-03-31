(ns edd.view-store.impl.elastic.mock
  (:require [edd.view-store.impl.elastic.mock-search :as mock-search]
            [edd.memory.event-store :as event-store]
            [lambda.util :as util]
            [clojure.data :as clojure-data]
            [clojure.tools.logging :as log]))

(def implementation :elastic-mock)

(defn with-init
  [ctx body-fn]
  (body-fn ctx))

(defn fix-keys
  [val]
  (-> val
      (util/to-json)
      (util/to-edn)))

(defn filter-aggregate
  [query aggregate]
  (let [res (clojure-data/diff aggregate query)]
    (and (= (second res) nil)
         (= (nth res 2) query))))

(defn simple-search
  [_ctx query]
  {:pre [query]}
  (into []
        (filter
         #(filter-aggregate
           (dissoc query :query-id)
           %)
         (->> @event-store/*dal-state*
              (:aggregate-store)))))

(defn update-aggregate-impl
  [ctx aggregate]
  {:pre [aggregate]}
  (log/info "Emulated 'update-aggregate' dal function")
  (let [aggregate (fix-keys aggregate)]
    (swap! event-store/*dal-state*
           #(update % :aggregate-store
                    (fn [v]
                      (->> v
                           (filter
                            (fn [el]
                              (not= (:id el) (:id aggregate))))
                           (cons aggregate)
                           (sort-by (fn [{:keys [id]}] (str id))))))))

  ctx)

(defn update-snapshot
  [ctx aggregate]
  (update-aggregate-impl ctx aggregate))

(defn advanced-search
  [ctx query]
  (mock-search/advanced-search-impl ctx query))

(defn with-init
  [ctx body-fn]
  (log/debug "Initializing memory view store")
  (if-not (:global @event-store/*dal-state*)
    (do
      (swap! event-store/*dal-state*
             #(merge
               mock-search/default-elastic-store
               (select-keys
                ctx
                (keys mock-search/default-elastic-store))
               %))
      (body-fn ctx))
    (binding [event-store/*dal-state* (atom (merge
                                             mock-search/default-elastic-store
                                             (util/fix-keys
                                              (select-keys
                                               ctx
                                               (keys mock-search/default-elastic-store)))
                                             {:global false}))]
      (body-fn ctx))))

(defn get-snapshot
  [_ctx id]
  (log/info "Fetching snapshot aggregate" id)
  (->> @event-store/*dal-state*
       (:aggregate-store)
       (filter
        #(= (:id %) id))
       (first)))
