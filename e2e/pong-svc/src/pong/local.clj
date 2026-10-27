(ns pong.local
  (:gen-class)
  (:require [edd.core :as edd]
            [edd.kafka.distributor :as distributor]
            [edd.postgres.event-store :as event-store]
            [edd.undertow.core :as undertow]
            [edd.view-store.postgres.view-store :as view-store]
            [pong.service :as service]))

(defn -main
  [& _args]
  (undertow/start
   (service/init (view-store/register (event-store/register (distributor/register {}))))
   edd/handler
   :filters service/request-filters))
