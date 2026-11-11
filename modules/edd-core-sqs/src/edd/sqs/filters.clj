(ns edd.sqs.filters
  "Runtime filters for requests consumed from SQS."
  (:require [lambda.runtime :as runtime]))

(def event-source
  "aws:sqs")

(defn authenticate
  "Runtime filter filling in the user of SQS records, by default the
   service's :non-interactive user. options: :resolve-user (fn [ctx request])
   returning the user; the record is in ctx :record."
  ([]
   (authenticate {}))
  ([options]
   (runtime/authenticate-records (assoc options :source event-source))))
