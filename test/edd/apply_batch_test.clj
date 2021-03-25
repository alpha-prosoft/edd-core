(ns edd.apply-batch-test
  (:require [clojure.test :refer :all]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [lambda.test.fixture.client :refer [verify-traffic]]
            [lambda.test.fixture.core :refer [mock-core]]
            [edd.core :as edd]
            [lambda.core :as core]
            [edd.test.fixture.dal :as mock]
            [edd.memory.event-store :as event-store]
            [edd.elastic.view-store :as view-store]
            [lambda.test.fixture.client :as client]
            [edd.el.event :as event]))

(def agg-id #uuid "05120289-90f3-423c-ad9f-c46f9927a53e")

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
  (-> {}
      (assoc :service-name "local-test")
      (event-store/register)
      (view-store/register)
      (edd/reg-cmd :cmd-1 (fn [ctx cmd]
                            {:id       (:id cmd)
                             :event-id :event-1
                             :name     (:name cmd)}))
      (edd/reg-cmd :cmd-2 (fn [ctx cmd]
                            {:error "failed"}))
      (edd/reg-event :event-1
                     (fn [agg event]
                       (merge agg
                              {:value "1"})))
      (edd/reg-event :event-2
                     (fn [agg event]
                       (throw (ex-info "Sory" {:something "happened"}))))))

(deftest apply-when-two-events-1
  (with-redefs [aws/create-date (fn [] "20210322T232540Z")
                event/get-by-id (fn [ctx]
                                  (assoc ctx
                                         :aggregate {:id agg-id}))]
    (mock-core
     :invocations [(util/to-json (req
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
                                    :interaction-id int-id}]))]

     :requests [{:post "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}]
     (core/start
      ctx
      edd/handler)
     (verify-traffic [{:body   (util/to-json
                                [{:result         {:apply true}
                                  :request-id     req-id1,
                                  :interaction-id int-id}
                                 {:result         {:apply true}
                                  :request-id     req-id2,
                                  :interaction-id int-id}
                                 {:result         {:apply true}
                                  :request-id     req-id3,
                                  :interaction-id int-id}])
                       :method :post
                       :url    "http://mock/2018-06-01/runtime/invocation/0/response"}
                      {:body      "{\"id\":\"#05120289-90f3-423c-ad9f-c46f9927a53e\"}"
                       :headers   {"Authorization"        "AWS4-HMAC-SHA256 Credential=/20210322/eu-west-1/es/aws4_request, SignedHeaders=content-type;host;x-amz-date, Signature=bf9e7749d888bc5b25a3cb4715899ec2d68709ffa8771cb80532d29cdb4d014e"
                                   "Content-Type"         "application/json"
                                   "X-Amz-Date"           "20210322T232540Z"
                                   "X-Amz-Security-Token" ""}
                       :keepalive 300000
                       :method    :post
                       :timeout   20000
                       :url       "https:///local_test/_doc/05120289-90f3-423c-ad9f-c46f9927a53e"}
                      {:method  :get
                       :timeout 90000000
                       :url     "http://mock/2018-06-01/runtime/invocation/next"}]))))

(deftest apply-when-error-all-failed
  (with-redefs [aws/create-date (fn [] "20210322T232540Z")
                event/get-by-id (fn [ctx]
                                  (println (= (:request-id ctx)
                                              req-id2))
                                  (if (= (:request-id ctx)
                                         req-id2)
                                    (throw (ex-info "Something" {:badly :wrong})))
                                  (assoc ctx
                                         :aggregate {:id agg-id}))
                event/update-aggregate (fn [ctx]
                                         (if = ((:request-id ctx)
                                                req-id1)
                                             (throw (ex-info "Something" {:badly :wrong}))))]
    (mock-core
     :invocations [(util/to-json (req
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
                                    :interaction-id int-id}]))]

     :requests [{:post "https://sqs.eu-central-1.amazonaws.com/11111111111/test-evets-queue"}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}
                {:post   (str "https:///local_test/_doc/" agg-id)
                 :status 200}]
     (core/start
      ctx
      edd/handler)
     (verify-traffic [{:body   (util/to-json
                                [{:error "Unknown error in event handler"
                                  :request-id     req-id1,
                                  :interaction-id int-id}
                                 {:error {:badly :wrong}
                                  :request-id     req-id2,
                                  :interaction-id int-id}
                                 {:error "Unknown error in event handler"
                                  :request-id     req-id3,
                                  :interaction-id int-id}])
                       :method :post
                       :url    "http://mock/2018-06-01/runtime/invocation/0/error"}
                      {:method  :get
                       :timeout 90000000
                       :url     "http://mock/2018-06-01/runtime/invocation/next"}]))))





