(ns router.local
  (:gen-class)
  (:require [edd.kafka.distributor :as distributor]
            [edd.router.core :as router]
            [edd.undertow.core :as undertow]))

(defn -main
  [& _args]
  (undertow/start
   (router/register (distributor/register {}))
   router/handler))
