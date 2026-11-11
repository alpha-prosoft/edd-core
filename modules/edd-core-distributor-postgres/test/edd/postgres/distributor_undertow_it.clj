(ns edd.postgres.distributor-undertow-it
  (:require [clojure.test :refer [deftest is]]
            [edd.core :as edd]
            [edd.dal :as dal]
            [edd.flyway :as flyway]
            [edd.memory.view-store :as view-store]
            [edd.postgres.event-store :as event-store]
            [edd.postgres.distributor :as distributor]
            [edd.postgres.distributor.outbox :as outbox]
            [edd.postgres.pool :as pool]
            [edd.undertow.core :as sut]
            [lambda.http :as http]
            [lambda.test.fixture.state :refer [*dal-state*]]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]))

(defn- service-ctx
  []
  (let [base
        {:service-name (keyword (str "undertow-local-" (uuid/gen)))
         :environment-name-lower "local"
         :hosted-zone-name "example.com"
         :realms [:test]}

        with-stores
        (view-store/register
         (event-store/register
          (distributor/register (flyway/register base))))

        with-commands
        (edd/reg-cmd
         (edd/reg-cmd with-stores :start (fn [_ctx cmd]
                                           {:event-id :started
                                            :id (:id cmd)}))
         :finish (fn [_ctx cmd]
                   {:event-id :finished
                    :id (:id cmd)}))]
    (edd/reg-event-fx with-commands :started (fn [_ctx event]
                                               {:cmd-id :finish
                                                :id (:id event)}))))

(defn- post!
  ^HttpResponse [url body]
  (.send (HttpClient/newHttpClient)
         (.build
          (doto (HttpRequest/newBuilder (URI. url))
            (.header "Content-Type" "application/json")
            (.POST (HttpRequest$BodyPublishers/ofString (util/to-json body)))))
         (HttpResponse$BodyHandlers/ofString)))

(defn- wait-for
  [pred]
  (loop [attempts 300]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do
              (Thread/sleep 10)
              (recur (dec attempts))))))

(defn- realm
  [ctx]
  (assoc ctx :meta {:realm :test}))

(defn- event-ids
  [ctx id]
  (pool/with-pool [ctx]
    (mapv :event-id (dal/get-events (assoc (realm ctx) :id id)))))

(defn- markers
  [ctx]
  (pool/with-pool [ctx]
    (outbox/markers (realm ctx))))

(deftest poller-delivers-effect-to-own-service-test
  (binding [*dal-state* (atom {})]
    (let [ctx
          (service-ctx)

          server
          (sut/start ctx edd/handler
                     :port 0
                     :host "127.0.0.1"
                     :filters [(http/authenticate (fn [_ctx]
                                                    {:id "tester" :role :tester :realm :test}))])

          id
          (uuid/gen)]
      (try
        (let [response
              (post! (str "http://127.0.0.1:" (sut/port server) "/command")
                     {:request-id (uuid/gen)
                      :interaction-id (uuid/gen)
                      :commands [{:cmd-id :start
                                  :id id}]})]

          (is
           (= 200
              (.statusCode response)))

          (is
           (wait-for #(= [:started :finished] (event-ids ctx id))))

          (is
           (wait-for #(empty? (markers ctx)))))
        (finally
          (sut/stop server))))))
