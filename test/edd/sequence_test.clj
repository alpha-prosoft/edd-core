(ns edd.sequence-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [edd.test.fixture.dal :as mock]
   [edd.core :as edd]
   [lambda.uuid :as uuid]))

(def ctx
  (-> mock/ctx
      (edd/reg-cmd :create-1 (fn [_ctx cmd]
                               [{:sequence :limit-application
                                 :id       (:id cmd)}
                                {:event-id :e1
                                 :name     (:name cmd)}]))))

(deftest test-sequence-generation
  (mock/with-mock-dal
    (let [id (uuid/gen)
          resp (mock/handle-cmd
                ctx
                {:cmd-id :create-1
                 :id     id
                 :name   "e1"})]

      (mock/verify-state :sequence-store [{:value 1
                                           :id    id}])
      (is (= {:result
              {:effects    []
               :events     1
               :identities 0
               :meta      [{:create-1 {:id id}}]
               :sequences  1
               :success    true}}
             resp)))))
