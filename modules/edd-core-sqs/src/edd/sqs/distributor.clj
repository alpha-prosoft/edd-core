(ns edd.sqs.distributor
  "Amazon SQS distributor. Destinations are queue names in the account and
   region of the ctx credentials."
  (:require [aws.ctx :as aws-ctx]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [edd.distribution :as distribution]
            [edd.response.s3 :as response-cache]
            [lambda.util :as util]
            [sdk.aws.sqs :as sqs]))

(def ^:const max-batch-count
  10)

(def retry-visibility-seconds
  "Back-off before a failed message is received again, like the visibility
   timeout of a Lambda trigger, so short outages do not exhaust the
   redrive maxReceiveCount."
  30)

(def long-poll-seconds
  "Stays below the 5 s idle timeout of the first HTTP attempt."
  4)

(defn- body-size
  ^long [{:keys [body]}]
  (distribution/byte-size body))

(defn partition-messages
  "Batches of at most 10 messages and limit bytes. A message that is too big
   on its own gets a batch of one, which sqs-publish offloads to S3."
  [messages limit]
  (loop [[message & more :as remaining] messages
         batch []
         batch-size 0
         batches []]
    (if (empty? remaining)
      (cond-> batches
        (seq batch) (conj batch))
      (let [size
            (body-size message)

            full?
            (or (= (count batch) max-batch-count)
                (> (+ batch-size size) (long limit)))]
        (if (and full? (seq batch))
          (recur remaining [] 0 (conj batches batch))
          (recur more (conj batch message) (+ batch-size size) batches))))))

(defn- publish-batch
  [ctx queue batch]
  (try
    (if (= 1 (count batch))
      (let [{:keys [id body group-id deduplication-id] :as message}
            (first batch)]
        (sqs/sqs-publish (assoc ctx
                                :queue queue
                                :message (if (or group-id deduplication-id)
                                           message
                                           body)))
        [{:id id
          :success true}])
      (vec (sqs/sqs-publish-batch (assoc ctx
                                         :queue queue
                                         :messages batch))))
    (catch Exception e
      (log/error e "SQS publish failed" {:queue queue})
      (mapv (fn [{:keys [id]}]
              {:id id
               :success false})
            batch))))

(defn ->record
  [{:keys [aws]} queue {:keys [body message-id receipt-handle receive-count]}]
  {:eventSource "aws:sqs"
   :eventSourceARN (str "arn:aws:sqs:" (:region aws) ":" (:account-id aws) ":" queue)
   :messageId message-id
   :receiptHandle receipt-handle
   :body body
   :attributes {:ApproximateReceiveCount (str (or receive-count "1"))}})

(defn- queue-of
  [{:keys [eventSourceARN]}]
  (last (str/split eventSourceARN #":")))

(defn- publish-filter
  [limit]
  (fn [ctx {:keys [destination messages]} _chain]
    (let [limited
          (assoc ctx :sqs {:max-message-size limit})]
      (loop [[batch & more] (partition-messages messages limit)
             results []]
        (if-not batch
          results
          (let [batch-results
                (publish-batch limited destination batch)

                all-results
                (into results batch-results)]
            (if (seq (distribution/failed-ids batch batch-results))
              (into all-results (map #(hash-map :id (:id %) :success false) (apply concat more)))
              (recur more all-results))))))))

(defn- consumer
  [ctx queue]
  {:poll (fn []
           (let [fresh
                 (distribution/current-ctx ctx)]
             (mapv (fn [message]
                     {:record (->record fresh queue message)})
                   (sqs/sqs-receive (assoc fresh
                                           :queue queue
                                           :wait-time-seconds long-poll-seconds)))))
   :ack (fn [{:keys [record]}]
          (let [results
                (sqs/delete-message-batch (assoc (distribution/current-ctx ctx)
                                                 :queue queue
                                                 :messages [record]))]
            (when (some #{0} results)
              (throw (ex-info "Deleting SQS message failed" {:queue queue
                                                             :message-id (:messageId record)})))))
   :retry (fn [{:keys [record]}]
            (sqs/change-message-visibility (assoc (distribution/current-ctx ctx)
                                                  :queue queue
                                                  :receipt-handle (:receiptHandle record)
                                                  :visibility-timeout retry-visibility-seconds)))
   :close (fn [])})

(defn response-destination
  [ctx]
  (str (:environment-name-lower ctx)
       "-"
       (or (get-in ctx [:infra :router :queue])
           "glms-router-svc-response")))

(defn service-destination
  [ctx service stage type]
  (str/join "-" [(:environment-name-lower ctx)
                 stage
                 (name service)
                 (case type
                   :commands "commands-1.fifo"
                   :events "events-1.fifo")]))

(defn- unwrap
  "Bodies too big for SQS were parked in S3 by sqs/sqs-publish."
  [ctx body]
  (if (:s3 body)
    (response-cache/fetch-part ctx body)
    body))

(defn- ack-delivered
  [ctx records]
  (doseq [[queue queue-records] (group-by queue-of records)]
    (sqs/delete-message-batch (assoc ctx
                                     :queue queue
                                     :messages queue-records))))

(defn register
  "Registers SQS as transport. :max-message-size (or SqsMaxMessageSize env)
   is for queues created with a MaximumMessageSize below the 1 MiB default."
  ([ctx]
   (register ctx {}))
  ([ctx {:keys [max-message-size]}]
   (let [from-env
         (util/get-env "SqsMaxMessageSize")

         limit
         (or max-message-size
             (when from-env
               (parse-long from-env))
             sqs/max-message-size)]
     (distribution/register-transport (aws-ctx/register ctx) :edd/sqs
                                      :max-message-size limit
                                      :publish-filter (publish-filter limit)
                                      :consumer consumer
                                      :response-destination response-destination
                                      :service-destination service-destination
                                      :unwrap unwrap
                                      :ack-delivered ack-delivered))))
