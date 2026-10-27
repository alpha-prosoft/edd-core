(ns edd.apply-batch-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.el.event :as event]
            [edd.test.fixture.dal :as mock]
            [edd.test.fixture.runtime :as runtime]
            [lambda.uuid :as uuid]))

(def agg-id #uuid "05120289-90f3-423c-ad9f-c46f9927a53e")

(def req-id1 (uuid/gen))
(def req-id2 (uuid/gen))
(def req-id3 (uuid/gen))

(def int-id (uuid/gen))

(defn- apply-requests
  [request-ids]
  (mapv (fn [request-id]
          {:apply          {:service      "glms-booking-company-svc"
                            :aggregate-id agg-id}
           :meta           {:realm :test}
           :request-id     request-id
           :interaction-id int-id})
        request-ids))

(defn- start
  []
  (runtime/start (assoc mock/ctx :service-name :local-test)))

(deftest apply-when-two-events-1
  (testing "Ensure that wee only udpate aggregate once for same aggregate id"
    (let [updates
          (atom [])]
      (mock/with-mock-dal
        (with-redefs [event/get-by-id (fn [_ctx id]
                                        {:id id :version 1})
                      event/update-aggregate (fn [_ctx aggregate]
                                               (swap! updates conj aggregate))]
          (is
           (= {:responses  [{:result         {:apply true}
                             :invocation-id  0
                             :request-id     req-id1
                             :interaction-id int-id}
                            {:result         {:apply true}
                             :invocation-id  0
                             :request-id     req-id2
                             :interaction-id int-id}
                            {:result         {:apply true}
                             :invocation-id  0
                             :request-id     req-id3
                             :interaction-id int-id}]
               :redeliver? false}
              (runtime/handle-records (start) (apply-requests [req-id1 req-id2 req-id3]))))

          (is
           (= [{:id agg-id :version 1}]
              @updates)))))))

(deftest apply-when-error-all-failed
  (testing "the batch stops at the first failed record and is delivered again"
    (mock/with-mock-dal
      (with-redefs [event/get-by-id (fn [_ctx id]
                                      {:id id :version 1})
                    event/update-aggregate (fn [ctx _aggregate]
                                             (when (= (:request-id ctx) req-id1)
                                               (throw (ex-info "Something" {:badly :un-happy}))))]
        (is
         (= {:responses  [{:exception      {:badly :un-happy}
                           :invocation-id  0
                           :request-id     req-id1
                           :interaction-id int-id}]
             :redeliver? true}
            (runtime/handle-records (start) (apply-requests [req-id1 req-id2 req-id3]))))))))
