(ns edd.el.version-test
  (:require [clojure.test :refer :all]
            [edd.core :as edd]
            [edd.common :as common]
            [lambda.uuid :as uuid]
            [edd.test.fixture.dal :as mock]
            [edd.dal :as dal]
            [lambda.ctx :as lambda-ctx]))

(def ctx
  (-> mock/ctx
      lambda-ctx/init
      (edd/reg-cmd :cmd-1
                   (fn [_ctx _cmd]
                     {:event-id :event-1
                      :name     "Test name"})
                   :deps {:test-dps
                          (fn [_ctx cmd]
                            {:query-id :get-by-id
                             :id       (:id cmd)})})

      (edd/reg-query :get-by-id common/get-by-id)
      (edd/reg-event :event-1 (fn [_ctx event]
                                {:name (:name event)}))))

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(deftest test-version-with-no-aggregate
  (mock/with-mock-dal
    ctx
    (mock/apply-cmd ctx {:cmd-id :cmd-1
                         :id     cmd-id})

    (mock/verify-state :event-store [{:event-id  :event-1
                                      :id        cmd-id
                                      :event-seq 1
                                      :meta      {}
                                      :name      "Test name"}])
    (mock/verify-state :aggregate-store [{:id      cmd-id
                                          :version 1
                                          :name    "Test name"}])))

(deftest test-version-when-aggregate-exists
  (mock/with-mock-dal
    (assoc ctx
           :event-store [{:event-id  :event-1
                          :id        cmd-id
                          :event-seq 1
                          :meta      {}
                          :name      "Test name"}])

    (with-redefs [dal/get-max-event-seq (fn [_]
                                          (throw (ex-info "Fetching"
                                                          {:should :not})))]
      (mock/apply-cmd ctx {:cmd-id :cmd-1
                           :id     cmd-id}))

    (mock/verify-state :event-store [{:event-id  :event-1
                                      :id        cmd-id
                                      :event-seq 1
                                      :meta      {}
                                      :name      "Test name"}
                                     {:event-id  :event-1
                                      :id        cmd-id
                                      :event-seq 2
                                      :meta      {}
                                      :name      "Test name"}])
    (mock/verify-state :aggregate-store [{:id      cmd-id
                                          :version 2
                                          :name    "Test name"}])))

(deftest test-version-when-aggregate-missing
  (mock/with-mock-dal
    ctx
    (with-redefs [dal/get-max-event-seq (fn [_]
                                          (throw (ex-info "Fetching"
                                                          {:should :not})))]
      (mock/apply-cmd ctx {:cmd-id :cmd-1
                           :id     cmd-id}))

    (mock/verify-state :event-store [{:event-id  :event-1
                                      :id        cmd-id
                                      :event-seq 1
                                      :meta      {}
                                      :name      "Test name"}])
    (mock/verify-state :aggregate-store [{:id      cmd-id
                                          :version 1
                                          :name    "Test name"}])))
