(ns edd.router.core
  "Router service entry point. The fan-out lives in edd.distribution.router
   and publishes through the distributor registered on ctx."
  (:require [edd.distribution.router :as router]))

(defn register
  "Marks the service as the router, so long-running runtimes consume the
   router response destination."
  [ctx]
  (assoc ctx :edd/router true))

(defn handle-request
  [ctx record]
  (router/handle-record ctx record))

(defn handler
  "Consumes the router response destination and fans each effect and event
   out to the target service destinations."
  [ctx body]
  (router/handler ctx body))
