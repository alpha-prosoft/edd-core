(ns edd.aggregate-test
  (:require [clojure.test :refer [deftest is]]
            [edd.core :as edd]
            [edd.el.event :as event]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]))

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(def apply-ctx
  (-> mock/ctx
      (assoc-in [:meta :realm] :test)
      (merge {:service-name :local-test})
      (edd/reg-event
       :event-1 (fn [p v]
                  (assoc p :e1 v)))
      (edd/reg-event
       :event-2 (fn [p v]
                  (assoc p :e2 v)))
      (edd/reg-agg-filter
       (fn [{:keys [agg] :as ctx}]
         (assoc
          agg
          :filter-result
          (str (get-in agg [:e1 :k1])
               (get-in agg [:e2 :k2])))))))

(deftest test-apply
  (let [agg (event/get-current-state
             apply-ctx
             {:events
              [{:event-id  :event-1
                :id        cmd-id
                :event-seq 1
                :k1        "a"}
               {:event-id  :event-2
                :id        cmd-id
                :event-seq 2
                :k2        "b"}]
              :id "ag1"})]
    (is (= {:id            cmd-id
            :filter-result "ab"
            :version       2
            :e1            {:event-id  :event-1,
                            :k1        "a"
                            :event-seq 1
                            :id        cmd-id},
            :e2            {:event-id  :event-2
                            :k2        "b"
                            :event-seq 2
                            :id        cmd-id}}
           agg))))
