(ns edd.kafka.distributor
  "Apache Kafka transport. Topics are {env}.router and
   {env}.{stage}.{service}.commands|events. The message key is
   the group id, keeping every aggregate in order within one partition like
   an SQS FIFO message group. Records are committed one by one after the
   pipeline succeeds; a failed record is re-delivered by seeking back to it."
  (:require [clojure.tools.logging :as log]
            [edd.distribution :as distribution]
            [lambda.util :as util])
  (:import [java.time Duration]
           [java.util Collection Map Properties]
           [java.util.concurrent Future]
           [org.apache.kafka.clients.consumer Consumer ConsumerRecord KafkaConsumer OffsetAndMetadata]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.common TopicPartition]
           [org.apache.kafka.common.serialization StringDeserializer StringSerializer]))

(set! *warn-on-reflection* true)

(def ^:const message-size-limit
  "Below the 1048576 byte producer max.request.size and broker
   message.max.bytes defaults, leaving room for key and record overhead."
  1000000)

(defn- ->properties
  ^Properties [config]
  (let [properties
        (Properties.)]
    (doseq [[k v] config]
      (.put properties (name k) (str v)))
    properties))

(defn- bootstrap-servers
  [{:keys [bootstrap-servers]}]
  (or bootstrap-servers
      (util/get-env "KafkaBootstrapServers")
      (throw (ex-info "Kafka bootstrap servers missing; set KafkaBootstrapServers" {}))))

(defn producer-config
  [config]
  (merge {"bootstrap.servers" (bootstrap-servers config)
          "key.serializer" (.getName StringSerializer)
          "value.serializer" (.getName StringSerializer)
          "acks" "all"
          "enable.idempotence" "true"}
         (:producer config)))

(defn consumer-config
  [config ctx]
  (merge {"bootstrap.servers" (bootstrap-servers config)
          "group.id" (name (or (:service-name ctx)
                               (throw (ex-info "Kafka consumer needs :service-name as group id" {}))))
          "key.deserializer" (.getName StringDeserializer)
          "value.deserializer" (.getName StringDeserializer)
          "enable.auto.commit" "false"
          "auto.offset.reset" "earliest"
          "max.poll.records" "1"}
         (:consumer config)))

(defonce ^:private producers
  (atom {}))

(defn- new-producer
  ^Producer [config]
  (KafkaProducer. (->properties config)))

(defn- producer
  "One producer per configuration, shared by every thread of the service."
  ^Producer [kafka]
  (let [config
        (producer-config kafka)

        factory
        (get kafka :producer-factory new-producer)]
    (locking producers
      (or (get @producers [config factory])
          (get (swap! producers assoc [config factory] (factory config)) [config factory])))))

(defn- ->producer-record
  [destination {:keys [group-id body]}]
  (ProducerRecord. ^String destination (some-> group-id str) ^String body))

(defn ->record
  [^ConsumerRecord record]
  {:eventSource "edd:kafka"
   :topic (.topic record)
   :partition (.partition record)
   :offset (.offset record)
   :key (.key record)
   :body (.value record)})

(defn- publish-filter
  [kafka]
  (fn [_ctx {:keys [destination messages]} _chain]
    (let [kafka-producer
          (producer kafka)

          sent
          (mapv (fn [message]
                  [message (try
                             (.send kafka-producer (->producer-record destination message))
                             (catch Exception e
                               e))])
                messages)]
      (mapv (fn [[{:keys [id]} pending]]
              (try
                (if (instance? Exception pending)
                  (throw ^Exception pending)
                  (.get ^Future pending))
                {:id id
                 :success true}
                (catch Exception e
                  (log/error e "Kafka publish failed" {:destination destination :id id})
                  {:id id
                   :success false
                   :error (ex-message e)})))
            sent))))

(defn- consumer
  [kafka]
  (fn [ctx source]
    (let [factory
          (get kafka :consumer-factory #(KafkaConsumer. (->properties %)))

          ^Consumer kafka-consumer
          (factory (consumer-config kafka ctx))

          ^Collection topics
          [source]]
      (.subscribe kafka-consumer topics)
      {:poll (fn []
               (mapv (fn [^ConsumerRecord record]
                       {:record (->record record)
                        :partition (TopicPartition. (.topic record) (.partition record))
                        :offset (.offset record)})
                     (.poll kafka-consumer (Duration/ofSeconds 1))))
       :ack (fn [{:keys [partition offset]}]
              (let [^Map offsets
                    {partition (OffsetAndMetadata. (inc (long offset)))}]
                (.commitSync kafka-consumer offsets)))
       :retry (fn [{:keys [partition offset]}]
                (.seek kafka-consumer ^TopicPartition partition (long offset)))
       :close (fn []
                (.close kafka-consumer))})))

(defn response-destination
  [ctx]
  (str (:environment-name-lower ctx) ".router"))

(defn service-destination
  [ctx service stage type]
  (str (:environment-name-lower ctx) "." stage "." (name service) "." (name type)))

(defn- dead-letter
  [ctx source {:keys [record]}]
  (distribution/distribute! ctx
                            (str source ".dlq")
                            [{:id (str (:offset record))
                              :group-id (:key record)
                              :body (:body record)}]))

(defn register
  "Registers Kafka as transport. config: :bootstrap-servers (or
   KafkaBootstrapServers env), :producer and :consumer Kafka property maps,
   :producer-factory and :consumer-factory for tests."
  ([ctx]
   (register ctx {}))
  ([ctx config]
   (distribution/register-transport ctx :edd/kafka
                                    :max-message-size message-size-limit
                                    :publish-filter (publish-filter config)
                                    :consumer (consumer config)
                                    :response-destination response-destination
                                    :service-destination service-destination
                                    :dead-letter dead-letter)))
