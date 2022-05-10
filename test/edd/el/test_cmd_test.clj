(ns edd.el.test-cmd-test
  (:require [clojure.test :refer [deftest testing is]]
            [edd.el.cmd :as cmd]
            [edd.el.query :as query]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [edd.dal :as dal]
            [edd.ctx :as edd-ctx]
            [edd.core :as edd]
            [aws.aws :as aws])
  (:import (clojure.lang ExceptionInfo)
           (org.httpkit.client TimeoutException)))

(def cmd-id (uuid/gen))

(deftest test-if-properly-working-when-no-deps
  (testing "Test if properly prepared for no-dependencies"
    (with-redefs [query/handle-query (fn [_ctx _q] {:response true})
                  dal/log-dps (fn [ctx] ctx)
                  dal/get-max-event-seq (fn [_ctx _id] 0)]
      (let [cmd {:cmd-id :test-cmd
                 :id     cmd-id}
            deps (cmd/fetch-dependencies-for-command {} cmd)]
        (is (= {}
               deps))))))

(deftest test-prepare-context-for-command-local

  (testing "Test if context if properly prepared for local queries. If local query return nill
            we do not expect context to change. This will be used for testing on other servies
            to prearrange context"
    (with-redefs [dal/log-dps (fn [ctx] ctx)
                  dal/get-max-event-seq (fn [_ctx _id] 0)]
      (let [ctx (edd-ctx/put-cmd {}
                                 :cmd-id :test-cmd
                                 :options {:deps {:test-value   (fn [_ _cmd]
                                                                  {:query-id :q1
                                                                   :query    {}})
                                                  :test-value-2 (fn [_ _cmd]
                                                                  {:query-id :q2
                                                                   :query    {}})}})
            cmd {:cmd-id :test-cmd
                 :id     cmd-id}]
        (with-redefs [query/handle-query (fn [_ctx q]
                                           (if (= (get-in q [:query :query-id]) :q1)
                                             {:response true}
                                             nil))]
          (let [deps (cmd/fetch-dependencies-for-command ctx cmd)]
            (is (= {:test-value {:response true}}
                   deps))))
        (testing "If error is returned"
          (with-redefs [query/handle-query (fn [_ctx q]
                                             (if (= (get-in q [:query :query-id]) :q1)
                                               {:error true}
                                               nil))]
            (is (thrown? ExceptionInfo
                         (cmd/fetch-dependencies-for-command ctx cmd)))))
        (testing "If resolved returns exception"
          (with-redefs [query/handle-query (fn [_ctx q]
                                             (if (= (get-in q [:query :query-id]) :q1)
                                               (throw (ex-info "Some" {:error :happened}))
                                               nil))]
            (is (thrown? ExceptionInfo
                         (cmd/fetch-dependencies-for-command ctx cmd)))))))))

(deftest test-prepare-context-for-command-remote-deps

  (testing
   "Test if context if properly prepared for remote queries"
    (with-redefs [aws/get-token (fn [_ctx] "")
                  dal/log-dps (fn [ctx] ctx)
                  dal/get-max-event-seq (fn [_ctx _id] 0)]
      (let [ctx (edd-ctx/put-cmd {}
                                 :cmd-id :test-cmd
                                 :options {:deps
                                           {:test-value
                                            {:service :remote
                                             :query   (fn [_ _cmd]
                                                        {:query-id :q1
                                                         :query    {}})}}})
            cmd {:cmd-id :test-cmd
                 :id     cmd-id}]
        (with-redefs [util/http-request (fn [_req & _options]
                                          {:status 200
                                           :body   {:result {:response true}}})]
          (let [resolved-ctx (cmd/fetch-dependencies-for-command ctx cmd)]
            (is (= {:test-value {:response true}}
                   (dissoc resolved-ctx
                           :dps
                           :commands)))))
        (testing "If error is returned"
          (with-redefs [util/http-post (fn [_ctx _q]
                                         {:error "ConnectionTimeout"})]
            (is (thrown? ExceptionInfo
                         (cmd/fetch-dependencies-for-command ctx cmd)))))
        (testing "If resolved returns exception"
          (with-redefs [util/http-post (fn [_ctx _q]
                                         {:status 499
                                          :body   {:response true}})]
            (is (thrown? ExceptionInfo
                         (cmd/fetch-dependencies-for-command ctx cmd)))))
        (testing "If resolved returns exception"
          (with-redefs [util/http-post (fn [_ctx _q]
                                         (throw (TimeoutException. "")))]
            (is (thrown? ExceptionInfo
                         (cmd/fetch-dependencies-for-command ctx cmd)))))))))

(def id-1 (uuid/gen))
(def id-2 (uuid/gen))

(deftest test-resolve-id
  (testing
   "Test if id is properly resolved"
    (let [id (cmd/resolve-command-id-with-id-fn
              {}
              {:cmd-id :dummy-cmd
               :id     id-1})]
      (is (= id {:cmd-id :dummy-cmd
                 :id     id-1})))))

(deftest test-resolve-id-when-id-override-present
  (testing "Test if id is properly resolved when override present.
            when override method return nil, we should fallback to (:id cmd)"
    (let [ctx (-> {:dps-1 2
                   :dps-2 3}
                  (edd/reg-cmd :dummy-cmd (fn [_ctx _cmd])
                               :id-fn (fn [ctx _cmd] (+ (:dps-1 ctx)
                                                        (:dps-2 ctx))))
                  (edd/reg-cmd :dummy-cmd-1 (fn [_ctx _cmd])
                               :id-fn (fn [_ctx _cmd] nil)))
          cmd-1 (cmd/resolve-command-id-with-id-fn
                 ctx
                 {:cmd-id :dummy-cmd
                  :id     id-1})
          cmd-2 (cmd/resolve-command-id-with-id-fn
                 ctx
                 {:cmd-id :dummy-cmd-1
                  :id     id-1})]
      (is (= cmd-1 {:cmd-id :dummy-cmd
                    :id     5}))
      (is (= cmd-2 {:cmd-id :dummy-cmd-1
                    :id     id-1})))))
