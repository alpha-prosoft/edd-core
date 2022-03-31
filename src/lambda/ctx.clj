(ns lambda.ctx
  (:require [malli.util :as mu]))

(def default-service-name :local-svc)

(defn update-service-name
  "Update name, overriding previous name"
  [ctx service-name]
  (assoc ctx :service-name service-name))

(defn set-service-name
  "Keep previous name if already set"
  [ctx service-name]
  (assoc ctx :service-name
         (get ctx
              :service-name service-name)))

(defn get-service-name
  [ctx]
  (get ctx :service-name default-service-name))