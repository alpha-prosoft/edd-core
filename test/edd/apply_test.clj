(ns edd.apply-test
  (:require [clojure.test :refer [deftest testing is]]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [edd.core :as edd]
            [edd.test.fixture.dal :as mock]
            [edd.memory.event-store :as event-store]
            [edd.view-store.elastic :as view-store]))

(def agg-id (uuid/parse "0000bf24-c357-4ee2-ae1e-6ce22c90c183"))

(def req-id (uuid/parse "1111bf24-c357-4ee2-ae1e-6ce22c90c183"))
(def int-id (uuid/parse "2222bf24-c357-4ee2-ae1e-6ce22c90c183"))

(def ctx
  (-> {}
      (assoc :service-name "local-test")
      (event-store/register)
      (view-store/register :implementation :mock)
      (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                            {:id       (:id cmd)
                             :event-id :event-1
                             :name     (:name cmd)}))
      (edd/reg-event :event-1
                     (fn [agg _event]
                       (merge agg
                              {:value "1"})))
      (edd/reg-event :event-2
                     (fn [agg _event]
                       (merge agg
                              {:value "2"})))))

(deftest apply-when-two-events
  (mock/with-mock-dal
    {:event-store [{:event-id  :event-1
                    :event-seq 1
                    :id        agg-id}
                   {:event-id  :event-1
                    :event-seq 2
                    :id        agg-id}]}
    (let [resp (mock/apply-events ctx agg-id)]
      (mock/verify-state :aggregate-store
                         [{:id      agg-id
                           :version 2
                           :value   "1"}])
      (is (= [{:result         {:apply true}}]
             resp)))))

(deftest apply-when-no-events
  (mock/with-mock-dal
    ctx
    (let [resp (mock/apply-events ctx #uuid "cb245f3b-a791-4637-919f-c0682d4277ae")]
      (is (= {:result {:apply true}}
             (first resp))))))

(deftest apply-when-events-but-no-handler
  (testing
   "When some events are handled but some are not registered"
    (let [ctx
          (-> mock/ctx
              (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                    {:id       (:id cmd)
                                     :event-id :event-1
                                     :name     (:name cmd)}))
              (edd/reg-cmd :cmd-2 (fn [_ctx cmd]
                                    {:id       (:id cmd)
                                     :event-id :event-2
                                     :name     (:name cmd)}))
              (edd/reg-event :event-2 (fn [agg event]
                                        (assoc agg :name (:name event)))))]

      (mock/with-mock-dal
        {:event-store [{:event-id  :event-1
                        :event-seq 1
                        :id        agg-id}
                       {:event-id  :event-2
                        :event-seq 2
                        :name "Some name"
                        :id        agg-id}
                       {:event-id  :event-1
                        :event-seq 3
                        :id        agg-id}]}
        (let [resp (mock/apply-events ctx agg-id)]
          (mock/verify-state :aggregate-store
                             [{:id      agg-id
                               :name    "Some name"
                               :version 3}])
          (is (= [{:result {:apply true}}]
                 resp)))))))

(deftest apply-when-only-unhandeled-event
  (testing
   "When we have only 1 unhandeled event we expect version to be 1"
    (let [ctx mock/ctx]
      (mock/with-mock-dal
        {:event-store [{:event-id  :event-1
                        :event-seq 1
                        :id        agg-id}]}
        (let [resp (mock/apply-events ctx agg-id)]
          (mock/verify-state :aggregate-store
                             [{:id      agg-id
                               :version 1}])
          (is (= [{:result {:apply true}}]
                 resp)))))))
