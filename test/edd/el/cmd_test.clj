(ns edd.el.cmd-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [edd.core :as edd]
            [edd.el.cmd :as el-cmd]
            [edd.test.fixture.runtime :as api]
            [edd.test.fixture.dal :as mock]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import (clojure.lang ExceptionInfo)))

(def fixed-created-on "2026-05-01T00:00:00.000+00:00")

(use-fixtures :each
  (fn [t]
    (with-redefs [util/date->string (fn ([] fixed-created-on)
                                      ([_] fixed-created-on))]
      (t))))

(def ctx
  (edd/reg-cmd {} :ssa (fn [_ _])))

(deftest test-empty-commands-list
  (try
    (el-cmd/validate-commands ctx [])
    (catch ExceptionInfo ex
      (is
       (= {:error "No commands present in request"}
          (ex-data ex))))))

(defn register
  []
  (edd/reg-cmd mock/ctx
               :ping
               (fn [_ _] {:event-id :ping})))

(deftest api-handler-test
  (let [request-id
        (uuid/gen)

        interaction-id
        (uuid/gen)]
    (mock/with-mock-dal
      (is
       (= {:status 500
           :body   {:invocation-id  0
                    :request-id     request-id
                    :interaction-id interaction-id
                    :error          {:id ["missing required key"]}}}
          (api/post (api/start (register))
                    {:request-id     request-id
                     :interaction-id interaction-id
                     :commands       [{:cmd-id :ping}]})))

      (testing "a failed command has nothing to route"
        (is
         (= []
            (api/routed)))))))

(deftest api-handler-response-test
  (let [request-id
        (uuid/gen)

        interaction-id
        (uuid/gen)

        id
        (uuid/gen)]
    (mock/with-mock-dal
      {:keep-meta true}
      (is
       (= {:status 200
           :body   {:result         {:success    true
                                     :effects    []
                                     :events     1
                                     :meta       [{:ping {:id id}}]
                                     :identities 0}
                    :invocation-id  0
                    :request-id     request-id
                    :interaction-id interaction-id}}
          (api/post (api/start (register))
                    {:request-id     request-id
                     :interaction-id interaction-id
                     :commands       [{:cmd-id :ping
                                       :id     id}]})))

      (testing "the events are routed"
        (is
         (= [[id]]
            (map #(map :id (get-in % [:resp :events])) (api/routed)))))

      (testing "the events carry the authenticated user and realm"
        (mock/verify-state :event-store [{:event-id       :ping
                                          :event-seq      1
                                          :id             id
                                          :request-id     request-id
                                          :interaction-id interaction-id
                                          :meta           {:realm         :test
                                                           :created-on    fixed-created-on
                                                           :invocation-id 0
                                                           :user          (dissoc api/user :realm)}}])))))

(deftest repeated-request-reports-attempt-and-cached-response-test
  (let [request
        {:request-id     (uuid/gen)
         :interaction-id (uuid/gen)
         :commands       [{:cmd-id :ping
                           :id     (uuid/gen)}]}]
    (mock/with-mock-dal
      (let [ctx
            (api/start (register))

            handler-info
            (fn []
              (get-in (api/post ctx request) [:body :meta]))]
        (testing "nothing for the first execution"
          (is
           (nil?
            (handler-info))))

        (testing "a repeated request is answered from the stored response"
          (is
           (= {:handler {:attempt 1 :cached true}}
              (handler-info)))

          (is
           (= {:handler {:attempt 2 :cached true}}
              (handler-info))))

        (testing "the command ran once"
          (is
           (= 1
              (count (mock/peek-state :event-store)))))))))

(deftest rerun-after-concurrent-modification-reports-attempt-test
  (let [failures
        (atom 1)

        store-results
        el-cmd/store-results]
    (mock/with-mock-dal
      (with-redefs [el-cmd/store-results (fn [ctx resp]
                                           (when (pos? (long @failures))
                                             (swap! failures dec)
                                             (throw (ex-info "taken" {:error {:key :concurrent-modification}})))
                                           (store-results ctx resp))]
        (let [response
              (api/post (api/start (register))
                        {:request-id     (uuid/gen)
                         :interaction-id (uuid/gen)
                         :commands       [{:cmd-id :ping
                                           :id     (uuid/gen)}]})]
          (is
           (= 200
              (:status response)))

          (is
           (= {:handler {:attempt 1}}
              (get-in response [:body :meta]))))))))

(deftest test-retry-logic
  (testing "regular exception"
    (let [capture! (atom 0)]
      (try
        (el-cmd/retry (fn []
                        (swap! capture! inc)
                        (/ 0 0))
                      3)
        (is false)
        (catch Exception _e
          (is
           (= 1 @capture!))))))

  (testing "special exception"
    (let [capture! (atom 0)]
      (try
        (el-cmd/retry
         (fn []
           (swap! capture! inc)
           (throw (ex-info "boom" {:error {:key :concurrent-modification}})))
         3)
        (is false)
        (catch Exception e
          (is
           (= 3 @capture!))

          (is
           (= "boom"
              (ex-message e))))))))
