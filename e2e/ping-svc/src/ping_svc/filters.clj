(ns ping-svc.filters
  (:require [aws.filters :as aws-filters]
            [lambda.runtime :as runtime]
            [ping-svc.auth :as auth]))

;; Effects arrive through the queue from the router (SQS on AWS, Kafka
;; locally); they run as the service's :non-interactive user regardless of
;; who triggered the originating command.
(def filters
  [auth/authenticate
   aws-filters/from-bucket
   (runtime/authenticate-records)])
