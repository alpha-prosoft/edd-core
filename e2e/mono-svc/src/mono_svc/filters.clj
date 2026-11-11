(ns mono-svc.filters
  (:require [edd.postgres.distributor.filters :as outbox]
            [mono-svc.auth :as auth]))

;; Effects arrive from the outbox; they run as the service's :non-interactive
;; user regardless of who triggered the originating command.
(def filters
  [auth/authenticate
   (outbox/authenticate)])
