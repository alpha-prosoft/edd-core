(ns edd.undertow.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.memory.event-store :as event-store]
            [edd.memory.view-store :as view-store]
            [edd.distribution :as distribution]
            [edd.distribution.memory :as memory]
            [edd.undertow.core :as sut]
            [lambda.http :as http]
            [lambda.http :as http]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [io.undertow.server HttpServerExchange]
           [io.undertow.util HttpString]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]))

(def broker
  (atom {}))

(defn- service-ctx
  []
  (let [base
        {:service-name :undertow-test-svc
         :environment-name-lower "local"
         :hosted-zone-name "example.com"
         :meta {:realm :test}}

        with-stores
        (event-store/register
         (view-store/register
          (memory/register base broker)))]
    (edd/reg-cmd with-stores
                 :create-thing
                 (fn [_ctx cmd]
                   {:event-id :thing-created
                    :id (:id cmd)
                    :attrs (:attrs cmd)}))))

(def ^:private authenticate
  (http/authenticate (fn [_ctx]
                       {:id "tester" :role :tester :realm :test})))

(defn- with-server
  [f & {:keys [consume]
        :or {consume []}}]
  (let [server
        (sut/start (service-ctx)
                   edd/handler
                   :port 0
                   :host "127.0.0.1"
                   :filters [authenticate]
                   :consume consume)]
    (try
      (f (str "http://127.0.0.1:" (sut/port server)))
      (finally
        (sut/stop server)))))

(defn- send!
  ^HttpResponse [^HttpRequest request]
  (.send (HttpClient/newHttpClient) request (HttpResponse$BodyHandlers/ofString)))

(defn- get!
  [url]
  (send! (.build (HttpRequest/newBuilder (URI. url)))))

(defn- post!
  [url body]
  (send! (.build
          (doto (HttpRequest/newBuilder (URI. url))
            (.header "Content-Type" "application/json")
            (.POST (HttpRequest$BodyPublishers/ofString (util/to-json body)))))))

(deftest health-check-test
  (with-server
    (fn [base-url]
      (let [response
            (get! (str base-url "/health"))]

        (is
         (= 200
            (.statusCode response)))

        (is
         (true?
          (:healthy (util/to-edn (.body response)))))))))

(deftest command-test
  (reset! broker {})
  (with-server
    (fn [base-url]
      (let [id
            (uuid/gen)

            response
            (post! (str base-url "/command")
                   {:request-id (uuid/gen)
                    :interaction-id (uuid/gen)
                    :meta {:realm :test}
                    :commands [{:cmd-id :create-thing
                                :id id
                                :attrs {:name "first"}}]})

            body
            (util/to-edn (.body response))]

        (testing "command runs through the HTTP pipeline as the authenticated user"
          (is
           (= 200
              (.statusCode response)))

          (is
           (= {:success true
               :events 1}
              (select-keys (:result body) [:success :events]))))

        (testing "response is distributed to the router queue like on Lambda"
          (is
           (= ["router"]
              (keys (memory/published (service-ctx))))))))))

(defn- wait-for
  [pred]
  (loop [attempts 300]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do
              (Thread/sleep 10)
              (recur (dec attempts))))))

(deftest consumes-service-source-test
  (reset! broker {})
  (distribution/distribute! (service-ctx)
                            "commands"
                            [{:id "1"
                              :body (util/to-json
                                     {:request-id (uuid/gen)
                                      :interaction-id (uuid/gen)
                                      :meta {:realm :test
                                             :user {:id "svc" :role :non-interactive}}
                                      :commands [{:cmd-id :create-thing
                                                  :id (uuid/gen)
                                                  :attrs {:name "queued"}}]})}])
  (with-server
    (fn [_base-url]
      (is
       (wait-for #(empty? (memory/messages (service-ctx) "commands"))))

      (is
       (wait-for #(seq (memory/messages (service-ctx) "router")))))
    :consume ["commands"]))

(defn- ctx-without-distributor
  []
  {:service-name :no-distributor-svc
   :environment-name-lower "local"
   :hosted-zone-name "example.com"})

(defn- post-to-server!
  [handler body]
  (let [server
        (sut/start (ctx-without-distributor)
                   handler
                   :port 0
                   :host "127.0.0.1"
                   :filters [authenticate])]
    (try
      (post! (str "http://127.0.0.1:" (sut/port server) "/") body)
      (finally
        (sut/stop server)))))

(deftest edd-request-without-distributor-fails-test
  (let [response
        (post-to-server! edd/handler {:request-id (uuid/gen)
                                      :interaction-id (uuid/gen)
                                      :query {:query-id :get-thing}})]
    (is
     (= 500
        (.statusCode response)))

    (is
     (re-find #"No distributor registered"
              (str (.body response))))))

(deftest own-handler-runs-without-distributor-test
  (let [response
        (post-to-server! (fn [_ctx request]
                           {:echo (:value request)})
                         {:value "hello"})]
    (is
     (= 200
        (.statusCode response)))

    (is
     (= {:echo "hello"}
        (util/to-edn (.body response))))))

(deftest features-renew-the-ctx-of-every-invocation-test
  (let [renewals
        (atom 0)

        seen
        (atom [])

        ctx
        (edd/reg-feature (memory/register (ctx-without-distributor) (atom {}))
                         :test/renewing
                         :deps {}
                         :invocation-ctx (fn [ctx]
                                           (assoc ctx :renewed (swap! renewals inc))))

        handler
        (fn [ctx request]
          (swap! seen conj [(:from request) (:renewed ctx)])
          {:renewed (:renewed ctx)})

        server
        (sut/start ctx
                   handler
                   :port 0
                   :host "127.0.0.1"
                   :filters [authenticate]
                   :consume ["commands"])

        url
        (str "http://127.0.0.1:" (sut/port server) "/")]
    (try
      (testing "every HTTP request gets a renewed ctx"
        (is
         (< (long (:renewed (util/to-edn (.body (post! url {:from :http})))))
            (long (:renewed (util/to-edn (.body (post! url {:from :http}))))))))

      (testing "so does every consumed record"
        (distribution/distribute! ctx "commands" [{:id "1"
                                                   :body (util/to-json {:from :queue})}])

        (is
         (wait-for #(some (fn [[from _renewed]] (= :queue from)) @seen)))

        (is
         (< (long (apply max (map second (filter (fn [[from _renewed]] (= :http from)) @seen))))
            (long (second (first (filter (fn [[from _renewed]] (= :queue from)) @seen)))))))
      (finally
        (sut/stop server)))))

(defn- with-listeners
  [listeners f]
  (let [server
        (sut/start (service-ctx)
                   edd/handler
                   :filters [authenticate]
                   :listeners listeners
                   :consume [])]
    (try
      (f server)
      (finally
        (sut/stop server)))))

(defn- url
  [server listener path]
  (str "http://127.0.0.1:" (sut/port server listener) path))

(defn- create-thing
  []
  {:request-id (uuid/gen)
   :interaction-id (uuid/gen)
   :meta {:realm :test}
   :commands [{:cmd-id :create-thing
               :id (uuid/gen)
               :attrs {:name "first"}}]})

(defn- answer!
  [^HttpServerExchange exchange ^String text]
  (.put (.getResponseHeaders exchange) (HttpString. "X-Listener") "own")
  (with-open [out (.getOutputStream exchange)]
    (.write out (.getBytes text "UTF-8"))))

(deftest listener-handler-gets-the-runtime-and-the-exchange-test
  (let [seen
        (atom nil)

        echo
        (fn [runtime ^HttpServerExchange exchange]
          (reset! seen {:runtime runtime
                        :exchange exchange
                        :io-thread? (.isInIoThread exchange)})
          (answer! exchange (str (.getRequestMethod exchange)
                                 " "
                                 (.getRequestPath exchange)
                                 " "
                                 (slurp (.getInputStream exchange)))))]
    (with-listeners {:default {:port 0 :host "127.0.0.1"}
                     :public {:port 0 :host "127.0.0.1" :handler echo}}
      (fn [server]
        (let [response
              (post! (url server :public "/anything") {:value 1})]

          (is
           (= "POST /anything {\"value\":1}"
              (.body response)))

          (is
           (= "own"
              (.orElse (.firstValue (.headers response) "X-Listener") nil)))

          (is
           (identical? server
                       (:runtime @seen)))

          (is
           (instance? HttpServerExchange
                      (:exchange @seen)))

          (is
           (false?
            (:io-thread? @seen))))

        (testing "the default listener still serves edd requests"
          (is
           (true?
            (:healthy (util/to-edn (.body (get! (url server :default "/health")))))))

          (is
           (= (sut/port server)
              (sut/port server :default))))

        (testing "every listener has its own port"
          (is
           (not= (sut/port server :default)
                 (sut/port server :public))))))))

(deftest listener-handler-delegates-to-edd-test
  (reset! broker {})
  (let [routed
        (fn [runtime ^HttpServerExchange exchange]
          (if (= "/api/command" (.getRequestPath exchange))
            (sut/handle-edd runtime exchange)
            (answer! exchange "own")))]
    (with-listeners {:default false
                     :public {:port 0 :host "127.0.0.1" :handler routed}}
      (fn [server]
        (is
         (= {:success true
             :events 1}
            (select-keys (:result (util/to-edn (.body (post! (url server :public "/api/command") (create-thing)))))
                         [:success :events])))

        (is
         (= "own"
            (.body (get! (url server :public "/other")))))

        (is
         (= ["router"]
            (keys (memory/published (service-ctx)))))))))

(deftest handle-request-runs-in-process-test
  (reset! broker {})
  (with-listeners {:default false}
    (fn [server]
      (let [response
            (sut/handle-request server {:path "/command"
                                        :httpMethod "POST"
                                        :headers {}
                                        :body (util/to-json (create-thing))})]
        (is
         (= 200
            (:statusCode response)))

        (is
         (= {:success true
             :events 1}
            (select-keys (:result (util/to-edn (:body response))) [:success :events])))

        (testing "as the authenticated user, distributed like an HTTP request"
          (is
           (= ["router"]
              (keys (memory/published (service-ctx)))))))

      (testing "a request edd refuses answers like the default listener"
        (is
         (= 500
            (:statusCode (sut/handle-request server {:path "/query"
                                                     :httpMethod "POST"
                                                     :headers {}
                                                     :body "not json"}))))))))

(deftest handle-request-throws-what-handle-edd-answers-test
  (with-listeners {:default {:port 0 :host "127.0.0.1"}}
    (fn [server]
      (with-redefs [http/handle (fn [_ctx _request]
                                  (throw (ex-info "Distribution failed" {})))]
        (is
         (thrown-with-msg? clojure.lang.ExceptionInfo
                           #"Distribution failed"
                           (sut/handle-request server {:path "/command"
                                                       :httpMethod "POST"
                                                       :headers {}
                                                       :body (util/to-json (create-thing))})))

        (let [response
              (post! (url server :default "/command") (create-thing))]
          (is
           (= 500
              (.statusCode response)))

          (is
           (= {:error "Distribution failed"}
              (util/to-edn (.body response)))))))))

(deftest no-default-listener-test
  (with-listeners {:default false
                   :public {:port 0 :host "127.0.0.1" :handler (fn [_runtime exchange] (answer! exchange "own"))}}
    (fn [server]
      (is
       (= [:public]
          (keys (:servers server))))

      (testing "the only listener is the one port names"
        (is
         (= (sut/port server :public)
            (sut/port server))))))

  (with-listeners {:default nil}
    (fn [server]
      (is
       (empty?
        (:servers server))))))

(deftest default-listener-with-its-own-handler-test
  (with-listeners {:default {:port 0 :host "127.0.0.1" :handler (fn [_runtime exchange] (answer! exchange "own"))}}
    (fn [server]
      (is
       (= "own"
          (.body (get! (url server :default "/health"))))))))

(deftest listener-limits-request-bodies-test
  (let [echo
        (fn [_runtime ^HttpServerExchange exchange]
          (answer! exchange (str (count (slurp (.getInputStream exchange))))))]
    (with-listeners {:default false
                     :small {:port 0 :host "127.0.0.1" :handler echo :max-entity-size 64}
                     :large {:port 0 :host "127.0.0.1" :handler echo}}
      (fn [server]
        (let [body
              {:value (apply str (repeat 200 "a"))}]
          (is
           (= 413
              (.statusCode (post! (url server :small "/") body))))

          (is
           (= 200
              (.statusCode (post! (url server :large "/") body)))))))))

(deftest listener-without-handler-is-refused-test
  (is
   (thrown-with-msg? clojure.lang.ExceptionInfo
                     #"Listener :public needs a :port and a :handler"
                     (sut/start (service-ctx)
                                edd/handler
                                :listeners {:default false
                                            :public {:port 0}}
                                :consume []))))
