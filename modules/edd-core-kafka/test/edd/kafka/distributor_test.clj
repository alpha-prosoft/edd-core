(ns edd.kafka.distributor-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.distribution :as distribution]
            [edd.kafka.distributor :as sut])
  (:import [org.apache.kafka.clients.consumer ConsumerRecord MockConsumer]
           [org.apache.kafka.clients.producer MockProducer ProducerRecord]
           [org.apache.kafka.common TopicPartition]
           [org.apache.kafka.common.serialization StringSerializer]))

(def topic
  "dev-prod-order-svc-commands-1.fifo")

(def partition-0
  (TopicPartition. topic 0))

(defn- ctx
  [config]
  (sut/register {:service-name :order-svc}
                (merge {:bootstrap-servers "mock:9092"} config)))

(defn- mock-consumer-with
  [bodies]
  (let [consumer
        (MockConsumer. "earliest")]
    (.schedulePollTask consumer
                       (fn []
                         (.rebalance consumer [partition-0])
                         (.updateBeginningOffsets consumer {partition-0 0})
                         (doseq [[offset body] (map-indexed vector bodies)]
                           (.addRecord consumer (ConsumerRecord. topic 0 (long offset) "group" body)))))
    consumer))

(defn- wait-for
  [pred]
  (loop [attempts 300]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do
              (Thread/sleep 10)
              (recur (dec attempts))))))

(defn- committed-offset
  [^MockConsumer consumer]
  (some-> (.committed consumer #{partition-0})
          (get partition-0)
          (.offset)))

(deftest publish-test
  (let [producer
        (MockProducer. true nil (StringSerializer.) (StringSerializer.))

        publish-ctx
        (ctx {:producer-factory (constantly producer)})]

    (is
     (= [{:id "a" :success true}
         {:id "b" :success true}]
        (distribution/publish! publish-ctx
                               topic
                               [{:id "a" :group-id "agg-1" :body "1"}
                                {:id "b" :body "2"}])))

    (testing "group id becomes the key, so an aggregate stays on one partition"
      (is
       (= [[topic "agg-1" "1"] [topic nil "2"]]
          (map (fn [^ProducerRecord record]
                 [(.topic record) (.key record) (.value record)])
               (.history producer)))))))

(deftest consumer-config-test
  (is
   (= {"group.id" "order-svc"
       "enable.auto.commit" "false"
       "max.poll.records" "1"
       "fetch.max.bytes" "100"}
      (select-keys (sut/consumer-config {:bootstrap-servers "x" :consumer {"fetch.max.bytes" "100"}}
                                        {:service-name :order-svc})
                   ["group.id" "enable.auto.commit" "max.poll.records" "fetch.max.bytes"]))))

(deftest consume-and-commit-test
  (let [consumer
        (mock-consumer-with ["{\"commands\":[]}"])

        handled
        (atom [])

        stop
        (distribution/start-consumer! (ctx {:consumer-factory (constantly consumer)})
                                      topic
                                      (fn [record]
                                        (swap! handled conj record)
                                        true))]
    (is
     (wait-for #(= 1 (committed-offset consumer))))

    (stop)

    (is
     (= [{:eventSource "edd:kafka"
          :topic topic
          :partition 0
          :offset 0
          :key "group"
          :body "{\"commands\":[]}"}]
        @handled))))

(deftest retry-seeks-back-to-failed-record-test
  (let [consumer
        (mock-consumer-with ["first"])

        consumer-ctx
        (ctx {:consumer-factory (constantly consumer)})

        adapter
        ((:consumer (distribution/transport consumer-ctx)) consumer-ctx topic)

        [message]
        ((:poll adapter))]

    (is
     (= 1
        (.position consumer partition-0)))

    ((:retry adapter) message)

    (is
     (= 0
        (.position consumer partition-0)))

    (is
     (nil?
      (committed-offset consumer)))))

(deftest message-limit-test
  (is
   (= sut/message-size-limit
      (distribution/max-message-size (ctx {})))))

(deftest failing-send-only-fails-that-message-test
  (let [producer
        (proxy [MockProducer] [true nil (StringSerializer.) (StringSerializer.)]
          (send
            ([^ProducerRecord record]
             (if (= "boom" (.value record))
               (throw (ex-info "broker rejected" {}))
               (proxy-super send record)))
            ([record callback]
             (proxy-super send record callback))))

        publish-ctx
        (ctx {:producer-factory (constantly producer)})]
    (is
     (= [{:id "ok" :success true}
         {:id "bad" :success false :error "broker rejected"}]
        (distribution/publish! publish-ctx
                               topic
                               [{:id "ok" :body "1"}
                                {:id "bad" :body "boom"}])))))
