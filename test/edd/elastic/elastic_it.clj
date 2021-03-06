(ns edd.elastic.elastic-it
  (:require [clojure.test :refer :all]
            [edd.core :as edd]
            [edd.view-store.elastic :as elastic-view-store]
            [edd.memory.event-store :as memory-event-store]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]
            [edd.view-store.common :as view-store]
            [clojure.string :as str]
            [lambda.elastic :as el]
            [lambda.util :as util]
            [edd.response.cache :as response-cache]
            [clojure.tools.logging :as log]))
(defn get-ctx
  []
  (let [ctx {:meta {:realm :test}}]
    (if (util/get-env "AWS_ACCESS_KEY_ID")
      (assoc ctx
             :aws {:region                (util/get-env "AWS_DEFAULT_REGION")
                   :aws-access-key-id     (util/get-env "AWS_ACCESS_KEY_ID")
                   :aws-secret-access-key (util/get-env "AWS_SECRET_ACCESS_KEY")
                   :aws-session-token     (util/get-env "AWS_SESSION_TOKEN")})
      ctx)))

(defn create-service-name
  []
  (str/replace (str (uuid/gen)) "-" "_"))

(defn create-service-index
  [{:keys [service-name] :as ctx}]
  (let [body {:settings
              {:index
               {:number_of_shards   1
                :number_of_replicas 0}}
              :mappings
              {:dynamic_templates
               [{:integers
                 {:match_mapping_type "long",
                  :mapping
                  {:type "integer",
                   :fields
                   {:number {:type "long"},
                    :keyword
                    {:type         "keyword",
                     :ignore_above 256}}}}}]}}]

    (log/info "Index name" service-name)
    (el/query
     (merge
      ctx
      {:elastic-search (get-in ctx [:view-store :config])
       :method         "PUT"
       :path           (str "/"
                            (elastic-view-store/realm ctx)
                            "_"
                            service-name)
       :body           (util/to-json body)}))
    ctx))

(defn cleanup-index
  [{:keys [service-name] :as ctx}]
  (el/query
   (merge
    ctx
    {:elastic-search (get-in ctx [:view-store :config])
     :method         "DELETE"
     :path           (str "/" service-name)})))

(deftest test-get-elastic-snapshot
  (let [agg-id (uuid/gen)
        ctx (-> (get-ctx)
                (response-cache/register-default)
                (memory-event-store/register)
                (elastic-view-store/register)
                (assoc :service-name (create-service-name)
                       :meta {:realm :test})
                (create-service-index)
                (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                      [{:event-id :event-1
                                        :attrs    (:attrs cmd)}]))
                (edd/reg-event :event-1 (fn [agg event]
                                          (assoc agg :attrs
                                                 (:attrs event)))))]

    (mock/apply-cmd ctx
                    {:commands [{:cmd-id :cmd-1
                                 :id     agg-id
                                 :attrs  {:my :special}}]})

    (is (= {:id      agg-id
            :version 1
            :attrs   {:my :special}}
           (view-store/get-snapshot ctx agg-id)))
    #_(cleanup-index ctx)))