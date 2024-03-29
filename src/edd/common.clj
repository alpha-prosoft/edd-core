(ns edd.common
  (:require
   [edd.dal :as dal]
   [edd.view-store.common  :as common-view-store]
   [edd.view-store.elastic :as elastic-view-store]
   [edd.el.event :as el-event]
   [lambda.uuid :as uuid]
   [clojure.tools.logging :as log]))

(defn parse-param
  [query]
  (if (or (uuid? query)
          (string? query)
          (number? query))
    query
    (let [filter (dissoc query :query-id)
          keys (vec filter)]
      (when (> (count keys)
               1)
        (throw (ex-info "Unable to determine filter. Needs to have only 1 key next to :query-id"
                        {:filter-count (count keys)})))
      (-> keys
          (first)
          (second)))))

(defn get-by-id
  [ctx & [query]]
  (if-let [id (cond
                (:id query) (:id query)
                (:id ctx) (:id ctx)
                query (parse-param query)
                :else nil)]
    (let [resp (el-event/get-by-id (assoc ctx :id id))]
      (if query
        (:aggregate resp)
        resp))
    (log/warn "Id is nil")))

(defn fetch-by-id
  [ctx & [query]]
  {:pre [(or (:query ctx)
             query)]}
  (if-let [id (or (-> ctx :query :id)
                  (:id query))]
    (common-view-store/get-snapshot ctx id)
    (log/warn "Fetch-by-id -> Id is nil")))

(defn get-sequence-number-for-id
  [ctx & [query]]
  {:pre [(or (:id ctx)
             query)]}
  (dal/get-sequence-number-for-id
   (if query
     (assoc ctx :id (if (:id query)
                      (:id query)
                      (parse-param query)))
     ctx)))

(defn get-id-for-sequence-number
  [ctx & [query]]
  {:pre [(or (:sequence ctx)
             sequence)]}
  (dal/get-id-for-sequence-number
   (if query
     (assoc ctx :id (if (:sequence query)
                      (:sequence query)
                      (parse-param query)))
     ctx)))

(defn get-aggregate-id-by-identity
  [ctx & [query]]
  {:pre [(or (:identity ctx)
             query)]}
  (dal/get-aggregate-id-by-identity
   (if query
     (assoc ctx :identity (parse-param query))
     ctx)))

(defn advanced-search
  [ctx & [query]]
  {:pre [(or (:query ctx)
             query)]}
  (elastic-view-store/advanced-search
   (if query
     (assoc ctx :query query)
     ctx)))

(defn simple-search
  [ctx & [query]]
  {:pre [(or (:query ctx)
             query)]}
  (elastic-view-store/simple-search
   ctx
   (if query
     (:query query)
     (:query ctx))))

(defn create-identity
  [& _]
  (uuid/gen))
