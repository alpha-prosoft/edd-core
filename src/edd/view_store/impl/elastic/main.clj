(ns edd.view-store.impl.elastic.main
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [edd.ctx :as edd-ctx]
            [edd.view-store.impl.elastic.common :as common]
            [lambda.elastic :as el]))

(def implementation :elastic-main)

(defn with-init
  [ctx body-fn]
  (log/debug "Initializing elastic search")
  (body-fn ctx))

(defn realm
  [ctx]
  (name (edd-ctx/get-realm ctx)))

(defn- make-index-name
  [realm-name service-name]
  (str realm-name "_" (str/replace (name service-name) "-" "_")))

(defn- field+keyword
  [field]
  (str (name field) ".keyword"))

(defn ->trim [v]
  (if (string? v)
    (str/trim v)
    v))

(def op->filter-builder
  {:and      (fn [op->fn & filter-spec]
               {:bool
                {:filter (mapv #(common/parse op->fn %) filter-spec)}})
   :or       (fn [op->fn & filter-spec]
               {:bool
                {:should               (mapv #(common/parse op->fn %) filter-spec)
                 :minimum_should_match 1}})
   :eq       (fn [_ & [a b]]
               {:term
                {(field+keyword a) (->trim b)}})
   :=        (fn [_ & [a b]]
               {:term
                {(name a) (->trim b)}})
   :wildcard (fn [_ & [a b]]
               {:bool
                {:should
                 [{:wildcard
                   {(str (name a)) {:value (str "*" (->trim b) "*")}}}
                  {:match_phrase
                   {(str (name a)) (str (->trim b))}}]}})
   :not      (fn [op->fn & filter-spec]
               {:bool
                {:must_not (common/parse op->fn filter-spec)}})
   :in       (fn [_ & [a b]]
               {:terms
                {(field+keyword a) b}})
   :exists   (fn [_ & [a _]]
               {:exists
                {:field (name a)}})
   :lte      (fn [_ & [a b]]
               {:range
                {(name a) {:lte b}}})
   :gte      (fn [_ & [a b]]
               {:range
                {(name a) {:gte b}}})
   :nested   (fn [op->fn path & filter-spec]
               {:bool
                {:must [{:nested {:path  (name path)
                                  :query (mapv #(common/parse op->fn %) filter-spec)}}]}})})

(defn search-with-filter
  [filter q]
  (let [[_fields-key fields _value-key value] (:search q)
        search (mapv
                (fn [p]
                  {:bool
                   {:should               [{:match
                                            {(str (name p))
                                             {:query value
                                              :boost 2}}}
                                           {:wildcard
                                            {(str (name p)) {:value (str "*" (->trim value) "*")}}}]
                    :minimum_should_match 1}})
                fields)]

    (-> filter
        (assoc-in [:query :bool :should] search)
        (assoc-in [:query :bool :minimum_should_match] 1))))

(defn form-sorting
  [sort]
  (map
   (fn [[a b]]
     (case (keyword b)
       :asc {(field+keyword a) {:order "asc"}}
       :desc {(field+keyword a) {:order "desc"}}
       :asc-number {(str (name a) ".number") {:order "asc"}}
       :desc-number {(str (name a) ".number") {:order "desc"}}
       :asc-date {(name a) {:order "asc"}}
       :desc-date {(name a) {:order "desc"}}))
   (partition 2 sort)))

(defn create-elastic-query
  [q]
  (cond-> {}
    (:filter q) (merge {:query {:bool {:filter (common/parse op->filter-builder (:filter q))}}})
    (:search q) (search-with-filter q)
    (:select q) (assoc :_source (mapv name (:select q)))
    (:sort q) (assoc :sort (form-sorting (:sort q)))))

(defn advanced-direct-search
  [ctx elastic-query]
  (let [json-query (util/to-json elastic-query)
        index-name (make-index-name (realm ctx) (or (:index-name ctx) (:service-name ctx)))
        {:keys [error] :as body} (el/query
                                  {:config (get-in ctx [:view-store :config] ctx)
                                   :method "POST"
                                   :path   (str "/" index-name "/_search")
                                   :body   json-query})
        total (get-in body [:hits :total :value])]

    (when error
      (throw (ex-info "Elastic query failed" error)))
    (log/debug "Elastic query")
    (log/debug json-query)
    (log/debug body)
    {:total total
     :from  (get elastic-query :from 0)
     :size  (get elastic-query :size common/default-size)
     :hits  (mapv
             :_source
             (get-in body [:hits :hits] []))}))

(defn advanced-search
  [ctx query]
  (let [elastic-query (-> (create-elastic-query query)
                          (assoc
                           :from (get query :from 0)
                           :size (get query :size common/default-size)))]
    (advanced-direct-search ctx elastic-query)))

(defn flatten-paths
  ([m separator]
   (flatten-paths m separator []))
  ([m separator path]
   (->> (map (fn [[k v]]
               (if (and (map? v) (not-empty v))
                 (flatten-paths v separator (conj path k))
                 [(->> (conj path k)
                       (map name)
                       (str/join separator)
                       keyword) v]))
             m)
        (into {}))))

(defn create-simple-query
  [query]
  {:pre [query]}
  (util/to-json
   {:size  600
    :query {:bool
            {:must (mapv
                    (fn [[field value]]
                      {:term {(field+keyword field) value}})
                    (seq (flatten-paths query ".")))}}}))

(defn simple-search
  [ctx query]
  (log/debug "Executing simple search" query)
  (let [index-name (make-index-name (realm ctx) (:service-name ctx))
        param (dissoc query :query-id)
        body (util/d-time
              "Doing elastic search (Simple-search)"
              (el/query
               {:config (get-in ctx [:view-store :config] ctx)
                :method         "POST"
                :path           (str "/" index-name "/_search")
                :body           (create-simple-query param)
                :elastic-search (get-in ctx [:view-store :config])
                :aws            (:aws ctx)}))]
    (mapv
     :_source
     (get-in body [:hits :hits] []))))

(defn store-to-elastic
  [{:keys [aggregate] :as ctx}]
  (log/debug "Updating aggregate" (realm ctx) aggregate)
  (let [index-name (make-index-name (realm ctx) (:service-name ctx))
        {:keys [error]} (el/query
                         {:config (get-in ctx [:view-store :config] ctx)
                          :method "POST"
                          :path   (str "/" index-name "/_doc/" (:id aggregate))
                          :body   (util/to-json aggregate)
                          :aws    (:aws ctx)})]
    (if error
      (throw (ex-info "Could not store aggregate" {:error error}))
      ctx)))

(defn update-snapshot
  [ctx aggregate]
  (log/info "Updating aggregate " (realm ctx) (:id aggregate) (:version aggregate))
  (store-to-elastic ctx))

(defn get-snapshot
  [ctx id]
  (log/info "Fetching snapshot aggregate" (realm ctx) id)
  (util/d-time
   (str "Fetching snapshot aggregate in realm: " (realm ctx)
        ", id: " id)
   (let [index-name (make-index-name (realm ctx) (:service-name ctx))
         {:keys [error] :as body} (el/query
                                   {:config (get-in ctx [:view-store :config] ctx)
                                    :method "GET"
                                    :path   (str "/" index-name "/_doc/" id)
                                    :aws    (:aws ctx)}
                                   :ignored-status 404)]
     (if error
       (throw (ex-info "Failed to fetch snapshot" error))
       (:_source body)))))
