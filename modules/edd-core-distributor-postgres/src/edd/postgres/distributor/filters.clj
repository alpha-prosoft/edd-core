(ns edd.postgres.distributor.filters
  "Runtime filters for requests delivered from the outbox."
  (:require [lambda.runtime :as runtime]))

(def event-source
  "edd:postgres-distributor")

(defn authenticate
  "Runtime filter filling in the user of outbox requests, by default the
   service's :non-interactive user. options: :resolve-user (fn [ctx request])
   returning the user."
  ([]
   (authenticate {}))
  ([options]
   (runtime/authenticate-records (assoc options :source event-source))))
