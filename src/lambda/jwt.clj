(ns lambda.jwt
  (:require [lambda.util :as util]
            [clojure.walk :as walk]
            [clojure.tools.logging :as log]
            [clojure.string :as str])

  (:import (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwt JWT)
           (com.auth0.jwk Jwk)
           (com.auth0.jwt.exceptions SignatureVerificationException)))

(defn validate-token-attributes
  [{{iss :iss aud :aud} :auth} token]
  (-> {:jwk       :valid
       :signature :valid}
      (assoc :aud (if (= (:aud token) aud)
                    :valid
                    {:result :invalid
                     :actual (:aud token)
                     :expected aud}))
      (assoc :iss (if (= (:iss token) iss)
                    :valid
                    {:result :invalid
                     :actual (:iss token)
                     :expected iss}))
      (assoc :exp (if (> (:exp token)
                         (util/get-current-time-ms))
                    :valid
                    :invalid))))

(defn fetch-jwks-keys
  [ctx]
  (let [jwks-json (util/load-config "jwks.json")]
    (log/debug "Initializing JWKS" (get jwks-json :keys))
    (assoc ctx :jwks-all (get jwks-json :keys))))

(defn parse-token
  [{:keys [jwks-all] :as ctx} token]
  (log/debug "Parsing JWT token")
  (try
    (let [jwt (JWT/decode token)
          token-kid (.getKeyId jwt)
          claims (-> token
                     (str/split #"\.")
                     second
                     (util/base64URLdecode)
                     (util/to-edn))
          [jwks] (filter #(= (:kid %) token-kid) jwks-all)]

      (if jwks
        (let [jwk (Jwk/fromValues (walk/stringify-keys jwks))]
          (try
            (.verify (Algorithm/RSA256 (.getPublicKey jwk) nil) jwt)
            (let [resp (validate-token-attributes
                        ctx
                        {:iss (.getIssuer jwt)
                         :aud (.get (.getAudience jwt) 0)
                         :exp (.getTime
                               (.getExpiresAt jwt))})
                  [invalid] (filter (fn [[_ v]] (not= v :valid)) resp)]
              (if-not invalid
                claims
                (do
                  (log/error "Token attributes validation failed" resp)
                  {:error resp})))

            (catch SignatureVerificationException e
              (log/error "Unable to verify signature" e)
              {:error {:jwk       :valid
                       :signature :invalid}})))
        {:error {:jwk :invalid}}))
    (catch Exception e
      (log/error "Unable to parse token" e)
      {:error {:jwt :invalid}})))
