(ns edd.commands-batch-test
  (:require [clojure.test :refer [deftest is]]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [lambda.test.fixture.client :as client]
            [lambda.test.fixture.core :refer [mock-core] :as core-mock]
            [edd.core :as edd]
            [lambda.filters :as filters]
            [edd.test.fixture.dal :as mock]
            [aws.runtime :as runtime]
            [sdk.aws.common :as common]
            [sdk.aws.sqs :as sqs]))

(def agg-id (uuid/gen))

(def req-id1 (uuid/gen))
(def req-id2 (uuid/gen))
(def req-id3 (uuid/gen)) 3

(def req-id4 (uuid/gen)) 3
(def req-id5 (uuid/gen)) 3
(def int-id (uuid/gen))

(defn req
  [items]
  {:Records
   (vec
    (map-indexed
     (fn [idx it]
       {:md5OfBody         "fff479f1b3e7bae94d4fbb22f1b2cce0",
        :eventSourceARN    "arn:aws:sqs:eu-central-1:11111111111:test-evets-queue",
        :awsRegion         "eu-central-1",
        :messageId         (str "id-" (inc idx)),
        :eventSource       "aws:sqs",
        :messageAttributes {},
        :body              (util/to-json it)
        :receiptHandle     (str "handle-" (inc idx)),
        :attributes        {:ApproximateReceiveCount "1",
                            :SentTimestamp           "1580103331238",
                            :SenderId                "AIDAISDDSWNBEXIA6J64K",
                            :ApproximateFirstReceiveTimestamp
                            "1580103331242"}})
     items))})

(def ctx
  (-> mock/ctx
      (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                            {:id       (:id cmd)
                             :event-id :event-1
                             :name     (:name cmd)}))
      (edd/reg-cmd :cmd-2 (fn [_ctx _cmd]
                            {:error "failed"}))
      (edd/reg-cmd :cmd-3 (fn [_ctx _cmd]
                            (throw (ex-info "OMG"
                                            {:failed "have I!"}))))
      (edd/reg-cmd :cmd-4 (fn [_ctx cmd]
                            {:id       (:id cmd)
                             :event-id :event-4
                             :name     (:name cmd)})
                   :deps {:test (fn [_cmd _query]
                                  {:query-id :query-4})})
      (edd/reg-query :query-4 (fn [_ctx _quers]
                                (throw (ex-info "DEPS" {:failed "yes"}))))
      (edd/reg-event :event-1
                     (fn [agg _event]
                       (merge agg
                              {:value "1"})))
      (edd/reg-event :event-2
                     (fn [agg _event]
                       (merge agg
                              {:value "2"})))))

(deftest test-command-handler-returns-error
  (let [messages (atom [])]
    (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                    (swap! messages #(conj % message)))]
      (mock-core
       {:requests [{:method :post
                    :url "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                    :response {:body "{}"}}]}
       (runtime/lambda-requests
        ctx
        edd/handler
        [(req
          [{:request-id     req-id1
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD1"}]}
           {:request-id     req-id2
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-2
                              :name   "CMD2"}]}
           {:request-id     req-id3
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD3"}]}])]
        :filters [filters/from-queue])

       (is (= [{:body   [{:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id1
                          :interaction-id int-id}
                         {:error          [{:error "failed",
                                            :id    agg-id}],
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id2
                          :interaction-id int-id}
                         {:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id core-mock/inocation-id-0
                          :request-id     req-id3
                          :interaction-id int-id}]
                :method :post
                :url    core-mock/response-endpoint-o}]
              (client/traffic-edn))))
      (is (= [{:Records [{:key (str "response/"
                                    req-id3
                                    "/0/local-svc.json")}]}
              {:Records [{:key (str "response/"
                                    req-id2
                                    "/0/local-svc.json")}]}
              {:Records [{:key (str "response/"
                                    req-id1
                                    "/0/local-svc.json")}]}]
             (mapv
              #(util/to-edn %)
              @messages))))))

(deftest test-all-ok
  (let [messages (atom [])]
    (with-redefs [common/create-date (fn [] "20210322T232540Z")
                  sqs/sqs-publish (fn [{:keys [message] :as _ctx}]
                                    (swap! messages #(conj % message)))]
      (mock-core
       {:requests [{:method :post
                    :url "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                    :response {:body "{}"}}]}
       (runtime/lambda-requests
        ctx
        edd/handler
        [(req [{:request-id     req-id4
                :interaction-id int-id
                :commands       [{:id     agg-id
                                  :cmd-id :cmd-1
                                  :name   "CMD1"}]}
               {:request-id     req-id5
                :interaction-id int-id
                :commands       [{:id     agg-id
                                  :cmd-id :cmd-1
                                  :name   "CMD2"}]}])]
        :filters [filters/from-queue])

       (is (= [{:body   [{:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id4,
                          :interaction-id int-id}
                         {:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id5,
                          :interaction-id int-id}]
                :method :post
                :url    core-mock/response-endpoint-o}]
              (client/traffic-edn)))))
    (is (= [{:Records [{:key (str "response/"
                                  req-id5
                                  "/0/local-svc.json")}]}
            {:Records [{:key (str "response/"
                                  req-id4
                                  "/0/local-svc.json")}]}]
           (map
            #(util/to-edn %)
            @messages)))))

(deftest test-command-handler-exception
  (let [messages (atom [])]
    (with-redefs [common/create-date (fn [] "20210322T232540Z")
                  sqs/sqs-publish (fn [{:keys [message]}]
                                    (swap! messages #(conj % message)))]
      (mock-core
       {:requests [{:method :post
                    :url "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                    :response {:body "{}"}}]}
       (runtime/lambda-requests
        ctx
        edd/handler
        [(req
          [{:request-id     req-id1
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD1"}]}
           {:request-id     req-id2
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-3
                              :name   "CMD2"}]}
           {:request-id     req-id3
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD3"}]}])]
        :filters [filters/from-queue])
       (is (= [{:body   [{:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id1
                          :interaction-id int-id}
                         {:error          {:failed "have I!"},
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id2
                          :interaction-id int-id}
                         {:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id3
                          :interaction-id int-id}]
                :method :post
                :url    core-mock/error-endpoint-o}
               {:body (str "Action=DeleteMessageBatch&QueueUrl=https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue&"
                           "DeleteMessageBatchRequestEntry.1.Id=id-1&"
                           "DeleteMessageBatchRequestEntry.1.ReceiptHandle=handle-1&"
                           "DeleteMessageBatchRequestEntry.2.Id=id-3&"
                           "DeleteMessageBatchRequestEntry.2.ReceiptHandle=handle-3&"
                           "Expires=2020-04-18T22%3A52%3A43PST&Version=2012-11-05")
                :headers {"Accept"               "application/json"
                          "Authorization" "AWS4-HMAC-SHA256 Credential=/20200426/eu-central-1/sqs/aws4_request, SignedHeaders=accept;content-type;host;x-amz-date, Signature=8d802494b4989964d60d90a164c71ed1f69a10da77612efa6d531dd09b9a6e19"
                          "Content-Type"         "application/x-www-form-urlencoded"
                          "X-Amz-Date"           core-mock/created-date
                          "X-Amz-Security-Token" ""}
                :method          :post
                :raw             true
                :connect-timeout 300
                :idle-timeout    5000
                :url             "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                :version         :http1.1}]
              (client/traffic-edn))))
      (is (= [{:Records [{:key (str "response/"
                                    req-id3
                                    "/0/local-svc.json")}]}
              {:Records [{:key (str "response/"
                                    req-id1
                                    "/0/local-svc.json")}]}]
             (map
              #(util/to-edn %)
              @messages))))))

(deftest test-failure-of-deps-resolver
  (let [messages (atom [])]
    (with-redefs [common/create-date (fn [] "20210322T232540Z")
                  sqs/sqs-publish (fn [{:keys [message]}]
                                    (swap! messages #(conj % message)))]
      (mock-core
       {:requests [{:method :post
                    :url "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                    :response {:body "{}"}}]}
       (runtime/lambda-requests
        ctx
        edd/handler
        [(req
          [{:request-id     req-id1
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD1"}]}
           {:request-id     req-id2
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-4
                              :name   "CMD4"}]}
           {:request-id     req-id3
            :interaction-id int-id
            :commands       [{:id     agg-id
                              :cmd-id :cmd-1
                              :name   "CMD3"}]}])]
        :filters [filters/from-queue])
       (is (= [{:body   [{:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id1
                          :interaction-id int-id}
                         {:error {:failed "yes"}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id2
                          :interaction-id int-id}
                         {:result         {:success    true,
                                           :effects    [],
                                           :events     1,
                                           :meta       [{:cmd-1 {:id agg-id}}],
                                           :identities 0,
                                           :sequences  0}
                          :invocation-id  core-mock/inocation-id-0
                          :request-id     req-id3
                          :interaction-id int-id}]
                :method :post
                :url    core-mock/error-endpoint-o}
               {:body            (str "Action=DeleteMessageBatch&QueueUrl=https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue&"
                                      "DeleteMessageBatchRequestEntry.1.Id=id-1&"
                                      "DeleteMessageBatchRequestEntry.1.ReceiptHandle=handle-1&"
                                      "DeleteMessageBatchRequestEntry.2.Id=id-3&"
                                      "DeleteMessageBatchRequestEntry.2.ReceiptHandle=handle-3&"
                                      "Expires=2020-04-18T22%3A52%3A43PST&Version=2012-11-05")
                :headers         {"Accept"               "application/json"
                                  "Authorization" "AWS4-HMAC-SHA256 Credential=/20200426/eu-central-1/sqs/aws4_request, SignedHeaders=accept;content-type;host;x-amz-date, Signature=8d802494b4989964d60d90a164c71ed1f69a10da77612efa6d531dd09b9a6e19"
                                  "Content-Type"         "application/x-www-form-urlencoded"
                                  "X-Amz-Date"           core-mock/created-date
                                  "X-Amz-Security-Token" ""}
                :method          :post
                :raw             true
                :connect-timeout 300
                :idle-timeout    5000
                :url             "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                :version         :http1.1}]
              (client/traffic-edn))))
      (is (= [{:Records [{:key (str "response/"
                                    req-id3
                                    "/0/local-svc.json")}]}
              {:Records [{:key (str "response/"
                                    req-id1
                                    "/0/local-svc.json")}]}]
             (map
              #(util/to-edn %)
              @messages))))))
