(ns edd.elastic.view-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.dal :as dal]
            [edd.el.event :as event]
            [edd.el.query :as query]
            [edd.elastic.view-store :as elastic-view-store]
            [edd.search :as search]
            [edd.snapshot-cache :as snapshot-cache]
            [edd.test.fixture.dal :as mock]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import (clojure.lang ExceptionInfo)))

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(def service-ctx
  (-> mock/ctx
      (assoc-in [:meta :realm] :test)
      (assoc :service-name :local-test)
      (edd/reg-query :get-by-id (fn [ctx query]
                                  (common/get-by-id (assoc ctx :id (:id query)))))
      (edd/reg-event :e7 (fn [_ctx event] {:name (:event-id event)}))))

(def apply-ctx
  (-> service-ctx
      (edd/reg-event :event-1 (fn [p v] (assoc p :e1 v)))
      (edd/reg-event :event-2 (fn [p v] (assoc p :e2 v)))))

(deftest test-get-by-id-missing-snapshot
  (testing "Test combinations of get-by. If aggregate snapshot return nil we apply events"
    "If no events we return nil"
    (let [CTX {:aws {:region "test"
                     :account-id "test"
                     :aws-session-token "test"}
               :environment-name-lower "test"}]
      ;; Test with valid UUID - when snapshot returns 404 and no events exist, returns nil aggregate
      (let [test-id (uuid/gen)]
        (mock/with-mock-dal
          (with-redefs [dal/get-events (fn [_]
                                         [])
                        util/http-get (fn [_url _request & {:keys [_raw]}]
                                        {:status 404})]
            (let [response (query/handle-query
                            (-> (merge service-ctx CTX)
                                (elastic-view-store/register))
                            {:query
                             {:query-id :get-by-id
                              :id       test-id}})]
              (is (contains? response :aggregate))
              (is (= nil
                     (:aggregate response)))))))
      ;; Test with valid UUID - when snapshot returns 403, throws exception
      (let [test-id (uuid/gen)]
        (mock/with-mock-dal
          (with-redefs [dal/get-events (fn [_]
                                         [{:event-id  :e7
                                           :event-seq 1}])
                        util/http-get (fn [_url _request & {:keys [_raw]}]
                                        {:status 403})]
            (is (thrown? ExceptionInfo
                         (query/handle-query
                          (-> (merge service-ctx CTX)
                              (elastic-view-store/register))
                          {:query
                           {:query-id :get-by-id
                            :id       test-id}}))))))
      ;; Test with valid UUID - when snapshot returns 404 but events exist, applies events
      (let [test-id (uuid/gen)]
        (mock/with-mock-dal
          (with-redefs [dal/get-events (fn [_]
                                         [{:event-id  :e7
                                           :event-seq 1
                                           :id        test-id}])
                        util/http-get (fn [_url _request & {:keys [_raw]}]
                                        {:status 404})]
            (let [response (query/handle-query
                            (-> (merge service-ctx CTX)
                                (elastic-view-store/register))
                            {:query
                             {:query-id :get-by-id
                              :id       test-id}})]
              (is (contains? response :aggregate))
              (is (= {:id      test-id
                      :name    :e7
                      :version 1}
                     (:aggregate response))))))))))

(deftest test-apply-cmd-storing-error
  (with-redefs [dal/get-events (fn [_]
                                 [{:event-id  :event-1
                                   :event-seq 1
                                   :id        cmd-id
                                   :k1        "a"}
                                  {:event-id  :event-2
                                   :event-seq 2
                                   :id        cmd-id
                                   :k2        "b"}])]

    (is (thrown? Exception
                 (event/handle-event (-> apply-ctx
                                         elastic-view-store/register
                                         (assoc :apply {:aggregate-id cmd-id
                                                        :apply        :cmd-1})))))))

(deftest test-apply-cmd-storing-response-error
  (with-redefs [dal/get-events (fn [_]
                                 [{:event-id  :event-1
                                   :event-seq 1
                                   :id        cmd-id
                                   :k1        "a"}
                                  {:event-id  :event-2
                                   :event-seq 2
                                   :id        cmd-id
                                   :k2        "b"}])
                util/http-get (fn [_url _request & {:keys [raw]}]
                                {:status 404})
                util/http-post (fn [url request & {:keys [raw]}]
                                 {:status 303
                                  :body   "Sorry"})]

    (try
      (event/handle-event (-> apply-ctx
                              elastic-view-store/register
                              (assoc
                               :apply {:aggregate-id cmd-id})))
      (catch Exception e
        (let [expected {:error {:status  303
                                :message "Sorry"}}]
          (is (= expected
                 (ex-data e)))
          (when (not= expected
                      (ex-data e))
            (log/info "Unexpected test exception" e)))))))

(defn- cached-ctx
  [cached]
  (let [cache
        {:store         (fn [_ctx aggregate]
                          (swap! cached assoc
                                 [(:id aggregate)] aggregate
                                 [(:id aggregate) (:version aggregate)] aggregate))
         :fetch         (fn [_ctx id]
                          (get @cached [id]))
         :fetch-version (fn [_ctx id version]
                          (get @cached [id version]))}]
    (snapshot-cache/register (elastic-view-store/register (merge service-ctx
                                                                 {:aws {:region "test"}
                                                                  :environment-name-lower "test"}))
                             cache)))

(deftest snapshot-cache-test
  (let [cached
        (atom {})

        indexed
        (atom [])

        ctx
        (cached-ctx cached)

        v1
        {:id cmd-id :version 1 :name "one"}

        v2
        {:id cmd-id :version 2 :name "two"}]
    (with-redefs [elastic-view-store/store-to-elastic (fn [_ctx aggregate]
                                                        (swap! indexed conj aggregate))
                  elastic-view-store/get-from-elastic-by-id (fn [_ctx _id]
                                                              {:id cmd-id :version 3 :name "indexed"})]
      (search/update-aggregate ctx v1)
      (search/update-aggregate ctx v2)

      (testing "every update is cached and indexed"
        (is
         (= [v1 v2]
            @indexed))

        (is
         (= v2
            (get @cached [cmd-id]))))

      (testing "the latest snapshot comes from the cache"
        (is
         (= v2
            (search/get-snapshot ctx cmd-id))))

      (testing "a version comes from the cache, else from the index when it is the current one"
        (is
         (= v1
            (search/get-snapshot ctx {:id cmd-id :version 1})))

        (is
         (= {:id cmd-id :version 3 :name "indexed"}
            (search/get-snapshot ctx {:id cmd-id :version 3})))

        (is
         (nil?
          (search/get-snapshot ctx {:id cmd-id :version 7})))))))

