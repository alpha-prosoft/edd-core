(ns ping-svc.auth
  (:require [lambda.http :as http]))

;; E2E only: no JWT/Cognito, the user comes straight from the request body
;; (realm from its meta). Without one the request runs as m2m traffic and
;; keeps its incoming meta.
(defn- request-user
  [{:keys [body]}]
  (if-let [user (:user body)]
    (assoc user :realm (get-in body [:meta :realm]))
    {:id "e2e" :role :non-interactive}))

(def authenticate
  (http/authenticate request-user))

(defn register
  [ctx]
  ctx)
