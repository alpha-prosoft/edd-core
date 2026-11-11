(ns edd.undertow.jwt
  "Authentication of HTTP requests with a JWT of an identity provider, as a
   runtime filter:

     (undertow/start ctx edd/handler
                     :filters [(jwt/authenticate
                                {:discovery-url \"https://idp/.well-known/openid-configuration\"
                                 :audience \"my-api\"})])

   The token comes from the X-Authorization header, or Authorization:
   Bearer. Its signature is verified with the keys of the provider (JWKS,
   fetched when first needed and cached), then its expiry, issuer and
   audience. :claims->user turns the verified claims into the user that goes
   to [:meta :user]."
  (:require [clojure.string :as string]
            [lambda.http :as http]
            [lambda.util :as util])
  (:import [com.auth0.jwk JwkProvider JwkProviderBuilder]
           [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.interfaces DecodedJWT]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.security.interfaces ECPublicKey RSAPublicKey]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private defaults
  {:leeway-seconds 30
   :jwks-cache-hours 12})

(defn- not-authenticated
  [reason]
  (ex-info "Not authenticated" {:message "Not authenticated"
                                :reason reason}))

(defn- header
  [request header-name]
  (some (fn [[k v]]
          (when (= header-name (string/lower-case (name k)))
            v))
        (:headers request)))

(defn- bearer
  [value]
  (when value
    (second (re-matches #"(?i)bearer\s+(.+)" (string/trim value)))))

(defn token
  "The token of an HTTP request: X-Authorization as it is, else the bearer
   token of Authorization."
  [request]
  (or (header request "x-authorization")
      (bearer (header request "authorization"))))

(defn- discovery
  [url]
  (util/to-edn (slurp url)))

(defn- provider
  ^JwkProvider [jwks-url cache-hours]
  (let [builder
        (JwkProviderBuilder. (.toURL (URI. jwks-url)))]
    (.cached builder 10 (long cache-hours) TimeUnit/HOURS)
    (.rateLimited builder 10 1 TimeUnit/MINUTES)
    (.build builder)))

(defn- identity-provider
  "What verifying needs from the provider: its keys and its issuer. With a
   :discovery-url both come from the OpenID configuration; :jwks-url and
   :issuer given explicitly win."
  [{:keys [discovery-url jwks-url issuer jwks-cache-hours]}]
  (let [discovered
        (when discovery-url
          (discovery discovery-url))

        keys-url
        (or jwks-url
            (:jwks_uri discovered)
            (throw (ex-info "JWT authentication needs :jwks-url or :discovery-url" {})))]
    {:keys (provider keys-url jwks-cache-hours)
     :issuer (or issuer (:issuer discovered))}))

(defn- algorithm
  "Asymmetric algorithms only: a token naming none or an HMAC is refused."
  ^Algorithm [^DecodedJWT decoded public-key]
  (case (.getAlgorithm decoded)
    "RS256" (Algorithm/RSA256 ^RSAPublicKey public-key nil)
    "RS384" (Algorithm/RSA384 ^RSAPublicKey public-key nil)
    "RS512" (Algorithm/RSA512 ^RSAPublicKey public-key nil)
    "ES256" (Algorithm/ECDSA256 ^ECPublicKey public-key nil)
    "ES384" (Algorithm/ECDSA384 ^ECPublicKey public-key nil)
    "ES512" (Algorithm/ECDSA512 ^ECPublicKey public-key nil)
    (throw (not-authenticated (str "Unsupported algorithm " (.getAlgorithm decoded))))))

(defn- audiences
  [audience]
  (cond
    (nil? audience) nil
    (string? audience) [audience]
    :else (vec audience)))

(defn- claims-of
  [^DecodedJWT decoded]
  (util/to-edn (String. (.decode (Base64/getUrlDecoder) (.getPayload decoded))
                        StandardCharsets/UTF_8)))

(defn verify
  "Claims of a token that is signed by the provider, not expired and issued
   by the expected issuer for the expected audience; throws otherwise."
  [{:keys [audience leeway-seconds]} {:keys [^JwkProvider keys issuer]} jwt-token]
  (try
    (let [decoded
          (JWT/decode jwt-token)

          public-key
          (.getPublicKey (.get keys (.getKeyId decoded)))

          verification
          (JWT/require (algorithm decoded public-key))]
      (.acceptLeeway verification (long leeway-seconds))
      (when issuer
        (.withIssuer verification ^"[Ljava.lang.String;" (into-array String [issuer])))
      (when-let [expected (audiences audience)]
        (.withAnyOfAudience verification ^"[Ljava.lang.String;" (into-array String expected)))
      (claims-of (.verify (.build verification) decoded)))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (not-authenticated (ex-message e))))))

(defn- roles-of
  [claims]
  (mapv keyword (or (:roles claims) (:groups claims) [])))

(defn claims->user
  "Default user of verified claims: :id from email, preferred_username or
   sub, :roles from the roles or groups claim, :realm from the realm claim."
  [claims]
  (let [realm
        (:realm claims)]
    (cond-> {:id (or (:email claims) (:preferred_username claims) (:sub claims))
             :email (:email claims)
             :roles (roles-of claims)}
      realm (assoc :realm (keyword realm)))))

(defn- with-role
  "A user without :role acts in the role lambda.http/select-role picks
   among its :roles: the one the request selects, else its first real one,
   :anonymous when it has none."
  [{:keys [role] :as user} request]
  (if role
    user
    (assoc user :role (http/select-role user request))))

(defn- checked
  [user]
  (when-not (and (map? user) (some? (:id user)))
    (throw (ex-info ":claims->user has to return a user with :id"
                    {:message ":claims->user has to return a user with :id"})))
  user)

(defn authenticate
  "Runtime filter: an HTTP request runs as the user of its JWT. config:
     :discovery-url  OpenID configuration of the provider
                     (.../.well-known/openid-configuration), names its keys
                     and issuer; or
     :jwks-url       its keys (JWKS) directly
     :issuer         expected iss, default the discovered one
     :audience       expected aud, a string or several; not checked without
     :claims->user   (fn [claims]) returning the user for [:meta :user]:
                     :id is required, :roles, :role, :realm and anything
                     else are yours; default claims->user. Without :role
                     the user acts in one of its :roles
                     (lambda.http/select-role), :anonymous when it has none
     :leeway-seconds clock skew accepted for exp and nbf (30)
     :jwks-cache-hours how long fetched keys are kept (12)
   The provider is contacted when the first request arrives, and again
   on the next one when it could not be reached."
  [config]
  (let [{user-of :claims->user :as settings}
        (merge defaults {:claims->user claims->user} config)

        known-provider
        (atom nil)

        provider-of
        (fn []
          (or @known-provider
              (reset! known-provider (identity-provider settings))))

        resolve-user
        (fn [{:keys [req body]}]
          (let [jwt-token
                (or (token req)
                    (throw (not-authenticated "No token")))

                claims
                (verify settings (provider-of) jwt-token)]
            (with-role (checked (user-of claims)) body)))]
    (http/authenticate resolve-user)))
