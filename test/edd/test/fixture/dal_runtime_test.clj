(ns edd.test.fixture.dal-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.test.fixture.dal :as mock]
            [edd.test.fixture.runtime :as runtime]
            [lambda.uuid :as uuid])
  (:import [clojure.lang ExceptionInfo]))

(defn- register
  [ctx]
  (edd/reg-cmd
   (edd/reg-cmd ctx :make (fn [_ctx cmd]
                            {:event-id :made
                             :id (:id cmd)}))
   :break (fn [_ctx cmd]
            (throw (ex-info "Broken on purpose" {:id (:id cmd)})))))

(deftest runtime-starts-from-given-ctx-test
  (let [ctx
        (register (assoc mock/ctx :service-name :given-svc))]
    (mock/with-mock-dal
      {:ctx ctx}

      (is
       (= :given-svc
          (:service-name mock/*runtime*)))

      (is
       (= {:success true
           :events 1}
          (select-keys (mock/handle-cmd ctx {:cmd-id :make
                                             :id (uuid/gen)})
                       [:success :events]))))))

(deftest runtime-starts-from-mock-ctx-test
  (mock/with-mock-dal
    (is
     (= (:service-name mock/ctx)
        (:service-name mock/*runtime*)))))

(deftest handler-exception-is-thrown-test
  (mock/with-mock-dal
    (testing "the exception of the handler reaches the test as it was thrown"
      (is
       (thrown-with-msg? ExceptionInfo
                         #"Broken on purpose"
                         (mock/handle-cmd (register mock/ctx) {:cmd-id :break
                                                               :id (uuid/gen)}))))))

(defn- register-tags
  [ctx seen]
  (let [with-cmd
        (edd/reg-cmd ctx :tag (fn [ctx cmd]
                                (swap! seen conj (:tags (:tagged ctx)))
                                {:event-id :tagged
                                 :id (:id cmd)
                                 :tag (:tag cmd)})
                     :deps [:tagged (fn [_deps cmd]
                                      {:query-id :get-tagged
                                       :id (:id cmd)})])

        with-event
        (edd/reg-event with-cmd :tagged (fn [agg event]
                                          (assoc agg :tags (conj (set (:tags agg)) (:tag event)))))]
    (edd/reg-query with-event :get-tagged (fn [ctx query]
                                            (common/get-by-id ctx (:id query))))))

(deftest every-request-reads-aggregates-as-stored-test
  (mock/with-mock-dal
    (let [seen
          (atom [])

          ctx
          (register-tags mock/ctx seen)

          id
          (uuid/gen)]
      (mock/apply-cmd ctx {:cmd-id :tag :id id :tag :a})
      (mock/apply-cmd ctx {:cmd-id :tag :id id :tag :b})

      (testing "a set an event handler built comes back as the store returns it"
        (is
         (= [nil [:a]]
            @seen))

        (is
         (= [:a :b]
            (sort (:tags (mock/query ctx {:query-id :get-tagged
                                          :id id})))))

        (is
         (vector?
          (:tags (mock/query ctx {:query-id :get-tagged
                                  :id id}))))))))

(defn- record
  [request]
  (merge {:request-id (uuid/gen)
          :interaction-id (uuid/gen)
          :meta {:realm :test
                 :user {:id "svc" :role :non-interactive}}}
         request))

(deftest a-batch-of-records-behaves-like-its-records-one-by-one-test
  (mock/with-mock-dal
    (let [seen
          (atom [])

          svc
          (runtime/start (register-tags mock/ctx seen))

          id
          (uuid/gen)

          handled
          (runtime/handle-records svc [(record {:commands [{:cmd-id :tag :id id :tag :a}]})
                                       (record {:apply {:aggregate-id id}})
                                       (record {:commands [{:cmd-id :tag :id id :tag :b}]})])]
      (is
       (= [false 3]
          [(:redeliver? handled) (count (:responses handled))]))

      (testing "the second command reads the aggregate the apply stored"
        (is
         (= [nil [:a]]
            @seen))))))
