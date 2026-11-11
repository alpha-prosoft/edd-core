(ns mono-svc.auth
  (:require [edd.core :as edd]))

;; E2E only: no JWT/Cognito, the user comes straight from the request body
;; (realm from its meta).
(defn- request-user
  [{:keys [body]}]
  (if-let [user (:user body)]
    (assoc user :realm (get-in body [:meta :realm]))
    {:id "e2e" :role :non-interactive}))

(defn register
  [ctx]
  (edd/reg-feature ctx :e2e/public-auth
                   :deps {}
                   :resolve-user request-user))
