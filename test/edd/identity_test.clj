(ns edd.identity-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [edd.test.fixture.dal :as mock]
   [edd.core :as edd]
   [lambda.uuid :as uuid]))

(def ctx
  (-> mock/ctx
      (edd/reg-cmd :create-1 (fn [_ctx cmd]
                               [{:identity (:name cmd)
                                 :id       (:id cmd)}
                                {:event-id :e1
                                 :name     (:name cmd)}]))
      (edd/reg-cmd :create-2 (fn [_ctx _cmd]
                               [nil
                                {:event-id :e2
                                 :name     "e2"}]))
      (edd/reg-cmd :create-3 (fn [_ctx _cmd]
                               '(nil
                                 {:event-id :e3
                                  :name     "e3"})))))
(deftest test-identity
  (mock/with-mock-dal
    ctx
    (let [id (uuid/gen)
          resp (mock/handle-cmd ctx
                                {:cmd-id :create-1
                                 :id     id
                                 :name   "e1"})]
      (mock/verify-state :event-store [{:event-id  :e1
                                        :name      "e1"
                                        :event-seq 1
                                        :meta      {}
                                        :id        id}])
      (mock/verify-state :identity-store [{:identity "e1",
                                           :id       id}])
      (is (= {:result {:effects    []
                       :events     1
                       :identities 1
                       :meta       [{:create-1 {:id id}}]
                       :sequences  0
                       :success    true}}
             resp)))))

(deftest test-handler-returns-vector
  (testing
   "Command response can contain nil, can return list or vector or map. We should handle it"
    (mock/with-mock-dal
      ctx
      (let [id (uuid/gen)
            resp (mock/handle-cmd ctx
                                  {:cmd-id :create-2
                                   :id     id
                                   :name   "e2"})]
        (mock/verify-state :event-store [{:event-id  :e2
                                          :name      "e2"
                                          :event-seq 1
                                          :meta      {}
                                          :id        id}])
        (is (= {:result
                {:effects    []
                 :events     1
                 :identities 0
                 :meta       [{:create-2 {:id id}}]
                 :sequences  0
                 :success    true}}
               resp))))))

(deftest test-handler-returns-list

  (testing
   "Command response can contain nil, can return list or vector or map. We should handle it"
    (mock/with-mock-dal
      ctx
      (let [id (uuid/gen)
            resp (mock/handle-cmd ctx
                                  {:cmd-id :create-3
                                   :id     id
                                   :name   "e3"})]
        (mock/verify-state :event-store [{:event-id  :e3
                                          :name      "e3"
                                          :event-seq 1
                                          :meta      {}
                                          :id        id}])
        (is (= {:result
                {:effects    []
                 :events     1
                 :identities 0
                 :meta       [{:create-3 {:id id}}]
                 :sequences  0
                 :success    true}}
               resp))))))

