(ns edd.snapshot-test
  (:require [clojure.test :refer [deftest testing is]]
            [lambda.uuid :as uuid]
            [edd.core :as edd]
            [edd.test.fixture.dal :as mock]
            [edd.memory.event-store :as event-store]
            [edd.view-store.elastic :as view-store]))

(def agg-id (uuid/gen))

(def ctx
  (-> {}
      (assoc :service-name "local-test")
      (event-store/register)
      (view-store/register :implementation :mock)
      (edd/reg-event :event-1
                     (fn [agg _event]
                       (update agg :value (fnil inc 0))))))

(deftest apply-events-no-snapshot
  (testing "snapshot - but no snapshot available"
    (mock/with-mock-dal
      (assoc ctx
             :event-store [{:event-id  :event-1
                            :event-seq 1
                            :id        agg-id}])
      (mock/handle-event ctx (assoc-in {} [:apply :aggregate-id] agg-id))
      (mock/verify-state :aggregate-store
                         [{:id      agg-id
                           :version 1
                           :value   1}]))))

(deftest apply-events-snapshot-and-no-events
  (testing "snapshot available and events empty"
    (mock/with-mock-dal
      (assoc ctx
             :aggregate-store [{:id      agg-id
                                :value   2
                                :version 2}])
      (let [resp (mock/handle-event ctx (assoc-in {} [:apply :aggregate-id] agg-id))]
        (prn resp)
        (mock/verify-state :aggregate-store
                           [{:id      agg-id
                             :version 2
                             :value   2}])))))

(deftest apply-events-snapshot-and-newer-events
  (testing "snapshot available and one newer event"
    (mock/with-mock-dal
      (assoc ctx
             :event-store [{:event-id  :event-1
                            :event-seq 3
                            :id        agg-id}]
             :aggregate-store [{:id      agg-id
                                :value   1
                                :version 2}])
      (let [_resp (mock/handle-event ctx (assoc-in {} [:apply :aggregate-id] agg-id))]
        (mock/verify-state :aggregate-store
                           [{:id      agg-id
                             :version 3
                             :value   2}])))))

(deftest apply-events-snapshot-and-older-events
  (testing "snapshot available and older events, only snapshot will be considered"
    (mock/with-mock-dal
      (assoc ctx
             :event-store [{:event-id  :event-1
                            :event-seq 3
                            :id        agg-id}]
             :aggregate-store [{:id      agg-id
                                :value   1
                                :version 3}])
      (let [_resp (mock/handle-event ctx (assoc-in {} [:apply :aggregate-id] agg-id))]
        (mock/verify-state :aggregate-store
                           [{:id      agg-id
                             :version 3
                             :value   1}])))))
