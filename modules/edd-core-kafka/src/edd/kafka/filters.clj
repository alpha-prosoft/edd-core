(ns edd.kafka.filters
  "Runtime filters for requests consumed from Kafka."
  (:require [lambda.runtime :as runtime]))

(def event-source
  "edd:kafka")

(defn authenticate
  "Runtime filter filling in the user of Kafka records, by default the
   service's :non-interactive user. options: :resolve-user (fn [ctx request])
   returning the user; the record is in ctx :record (:topic, :partition,
   :offset, :key)."
  ([]
   (authenticate {}))
  ([options]
   (runtime/authenticate-records (assoc options :source event-source))))
