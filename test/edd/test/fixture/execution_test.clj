(ns edd.test.fixture.execution-test
  (:require
   [clojure.test :refer [deftest is]]
   [edd.test.fixture.execution :as sut]
   [lambda.uuid :as uuid]
   [edd.test.fixture.dal :as f]
   [lambda.test.fixture.state :as state]
   [edd.core :as edd]
   [edd.test.fixture.dal :as mock]))

(defmulti handle (fn [_ctx event] (:event-id event)))

(defmethod handle :default [_ctx _event])

(defn ctx []
  (-> mock/ctx))

(defmethod handle :e1
  [_ctx evt]
  {:cmd-id :cmd-2
   :id     (:id evt)})

(defn handle-events [ctx events]
  (->> events
       (mapv #(handle ctx %))))

(defn register []
  (-> (ctx)
      (edd/reg-cmd :cmd-1 (fn [ctx cmd]
                            [{:event-id :e1
                              :name     (:cmd-id cmd)}]))
      (edd/reg-cmd :cmd-2 (fn [ctx cmd]
                            [{:event-id :e2
                              :name     (:cmd-id cmd)}]))

      (edd/reg-fx handle-events)))

(deftest test-process-cmd-response
  (let [ctx (register)
        id (uuid/gen)]

    (f/with-mock-dal
      ctx
      (sut/process-cmd-response!
       ctx
       {:commands [{:cmd-id :cmd-1
                    :id     id}]})
      (f/verify-state :event-store
                      [{:event-id  :e1,
                        :name      :cmd-1,
                        :meta      {}
                        :id        id
                        :event-seq 1}]))))

(deftest test-process-next-on-empty-queue
  (let [ctx (register)]
    (f/with-mock-dal
      ctx
      (is (not (sut/process-next! ctx)))

      (f/verify-state :event-store
                      []))))

(deftest test-place-and-process-next
  (let [ctx (register)
        id1 (uuid/gen)
        id2 (uuid/gen)]
    (f/with-mock-dal
      ctx
      (sut/place-cmd! {:cmd-id :cmd-1
                       :id     id1}
                      {:cmd-id :cmd-1
                       :id     id2})

      (is (sut/process-next! ctx))
      (is (= 1 (count (mock/peek-state :event-store)))))))

(deftest test-place-and-process-all
  (let [ctx (register)
        id1 (uuid/gen)
        id2 (uuid/gen)]
    (f/with-mock-dal
      ctx
      (sut/place-cmd! {:cmd-id :cmd-1
                       :id     id1}
                      {:cmd-id :cmd-1
                       :id     id2})

      (sut/process-all! ctx)

      (is (= 4 (count (mock/peek-state :event-store)))))))

(deftest test-run-multiple-cmds
  (let [ctx (register)
        id1 (uuid/gen)
        id2 (uuid/gen)]
    (f/with-mock-dal
      ctx
      (sut/run-cmd! ctx
                    {:cmd-id :cmd-1
                     :id     id1}
                    {:cmd-id :cmd-1
                     :id     id2})

      (is (= 4 (count (mock/peek-state :event-store)))))))

(deftest test-run-a-single-cmd
  (let [ctx (register)
        id1 (uuid/gen)]
    (f/with-mock-dal
      ctx
      (sut/run-cmd! ctx {:cmd-id :cmd-1
                         :id     id1})

      (is (= 2 (count (mock/peek-state :event-store)))))))
