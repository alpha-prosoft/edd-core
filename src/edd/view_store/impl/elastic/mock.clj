(ns edd.view-store.impl.elastic.mock
  (:require [edd.view-store.impl.elastic.mock-search :as mock-search]
            [lambda.util :as util]
            [clojure.data :as clojure-data]
            [edd.view-store.common :as common]
            [clojure.tools.logging :as log]))

(def implementation :elastic-mock)

(defn with-init
  [ctx body-fn]
  (log/debug "Initializing memory view store")
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
         (->> @(common/get-store)
              (:aggregate-store)))))

(defn update-aggregate-impl
  [ctx aggregate]
  {:pre [aggregate]}
  (log/info "Emulated 'update-aggregate' dal function")
  (let [aggregate (fix-keys aggregate)]
    (swap! (common/get-store)
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

(defn get-snapshot
  [_ctx id]
  (log/info "Fetching snapshot aggregate" id)
  (->> @(common/get-store)
       (:aggregate-store)
       (filter
        #(= (:id %) id))
       (first)))
