(ns edd.undertow.jwt-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [edd.undertow.core :as undertow]
            [edd.undertow.jwt :as sut]
            [lambda.runtime.memory :as runtime]
            [lambda.util :as util])
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.security KeyPair KeyPairGenerator]
           [java.security.interfaces RSAPrivateKey RSAPublicKey]
           [java.time Instant]
           [java.util Base64]))

(defn- key-pair
  ^KeyPair []
  (let [generator
        (KeyPairGenerator/getInstance "RSA")]
    (.initialize generator 2048)
    (.generateKeyPair generator)))

(def ^:private provider-keys
  (key-pair))

(def ^:private other-keys
  (key-pair))

(def ^:private idp
  (atom nil))

(defn- base64url
  [^java.math.BigInteger number]
  (let [bytes
        (.toByteArray number)

        unsigned
        (if (zero? (aget bytes 0))
          (java.util.Arrays/copyOfRange bytes 1 (alength bytes))
          bytes)]
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) unsigned)))

(defn- jwks
  []
  (let [^RSAPublicKey public-key
        (.getPublic ^KeyPair provider-keys)]
    (util/to-json {:keys [{:kty "RSA"
                           :kid "key-1"
                           :use "sig"
                           :alg "RS256"
                           :n (base64url (.getModulus public-key))
                           :e (base64url (.getPublicExponent public-key))}]})))

(defn- respond
  [^HttpExchange exchange ^String body]
  (let [bytes
        (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange 200 (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- base-url
  []
  (str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer @idp))))

(defn- with-identity-provider
  [f]
  (let [server
        (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (reset! idp server)
    (.createContext server "/jwks.json"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond exchange (jwks)))))
    (.createContext server "/.well-known/openid-configuration"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (respond exchange (str "{\"issuer\":\"" (base-url) "\","
                                               "\"jwks_uri\":\"" (base-url) "/jwks.json\"}")))))
    (.start server)
    (try
      (f)
      (finally
        (.stop server 0)))))

(use-fixtures :once with-identity-provider)

(defn- token
  [& {:keys [signed-by issuer audience expires-in claims]
      :or {signed-by provider-keys
           audience "my-api"
           expires-in 300
           claims {}}}]
  (let [^KeyPair signing
        signed-by

        builder
        (JWT/create)]
    (.withKeyId builder "key-1")
    (.withIssuer builder ^String (or issuer (base-url)))
    (.withAudience builder ^"[Ljava.lang.String;" (into-array String [audience]))
    (.withExpiresAt builder (.plusSeconds (Instant/now) (long expires-in)))
    (.withSubject builder "sub-1")
    (doseq [[claim value] claims]
      (if (sequential? value)
        (.withClaim builder ^String (name claim) ^java.util.List (vec value))
        (.withClaim builder ^String (name claim) ^String (str value))))
    (.sign builder (Algorithm/RSA256 ^RSAPublicKey (.getPublic signing)
                                     ^RSAPrivateKey (.getPrivate signing)))))

(def ^:private base
  {:service-name :jwt-test-svc
   :environment-name-lower "local"
   :hosted-zone-name "example.com"
   :auth {:default-realm :main}})

(defn- seen-user
  [ctx _request]
  (select-keys (:meta ctx) [:user :realm]))

(defn- post
  [config headers & {:keys [body] :or {body {}}}]
  (let [response
        (runtime/handle-http (runtime/start base seen-user :filters [(sut/authenticate config)])
                             {:path "/"
                              :httpMethod "POST"
                              :headers headers
                              :body (util/to-json body)})]
    {:status (:statusCode response)
     :body (util/to-edn (:body response))}))

(defn- discovered
  []
  {:discovery-url (str (base-url) "/.well-known/openid-configuration")
   :audience "my-api"})

(deftest verified-token-becomes-the-user-test
  (let [response
        (post (discovered)
              {:X-Authorization (token :claims {:email "ann@example.com"
                                                :roles ["editor" "viewer"]})})]

    (is
     (= 200
        (:status response)))

    (is
     (= {:realm :main
         :user {:id "ann@example.com"
                :email "ann@example.com"
                :roles [:editor :viewer]
                :role :editor}}
        (:body response)))))

(deftest bearer-token-and-explicit-keys-test
  (let [response
        (post {:jwks-url (str (base-url) "/jwks.json")}
              {:authorization (str "Bearer " (token :claims {:email "ann@example.com"}))})]

    (is
     (= "ann@example.com"
        (get-in response [:body :user :id])))))

(deftest request-selects-one-of-its-roles-test
  (let [headers
        {:x-authorization (token :claims {:email "ann@example.com"
                                          :roles ["editor" "viewer"]})}]

    (is
     (= :viewer
        (get-in (post (discovered) headers :body {:user {:selected-role :viewer}})
                [:body :user :role])))

    (is
     (re-find #"Selecting non-existing role"
              (str (:body (post (discovered) headers :body {:user {:selected-role :admin}})))))))

(defn- role-of
  [roles & {:keys [body] :or {body {}}}]
  (get-in (post (discovered)
                {:x-authorization (token :claims {:email "ann@example.com"
                                                  :roles roles})}
                :body body)
          [:body :user :role]))

(deftest user-without-a-real-role-is-anonymous-test
  (testing "no roles at all"
    (is
     (= :anonymous
        (role-of []))))

  (testing ":anonymous and realm markers are not roles to act in"
    (is
     (= :anonymous
        (role-of ["anonymous" "realm-test"])))

    (is
     (= :editor
        (role-of ["anonymous" "realm-test" "editor"])))))

(deftest non-interactive-user-is-trusted-with-its-request-meta-test
  (let [response
        (post (discovered)
              {:x-authorization (token :claims {:email "svc@example.com"
                                                :roles ["editor" "non-interactive"]})}
              :body {:meta {:realm :other
                            :user {:id "caller"
                                   :role :editor}}})]

    (is
     (= {:realm :other
         :user {:id "caller"
                :role :editor}}
        (:body response)))))

(deftest claims-are-parsed-by-the-given-function-test
  (let [config
        (assoc (discovered)
               :claims->user (fn [claims]
                               {:id (:sub claims)
                                :role :member
                                :realm :tenant-a
                                :team (:team claims)}))

        response
        (post config {:x-authorization (token :claims {:team "blue"})})]

    (is
     (= {:realm :tenant-a
         :user {:id "sub-1"
                :role :member
                :team "blue"}}
        (:body response)))

    (testing "a user without :id is refused"
      (is
       (re-find #":claims->user has to return a user with :id"
                (str (:body (post (assoc config :claims->user (fn [_claims] {:role :member}))
                                  {:x-authorization (token)}))))))))

(deftest invalid-tokens-are-refused-test
  (doseq [[reason headers]
          {"no token" {}
           "expired" {:x-authorization (token :expires-in -600)}
           "another audience" {:x-authorization (token :audience "other-api")}
           "another issuer" {:x-authorization (token :issuer "https://evil.example")}
           "signed with another key" {:x-authorization (token :signed-by other-keys)}
           "not a token" {:x-authorization "forged"}}]
    (testing reason
      (let [response
            (post (discovered) headers)]

        (is
         (= 500
            (:status response)))

        (is
         (= "Not authenticated"
            (get-in response [:body :exception :message :message])))))))

(deftest undertow-request-is-authenticated-test
  (let [server
        (undertow/start base seen-user
                        :port 0
                        :host "127.0.0.1"
                        :consume []
                        :filters [(sut/authenticate (discovered))])

        request
        (.build
         (doto (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" (undertow/port server) "/")))
           (.header "X-Authorization" (token :claims {:email "ann@example.com"
                                                      :roles ["editor"]}))
           (.POST (HttpRequest$BodyPublishers/ofString "{}"))))]
    (try
      (let [response
            (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString))]

        (is
         (= 200
            (.statusCode response)))

        (is
         (= {:id "ann@example.com"
             :email "ann@example.com"
             :roles [:editor]
             :role :editor}
            (:user (util/to-edn (.body response))))))
      (finally
        (undertow/stop server)))))
