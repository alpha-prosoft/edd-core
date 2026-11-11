(ns mono-svc.filters)

;; Effects arrive from the outbox; they run as the service's :non-interactive
;; user regardless of who triggered the originating command.
(defn non-interactive-outbox
  [ctx request chain]
  (chain ctx (if (:from-api ctx)
               request
               (assoc-in request [:meta :user] {:id (name (:service-name ctx))
                                                :role :non-interactive}))))

(def filters
  [non-interactive-outbox])
