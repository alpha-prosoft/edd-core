(ns lambda.ctx
  (:require [malli.util :as mu]
            [lambda.util :as util]))

(def default-service-name :local-svc)

(defn init
  "Update name, overriding previous name"
  [{:keys [service-name]
    :or {service-name (keyword (util/get-env
                                "ServiceName"
                                default-service-name))}
    :as ctx}]
  (-> ctx
      (assoc :service-name  service-name
             :hosted-zone-name (util/get-env
                                "PublicHostedZoneName"
                                "example.com")
             :environment-name-lower (util/get-env
                                      "EnvironmentNameLower"
                                      "local"))))

(defn get-service-name
  [ctx]
  (get ctx :service-name default-service-name))

(defn get-hosted-zone-name
  [ctx]
  (get ctx :hosted-zone-name))

(defn get-environment-name-lower
  [ctx]
  (get ctx :environment-name-lower))
