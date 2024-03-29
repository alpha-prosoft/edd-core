(ns edd.cmd-spec-test
  (:require [clojure.tools.logging :as log]
            [edd.core :as edd]
            [clojure.test :refer [deftest is]]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]))

(defn dummy-command-handler
  [_ctx cmd]
  (log/info "Dummy" cmd)
  {:event-id :dummy-event
   :id       (:id cmd)
   :handled  true})

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(def ctx
  (-> mock/ctx
      (edd/reg-cmd :dummy-cmd dummy-command-handler)))

(def valid-command-request
  {:commands [{:cmd-id :dummy-cmd
               :id     cmd-id}]})

(deftest test-valid-command
  (mock/with-mock-dal
    ctx
    (mock/handle-cmd ctx valid-command-request)
    (mock/verify-state :event-store [{:event-id  :dummy-event
                                      :handled   true
                                      :event-seq 1
                                      :meta      {}
                                      :id        cmd-id}])
    (mock/verify-state :identities [])
    (mock/verify-state :sequences [])
    (mock/verify-state :commands [])))

(deftest test-missing-id-command
  (mock/with-mock-dal
    ctx
    (is (= {:exception [{:id ["missing required key"]}]}
           (mock/handle-cmd
            ctx
            {:cmd-id :dummy-cmd})))))

(deftest test-missing-failed-custom-validation-command
  (mock/with-mock-dal
    ctx
    (is (= {:exception [{:name ["missing required key"]}]}
           (mock/handle-cmd
            (-> ctx
                (edd/reg-cmd :dummy-cmd dummy-command-handler
                             :spec [:map
                                    [:name string?]]))
            {:cmd-id :dummy-cmd
             :id     cmd-id})))))
