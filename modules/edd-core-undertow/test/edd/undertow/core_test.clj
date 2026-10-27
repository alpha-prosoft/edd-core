(ns edd.undertow.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.memory.event-store :as event-store]
            [edd.memory.view-store :as view-store]
            [edd.distribution :as distribution]
            [edd.distribution.memory :as memory]
            [edd.undertow.core :as sut]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [java.net URI]
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
    (edd/reg-cmd (edd/reg-feature with-stores :test/auth
                                  :deps {}
                                  :resolve-user (fn [_ctx]
                                                  {:id "tester" :role :tester :realm :test}))
                 :create-thing
                 (fn [_ctx cmd]
                   {:event-id :thing-created
                    :id (:id cmd)
                    :attrs (:attrs cmd)}))))

(defn- with-server
  [f & {:keys [consume]
        :or {consume []}}]
  (let [server
        (sut/start (service-ctx)
                   edd/handler
                   :port 0
                   :host "127.0.0.1"
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
  (edd/reg-feature {:service-name :no-distributor-svc
                    :environment-name-lower "local"
                    :hosted-zone-name "example.com"}
                   :test/auth
                   :deps {}
                   :resolve-user (fn [_ctx]
                                   {:id "tester" :role :tester :realm :test})))

(defn- post-to-server!
  [handler body]
  (let [server
        (sut/start (ctx-without-distributor)
                   handler
                   :port 0
                   :host "127.0.0.1")]
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
