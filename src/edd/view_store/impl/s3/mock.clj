(ns edd.view-store.impl.s3.mock
  (:require [sdk.aws.s3 :as s3]
            [lambda.util :as util]
            [edd.ctx :as edd-ctx]
            [clojure.tools.logging :as log]
            [edd.memory.event-store :as event-store]
            [lambda.ctx :as lambda-ctx]))

(def implementation :s3-mock)

(def default-store {:aggregate-store []})

(defn get-snapshot
  [ctx id & [_version]]
  (log/info "Fetching snapshot aggregate" id)
  (let [realm (edd-ctx/get-realm ctx)
        service-name (lambda-ctx/get-service-name ctx)]
    (->> @event-store/*dal-state*
         :aggregate-store
         (filter
          #(and (= (:realm %) realm)
                (= (:service-name %) service-name)
                (= (get-in % [:data :id]) id)))
         first
         :data)))

(defn update-snapshot
  [ctx aggregate]
  {:pre [aggregate]}
  (log/info "Emulated 'update-aggregate' dal function")
  (let [aggregate (util/fix-keys aggregate)
        realm (edd-ctx/get-realm ctx)
        service-name (lambda-ctx/get-service-name ctx)]
    (swap! event-store/*dal-state*
           #(update % :aggregate-store
                    (fn [store]
                      (->> store
                           (remove
                            (fn [entry]
                              (and (= (:realm entry) realm)
                                   (= (:service-name entry) service-name)
                                   (= (get-in entry [:data :id]) (:id aggregate)))))
                           (cons {:service-name service-name
                                  :realm        realm
                                  :data         aggregate}))))))

  ctx)

(defn with-init
  [ctx body-fn]
  (log/debug "Initializing memory view store")
  (if-not (:global @event-store/*dal-state*)
    (do
      (swap! event-store/*dal-state*
             #(merge
               default-store
               (select-keys
                ctx
                (keys default-store))
               %))
      (body-fn ctx))
    (binding [event-store/*dal-state* (atom (merge
                                             default-store
                                             (util/fix-keys
                                              (select-keys
                                               ctx
                                               (keys default-store)))
                                             {:global false}))]
      (body-fn ctx))))

