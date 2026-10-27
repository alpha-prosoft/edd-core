(ns edd.sqs.distributor-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.distribution :as distribution]
            [edd.distribution.router]
            [edd.sqs.distributor :as sut]
            [edd.test.fixture.dal :as mock]
            [lambda.core :as core]
            [lambda.request :as request]
            [lambda.test.fixture.client :as client]
            [lambda.test.fixture.core :refer [mock-core]]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [sdk.aws.s3 :as s3]
            [sdk.aws.sqs :as sqs]))

(def ctx
  (sut/register {:service-name :local-test
                 :environment-name-lower "local"
                 :aws {:region "eu-west-1" :account-id "123"}}))

(defn- message
  [id size]
  {:id id
   :body (apply str (repeat size "x"))})

(deftest partition-messages-test
  (testing "at most 10 messages per batch"
    (is
     (= [10 10 5]
        (map count (sut/partition-messages (map #(message (str %) 1) (range 25)) sqs/max-message-size)))))

  (testing "batches stay within 1 MiB"
    (is
     (= [1 1]
        (map count (sut/partition-messages [(message "a" 600000) (message "b" 600000)] sqs/max-message-size)))))

  (testing "a message too big on its own gets its own batch"
    (is
     (= [["small"] ["huge"] ["small-2"]]
        (map #(map :id %) (sut/partition-messages [(message "small" 10)
                                                   (message "huge" 2000000)
                                                   (message "small-2" 10)]
                                                  sqs/max-message-size))))))

(deftest publish-test
  (let [singles
        (atom [])

        batches
        (atom [])]
    (with-redefs [sqs/sqs-publish (fn [{:keys [queue message]}]
                                    (swap! singles conj [queue message])
                                    {:success true})
                  sqs/sqs-publish-batch (fn [{:keys [queue messages]}]
                                          (swap! batches conj [queue (count messages)])
                                          (map (fn [{:keys [id]}] {:id id :success true}) messages))]
      (is
       (= [{:success true :id "only"}]
          (distribution/publish! ctx "q" [(message "only" 1)])))

      (is
       (= 12
          (count (distribution/publish! ctx "q" (map #(message (str %) 1) (range 12)))))))

    (is
     (= [["q" "x"]]
        @singles))

    (is
     (= [["q" 10] ["q" 2]]
        @batches))))

(deftest distribute-response-test
  (binding [request/*request* (atom {:router-payloads ["{\"n\":1}" "{\"n\":2}"]})]
    (let [published
          (atom [])]
      (with-redefs [sqs/sqs-publish (fn [{:keys [queue message]}]
                                      (swap! published conj [queue (util/to-edn message)])
                                      {:success true})]
        (distribution/distribute-response! ctx))

      (is
       (= [["local-glms-router-svc-response" {:Records [{:n 1}]}]
           ["local-glms-router-svc-response" {:Records [{:n 2}]}]]
          @published)))))

(deftest parked-body-is-unwrapped-test
  (with-redefs [s3/get-object (fn [_ctx object]
                                (is
                                 (= {:s3 {:object {:key "sqs-content/big.json"}}}
                                    object))
                                (java.io.ByteArrayInputStream. (.getBytes "{\"commands\":[]}" "UTF-8")))]
    (is
     (= {:commands []}
        (distribution/unwrap ctx {:s3 {:object {:key "sqs-content/big.json"}}}))))

  (with-redefs [s3/get-object (fn [_ _] {:error {:status 403}})]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Unable to fetch parked message"
      (distribution/unwrap ctx {:s3 {:object {:key "k"}}})))))

(deftest ack-delivered-groups-by-queue-test
  (let [deleted
        (atom [])]
    (with-redefs [sqs/delete-message-batch (fn [{:keys [queue messages]}]
                                             (swap! deleted conj [queue (map :messageId messages)]))]
      (distribution/acknowledge-delivered! ctx [{:messageId "1" :eventSourceARN "arn:aws:sqs:r:a:queue-a"}
                                                {:messageId "2" :eventSourceARN "arn:aws:sqs:r:a:queue-b"}
                                                {:messageId "3" :eventSourceARN "arn:aws:sqs:r:a:queue-a"}]))
    (is
     (= #{["queue-a" ["1" "3"]] ["queue-b" ["2"]]}
        (set @deleted)))))

(deftest lambda-queue-trigger-publishes-response-test
  (let [request-id
        (uuid/gen)

        cmd
        {:request-id request-id
         :interaction-id (uuid/gen)
         :meta {:realm :test}
         :commands [{:cmd-id :ping
                     :id (uuid/gen)}]}]
    (mock/with-mock-dal
      (mock-core
       :env {"Region" "eu-west-1"}
       :invocations [(util/to-json {:Records [{:eventSource "aws:sqs"
                                               :eventSourceARN "arn:aws:sqs:eu-west-1:local:local-test-local-test-commands-1.fifo"
                                               :messageId "m-1"
                                               :receiptHandle "r-1"
                                               :body (util/to-json cmd)}]})]
       (core/start
        (sut/register (edd/reg-cmd mock/ctx :ping (fn [_ cmd] {:event-id :ping :id (:id cmd)})))
        edd/handler)

       (let [{:keys [url body]}
             (second (client/traffic-edn))]
         (is
          (= "https://sqs.eu-west-1.amazonaws.com/local/local-glms-router-svc-response"
             url))

         (is
          (= [request-id]
             (map :request-id
                  (get-in (util/to-edn (java.net.URLDecoder/decode (subs body (count "Action=SendMessage&MessageBody=")) "UTF-8"))
                          [:Records 0 :resp :events])))))))))

(deftest configurable-limit-test
  (testing "older queues with a 256 KiB MaximumMessageSize"
    (let [limited
          (sut/register {} {:max-message-size 262144})]
      (is
       (= 262144
          (distribution/max-message-size limited)))

      (is
       (= [1 1]
          (map count (sut/partition-messages [(message "a" 200000) (message "b" 200000)] 262144))))

      (is
       (sqs/is-message-too-big? {:sqs {:max-message-size 262144}} (apply str (repeat 300000 "x")))))))

(deftest consumer-retry-releases-message-test
  (let [changed
        (atom nil)

        sqs-consumer
        ((:consumer (distribution/transport ctx)) ctx "q")]
    (with-redefs [sqs/change-message-visibility (fn [request]
                                                  (reset! changed (select-keys request [:queue :receipt-handle :visibility-timeout])))]
      ((:retry sqs-consumer) {:record {:receiptHandle "r-1"}}))

    (is
     (= {:queue "q" :receipt-handle "r-1" :visibility-timeout sut/retry-visibility-seconds}
        @changed))))

(deftest failed-delete-is-reported-test
  (let [sqs-consumer
        ((:consumer (distribution/transport ctx)) ctx "q")]
    (with-redefs [sqs/delete-message-batch (fn [_] [0])]
      (is
       (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Deleting SQS message failed"
        ((:ack sqs-consumer) {:record {:messageId "m-1"}}))))))

(deftest receive-count-is-reported-test
  (is
   (= {:ApproximateReceiveCount "3"}
      (:attributes (sut/->record ctx "q" {:body "{}" :message-id "m" :receipt-handle "r" :receive-count "3"}))))

  (is
   (= {:ApproximateReceiveCount "1"}
      (:attributes (sut/->record ctx "q" {:body "{}" :message-id "m" :receipt-handle "r"})))))

(deftest change-message-visibility-request-test
  (let [posted
        (atom nil)]
    (with-redefs [util/http-post (fn [url request]
                                   (reset! posted [url (:body request)])
                                   {:status 200})]
      (sqs/change-message-visibility {:aws {:region "eu-west-1" :account-id "123"}
                                      :queue "q"
                                      :receipt-handle "a+b"
                                      :visibility-timeout 0}))
    (is
     (= ["https://sqs.eu-west-1.amazonaws.com/123/q"
         "Action=ChangeMessageVisibility&QueueUrl=https://sqs.eu-west-1.amazonaws.com/123/q&ReceiptHandle=a%2Bb&VisibilityTimeout=0&Version=2012-11-05"]
        @posted))))

(deftest delete-result-matches-consumer-records-test
  (with-redefs [util/http-post (fn [_url _request]
                                 {:status 200
                                  :body {:DeleteMessageBatchResponse
                                         {:DeleteMessageBatchResult
                                          {:Failed [{:Id "m-1"}]}}}})]
    (is
     (= [0]
        (sqs/delete-message-batch {:aws {:region "eu-west-1" :account-id "123"}
                                   :queue "q"
                                   :messages [{:messageId "m-1" :receiptHandle "r-1"}]})))))

(deftest failed-batch-holds-back-later-batches-test
  (let [sent
        (atom [])]
    (with-redefs [sqs/sqs-publish-batch (fn [{:keys [messages]}]
                                          (swap! sent conj (map :id messages))
                                          (map (fn [{:keys [id]}] {:id id :success false}) messages))]
      (is
       (= 12
          (count (remove :success (distribution/publish! ctx "q"
                                                         (map #(message (str %) 1) (range 12))))))))
    (is
     (= 1
        (count @sent)))))

(deftest destination-names-test
  (is
   (= "local-glms-router-svc-response"
      (distribution/response-destination ctx)))

  (is
   (= "local-router-svc-response"
      (distribution/response-destination (assoc-in ctx [:infra :router :queue] "router-svc-response"))))

  (is
   (= ["local-prod-local-test-commands-1.fifo"
       "local-prod-local-test-events-1.fifo"
       "local-test-local-test-commands-1.fifo"
       "local-test-local-test-events-1.fifo"]
      (distribution/service-sources ctx))))

(deftest router-reads-old-s3-cache-references-test
  (let [effect
        {:service :pong-svc
         :commands [{:cmd-id :pong :id #uuid "22222222-2222-2222-2222-222222222222"}]
         :meta {:realm :test}}

        published
        (atom [])]
    (with-redefs [s3/get-object (fn [_ctx _object]
                                  (java.io.ByteArrayInputStream.
                                   (.getBytes (util/to-json {:service :ping-svc
                                                             :resp {:effects [effect]}})
                                              "UTF-8")))
                  sqs/sqs-publish (fn [{:keys [queue message]}]
                                    (swap! published conj [queue (util/to-edn (:body message))])
                                    {:success true})]
      (is
       (= {:success true}
          (edd.distribution.router/handle-record ctx {:Records [{:s3 {:object {:key "response/r/0/ping-svc.json"}}}]}))))

    (is
     (= [["local-test-pong-svc-commands-1.fifo" effect]]
        @published))))
