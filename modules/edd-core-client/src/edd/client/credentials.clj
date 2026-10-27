(ns edd.client.credentials
  "Credentials providers of the HTTP deps resolver:
   {:init (fn [ctx] state) :authorize (fn [ctx state request] request')}.
   :init runs once when the service starts, :authorize adds what the target
   service needs to every request, refreshing the credentials when needed."
  (:require [lambda.util :as util]))

(defn token-header
  [request token]
  (if token
    (assoc-in request [:headers "X-Authorization"] token)
    request))

(defn env
  "Token from the ServiceToken env var; requests go without one when unset."
  []
  {:init (fn [_ctx]
           (util/get-env "ServiceToken"))
   :authorize (fn [_ctx token request]
                (token-header request token))})
