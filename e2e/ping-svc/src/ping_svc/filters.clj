(ns ping-svc.filters
  (:require [aws.filters :as aws-filters]))

;; Effects arrive through the queue from the router; they run as the service's
;; :non-interactive user regardless of who triggered the originating command.
;; The request meta is stamped since handle-commands merges it over ctx meta.
(defn non-interactive-queue
  [ctx request chain]
  (chain ctx (if (:from-api ctx)
               request
               (assoc-in request [:meta :user] {:id (name (:service-name ctx))
                                                :role :non-interactive}))))

(def filters
  [aws-filters/from-bucket
   non-interactive-queue])
