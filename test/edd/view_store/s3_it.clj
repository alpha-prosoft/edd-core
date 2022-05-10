(ns edd.view-store.s3-it
  (:require [clojure.test :refer [deftest is]]
            [edd.view-store.common :as common-view-store]
            [edd.view-store.s3 :as s3-view-store]
            [edd.test.fixture.dal :as mock]
            [edd.core :as edd]
            [edd.memory.event-store :as memory-event-store]
            [lambda.uuid :as uuid]
            [lambda.ctx :as lambda-ctx]
            [edd.ctx :as edd-ctx]
            [aws.ctx :as aws-context]
            [clojure.tools.logging :as log]))

(defn get-main-ctx
  [ctx]
  (-> ctx
      (lambda-ctx/init)
      (s3-view-store/register :config {:aggregate-store-bucket
                                       "test-it"})
      (memory-event-store/register)
      (aws-context/init)))

(defn get-mock-ctx
  [ctx]
  (-> ctx
      (lambda-ctx/init)
      (s3-view-store/register :implementation :mock)
      (memory-event-store/register)))

(deftest test-s3-view-store
  (let [agg-id (uuid/gen)
        base-ctx (-> mock/ctx
                     (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                           {:event-id :event-1
                                            :name     (:name cmd)}))
                     (edd/reg-event :event-1 (fn [agg evt]
                                               (assoc agg :name (:name evt)))))
        main-ctx (get-main-ctx base-ctx)
        mock-ctx (get-mock-ctx base-ctx)]

    (doseq [ctx [main-ctx mock-ctx]]
      (mock/with-mock-dal
        (log/info "Testing: " (get-in ctx [:view-store :type]))
        (mock/apply-cmd ctx {:cmd-id :cmd-1
                             :id     agg-id
                             :name   "Test1"})
        (is (= {:name "Test1",
                :id agg-id
                :version 1}
               (common-view-store/get-snapshot ctx agg-id)))))))

(deftest test-multiple-realms
  (let [base-ctx (-> mock/ctx
                     (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                           {:event-id :event-1
                                            :name     (:name cmd)}))
                     (edd/reg-event :event-1 (fn [agg evt]
                                               (assoc agg :name (:name evt)))))
        main-ctx (get-main-ctx base-ctx)
        mock-ctx (get-mock-ctx base-ctx)]

    (let [ctx main-ctx]
      (mock/with-mock-dal
        (let [agg-id (uuid/gen)
              ctx1 (edd-ctx/set-realm ctx :realm1)
              ctx2 (edd-ctx/set-realm ctx :realm2)]

          (mock/apply-cmd ctx1
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test1"})
          (mock/apply-cmd ctx2
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test2"})

          (is (= {:id      agg-id
                  :name    "Test1"
                  :version 1}
                 (common-view-store/get-snapshot ctx1 agg-id)))
          (is (= {:id      agg-id
                  :name    "Test2"
                  :version 2}
                 (common-view-store/get-snapshot ctx2 agg-id))))))
    (let [ctx mock-ctx]
      (mock/with-mock-dal
        (let [agg-id (uuid/gen)
              ctx1 (edd-ctx/set-realm ctx :realm1)
              ctx2 (edd-ctx/set-realm ctx :realm2)]

          (mock/apply-cmd ctx1
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test1"})
          (mock/apply-cmd ctx2
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test2"})

          (is (= {:id      agg-id
                  :name    "Test1"
                  :version 1}
                 (common-view-store/get-snapshot ctx1 agg-id)))
          (is (= {:id      agg-id
                  :name    "Test2"
                  :version 2}
                 (common-view-store/get-snapshot ctx2 agg-id))))))))

(deftest test-multiple-services
  (let [base-ctx (-> mock/ctx
                     (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                           {:event-id :event-1
                                            :name     (:name cmd)}))
                     (edd/reg-event :event-1 (fn [agg evt]
                                               (assoc agg :name (:name evt)))))
        main-ctx (get-main-ctx base-ctx)
        mock-ctx (get-mock-ctx base-ctx)]

    (doseq [ctx [main-ctx]]
      (mock/with-mock-dal
        (let [agg-id (uuid/gen)
              ctx (-> ctx
                      (edd/reg-cmd :cmd-1 (fn [_ctx cmd]
                                            {:event-id :event-1
                                             :name     (:name cmd)}))
                      (edd/reg-event :event-1 (fn [agg evt]
                                                (assoc agg :name (:name evt)))))
              ctx1 (assoc ctx :service-name :svc1)
              ctx2 (assoc ctx :service-name :svc2)]

          (mock/apply-cmd ctx1
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test1"})
          (mock/apply-cmd ctx2
                          {:cmd-id :cmd-1
                           :id     agg-id
                           :name   "Test2"})

          (is (= {:id      agg-id
                  :name    "Test1"
                  :version 1}
                 (common-view-store/get-snapshot ctx1 agg-id)))
          (is (= {:id      agg-id
                  :name    "Test2"
                  :version 2}
                 (common-view-store/get-snapshot ctx2 agg-id))))))))
