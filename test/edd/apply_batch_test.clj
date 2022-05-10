(ns edd.apply-batch-test
  (:require [clojure.test :refer [deftest testing is]]
            [lambda.util :as util]
            [aws.runtime :as runtime]
            [lambda.uuid :as uuid]
            [lambda.test.fixture.client :as client]
            [lambda.test.fixture.core :refer [mock-core] :as fixture-core]
            [edd.core :as edd]
            [lambda.filters :as filters]
            [edd.memory.event-store :as event-store]
            [edd.view-store.elastic :as view-store]
            [edd.el.event :as event]
            [sdk.aws.common :as common]))

(def agg-id #uuid "05120289-90f3-423c-ad9f-c46f9927a53e")

(def req-id1 (uuid/gen))
(def req-id2 (uuid/gen))
(def req-id3 (uuid/gen)) 3

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
  (-> {}
      (assoc :meta {:realm :test}
             :edd {:config {:secrets-file "files/secret-eu-west.json"}})
      (event-store/register)
      (view-store/register)
      (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                            {:id       (:id cmd)
                             :event-id :event-1
                             :name     (:name cmd)}))
      (edd/reg-cmd :cmd-2 (fn [_ctx _cmd]
                            {:error "failed"}))
      (edd/reg-event :event-1
                     (fn [agg _event]
                       (merge agg
                              {:value "1"})))
      (edd/reg-event :event-2
                     (fn [_agg _event]
                       (throw (ex-info "Sory" {:something "happened"}))))))
0
(deftest apply-when-two-events-1
  (testing
   "We apply 3 events, we should update aggregate only once"
    (with-redefs [event/get-by-id (fn [ctx]
                                    (assoc ctx
                                           :aggregate {:id agg-id}))]
      (mock-core
       {:env {"Region" "eu-west-1"}
        :responses [{:method :post
                     :url "https://sqs.eu-west-1.amazonaws.com/11111111111/test-evets-queue"
                     :response {:status 200
                                :body nil}}
                    {:method :post
                     :url (str "https://"
                               view-store/default-endpoint
                               "/test_local_svc/_doc/" agg-id)
                     :response {:status 200
                                :body "{}"}}
                    {:method :post
                     :url (str "https://"
                               view-store/default-endpoint
                               "/test_local_svc/_doc/" agg-id)
                     :response {:status 200
                                :body "{}"}}
                    {:method :post
                     :url (str "https://"
                               view-store/default-endpoint
                               "/test_local_svc/_doc/" agg-id)
                     :response {:status 200
                                :body "{}"}}]}
       (runtime/lambda-requests
        ctx
        edd/handler
        [(req
          [{:apply          {:service      "glms-booking-company-svc",
                             :aggregate-id agg-id}
            :meta           {:realm :test}
            :request-id     req-id1
            :interaction-id int-id}
           {:apply          {:service      "glms-booking-company-svc",
                             :aggregate-id agg-id}

            :meta           {:realm :test}
            :request-id     req-id2
            :interaction-id int-id}
           {:apply          {:service      "glms-booking-company-svc",
                             :aggregate-id agg-id}
            :request-id     req-id3
            :meta           {:realm :test}
            :interaction-id int-id}])]
        :filters [filters/from-queue])
       (is (= [{:body   [{:result         {:apply true}
                          :invocation-id  #uuid "00000000-0000-0000-0000-000000000000"
                          :request-id     req-id1,
                          :interaction-id int-id}
                         {:result         {:apply true}
                          :invocation-id  #uuid "00000000-0000-0000-0000-000000000000"
                          :request-id     req-id2,
                          :interaction-id int-id}
                         {:result         {:apply true}
                          :invocation-id  #uuid "00000000-0000-0000-0000-000000000000"
                          :request-id     req-id3,
                          :interaction-id int-id}]
                :method :post
                :url    "http://mock/2018-06-01/runtime/invocation/00000000-0000-0000-0000-000000000000/response"}
               {:body            {:id agg-id}
                :headers         {"Content-Type" "application/json",
                                  "X-Amz-Date" "20200426T061823Z",
                                  "X-Amz-Security-Token" "",
                                  "Authorization"
                                  "AWS4-HMAC-SHA256 Credential=/20200426/eu-west-1/es/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=d6fa5b1fe9961831802b4db838785b9b6b6032bc35cbd0a77d87234226bd790f"}
                :method          :post
                :idle-timeout    20000
                :connect-timeout 300
                :url             (str "https://"
                                      view-store/default-endpoint
                                      "/test_local_svc/_doc/05120289-90f3-423c-ad9f-c46f9927a53e")}]
              (client/traffic-edn)))))))

(deftest apply-when-error-all-failed
  (with-redefs [common/create-date (fn [] "20210322T232540Z")
                event/get-by-id (fn [ctx]
                                  (when (= (:request-id ctx)
                                           req-id2)
                                    (throw (ex-info "Something" {:badly :1wrong})))
                                  (when (= (:request-id ctx)
                                           req-id3)
                                    (throw (RuntimeException. "Non clojure error")))
                                  (assoc ctx
                                         :aggregate {:id agg-id}))
                event/update-aggregate (fn [ctx]
                                         (when (= (:request-id ctx)
                                                  req-id1)
                                           (throw (ex-info "Something" {:badly :un-happy}))))]
    (mock-core
     {:responses [{:method :post
                   :url "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"
                   :response {:body "{}"}}
                  {:method :post
                   :url (str "https:///local_test/_doc/" agg-id)
                   :response {:body "{}"}}
                  {:method :post
                   :url (str "https:///local_test/_doc/" agg-id)
                   :response {:body "{}"}}
                  {:method :post
                   :url (str "https:///local_test/_doc/" agg-id)
                   :response {:body "{}"}}]}
     (runtime/lambda-requests
      ctx
      edd/handler
      [(req
        [{:apply          {:service      "glms-booking-company-svc",
                           :aggregate-id agg-id}
          :request-id     req-id1
          :interaction-id int-id}
         {:apply          {:service      "glms-booking-company-svc",
                           :aggregate-id agg-id}
          :request-id     req-id2
          :interaction-id int-id}
         {:apply          {:service      "glms-booking-company-svc",
                           :aggregate-id agg-id}
          :request-id     req-id3
          :interaction-id int-id}])]
      :filters [filters/from-queue])
     (is (= [{:body   [{:error          {:badly :un-happy}
                        :invocation-id  fixture-core/inocation-id-0
                        :request-id     req-id1,
                        :interaction-id int-id}
                       {:error          {:badly :1wrong}
                        :invocation-id  fixture-core/inocation-id-0
                        :request-id     req-id2,
                        :interaction-id int-id}
                       {:error          "Non clojure error"
                        :invocation-id  fixture-core/inocation-id-0
                        :request-id     req-id3,
                        :interaction-id int-id}]
              :method :post
              :url    fixture-core/error-endpoint-o}]
            (client/traffic-edn))))))







