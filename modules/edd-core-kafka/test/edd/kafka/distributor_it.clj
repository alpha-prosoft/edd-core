(ns edd.kafka.distributor-it
  "Needs a broker: docker compose up kafka, KafkaBootstrapServers=127.0.0.1:9092."
  (:require [clojure.test :refer [deftest is]]
            [clojure.tools.logging :as log]
            [edd.distribution :as distribution]
            [edd.distribution.router :as router]
            [edd.kafka.distributor :as sut]
            [lambda.util :as util]
            [lambda.uuid :as uuid]))

(defn- wait-for
  [pred]
  (loop [attempts 600]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do
              (Thread/sleep 50)
              (recur (dec attempts))))))

(defn- consume-into
  [ctx source received]
  (distribution/start-consumer! ctx
                                source
                                (fn [record]
                                  (swap! received conj record)
                                  true)))

(defn- with-broker
  [f]
  (if (util/get-env "KafkaBootstrapServers")
    (f)
    (log/warn "KafkaBootstrapServers not set, skipping Kafka integration test")))

(defn- response-routed-through-kafka
  []
  (let [env
        (str "it" (subs (str (uuid/gen)) 0 8))

        ping-ctx
        (sut/register {:service-name :ping-svc
                       :environment-name-lower env})

        router-ctx
        (sut/register {:service-name :router-svc
                       :environment-name-lower env})

        pong-ctx
        (sut/register {:service-name :pong-svc
                       :environment-name-lower env})

        effect
        {:service :pong-svc
         :commands [{:cmd-id :pong :id (uuid/gen)}]
         :meta {:realm :test}}

        routed
        (atom [])

        stop-router
        (distribution/start-consumer! router-ctx
                                      (distribution/response-destination router-ctx)
                                      (fn [record]
                                        (swap! routed conj (router/handle-record router-ctx (util/to-edn (:body record))))
                                        true))

        received
        (atom [])

        stop-pong
        (consume-into pong-ctx (distribution/service-destination pong-ctx :pong-svc "test" :commands) received)]
    (try
      (distribution/distribute! ping-ctx
                                (distribution/response-destination ping-ctx)
                                [{:id "0"
                                  :body (util/to-json {:Records [{:service :ping-svc
                                                                  :resp {:effects [effect]}}]})}])

      (is
       (wait-for #(seq @received)))

      (is
       (= [{:success true}]
          @routed))

      (is
       (= effect
          (util/to-edn (:body (first @received)))))

      (is
       (= (str (get-in effect [:commands 0 :id]))
          (:key (first @received))))
      (finally
        (stop-router)
        (stop-pong)))))

(deftest response-routed-through-kafka-it
  (with-broker response-routed-through-kafka))

