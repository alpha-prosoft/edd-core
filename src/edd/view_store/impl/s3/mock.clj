(ns edd.view-store.impl.s3.mock
  (:require [lambda.util :as util]
            [edd.ctx :as edd-ctx]
            [clojure.tools.logging :as log]
            [edd.view-store.common :as common]
            [lambda.ctx :as lambda-ctx]))

(def implementation :s3-mock)

(defn with-init
  [ctx body-fn]
  (log/debug "Initializing memory view store")
  (body-fn ctx))

(defn get-snapshot
  [ctx id & [_version]]
  (log/info "Fetching mock snapshot aggregate" id)
  (let [realm (edd-ctx/get-realm ctx)
        service-name (lambda-ctx/get-service-name ctx)]
    (->> @(common/get-store)
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
    (swap! (common/get-store)
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



