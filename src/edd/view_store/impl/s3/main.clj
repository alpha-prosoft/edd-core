(ns edd.view-store.impl.s3.main
  (:require [sdk.aws.s3 :as s3]
            [lambda.util :as util]
            [edd.ctx :as edd-ctx]
            [lambda.ctx :as lambda-ctx]))

(def implementation :s3-main)

(defn get-key
  [ctx id]
  (str "aggregates/"
       (lambda-ctx/get-service-name ctx)
       "/"
       (str
        (edd-ctx/get-realm ctx))
       "/"
       id
       ".json"))

(defn get-snapshot
  [ctx id & [_version]]
  (let [storage (get-in ctx [:view-store :config :storage])
        object (-> storage
                   (assoc-in [:s3 :object :key]
                             (get-key ctx id)))
        {:keys [error] :as resp} (s3/get-object ctx object)]
    (cond
      (not error) (-> resp
                      (slurp)
                      (util/to-edn))
      (= (:status error) 404) nil
      :else (throw (ex-info "Error getting snapshot"
                            {:error  error
                             :object object})))))

(defn update-snapshot
  [ctx aggregate]
  (let [storage (get-in ctx [:view-store :config :storage])
        object (-> storage

                   (assoc-in [:s3 :object :key]
                             (get-key ctx (:id aggregate))))
        {:keys [error]} (s3/put-object ctx
                                       (-> object
                                           (assoc-in [:s3 :object :content]
                                                     (util/to-json aggregate))))]
    (when error
      (throw (ex-info "Error updating snapshot"
                      {:error  error
                       :object object})))
    aggregate))

(defn with-init
  [ctx body-fn]
  (body-fn ctx))
