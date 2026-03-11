(ns edd.cmd-spec-test
  (:require [clojure.tools.logging :as log]
            [edd.core :as edd]
            [clojure.test :refer :all]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]
            [edd.el.cmd :as cmd]))

(defn dummy-command-handler
  [ctx cmd]
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
    (cmd/handle-commands ctx valid-command-request)
    (mock/verify-state :event-store [{:event-id  :dummy-event
                                      :handled   true
                                      :event-seq 1
                                      :id        cmd-id}])
    (mock/verify-state :identities [])
    (mock/verify-state :commands [])))

(deftest test-missing-id-command
  (mock/with-mock-dal
    (is (= {:error {:id ["missing required key"]}}
           (select-keys
            (mock/handle-cmd
             ctx
             {:cmd-id :dummy-cmd})
            [:error])))))

(deftest test-missing-failed-custom-validation-command
  (mock/with-mock-dal
    (is (= {:name ["missing required key"]}
           (:error
            (mock/handle-cmd
             (-> ctx
                 (edd/reg-cmd :dummy-cmd dummy-command-handler
                              :consumes [:map
                                         [:name string?]]))
             {:cmd-id :dummy-cmd
              :id     cmd-id}))))))

(deftest test-missing-failed-custom-validation-command-logs-error
  (mock/with-mock-dal
    (let [error-log
          (atom nil)

          error-count
          (atom 0)

          result
          (with-redefs [log/log* (fn [_ level _ message]
                                   (when (= :error level)
                                     (swap! error-count inc)
                                     (reset! error-log message)))]
            (mock/handle-cmd
             (-> ctx
                 (edd/reg-cmd :dummy-cmd dummy-command-handler
                              :consumes [:map
                                         [:name string?]]))
             {:cmd-id :dummy-cmd
              :id     cmd-id}))]
      (is
       (= {:error {:name ["missing required key"]}}
          (select-keys result [:error])))

      (is
       (= 1 @error-count))

      (is
       (re-matches #".*Command validation failed.*:dummy-cmd.*missing required key.*"
                   @error-log)))))
