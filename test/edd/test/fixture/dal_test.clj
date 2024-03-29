(ns edd.test.fixture.dal-test
  (:require [edd.test.fixture.dal :as mock]
            [edd.common :as common]
            [edd.search :as search]
            [edd.core :as edd]
            [edd.dal :as event-store]
            [edd.memory.event-store :as dal]
            [edd.view-store.elastic :as elastic-view]
            [edd.view-store.impl.elastic.main :as elastic-view-impl]
            [edd.view-store.common :as view-store]
            [clojure.test :refer [deftest testing is]]
            [lambda.test.fixture.core :as core-mock]
            [org.httpkit.client :as http]
            [lambda.util :as util]
            [lambda.uuid :as uuid])
  (:import (clojure.lang ExceptionInfo)))

(def ctx mock/ctx)

(deftest when-store-and-load-events-then-ok
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx {:id 1 :info "info"})
    (mock/verify-state [{:id 1 :info "info"}] :event-store)
    (let [events (event-store/get-events (assoc ctx
                                                :id 1))]
      (is (= [{:id 1 :info "info"}]
             events)))))

(deftest when-update-snapshot-then-ok
  (mock/with-mock-dal
    ctx
    (view-store/update-snapshot ctx {:id 1 :payload "payload"})
    (mock/verify-state [{:id      1
                         :payload "payload"}] :aggregate-store)
    (view-store/update-snapshot ctx {:id 1 :payload "payload2"})
    (mock/verify-state [{:id      1
                         :payload "payload2"}] :aggregate-store)))

(deftest when-query-aggregate-with-unknown-condition-then-return-nothing
  (mock/with-mock-dal
    ctx
    (view-store/update-snapshot ctx {:id 1 :payload "payload"})
    (view-store/update-snapshot ctx {:id 2 :payload "payload"})
    (view-store/update-snapshot ctx {:id 3 :payload "pa2"})
    (is (= [{:id 3 :payload "pa2"}]
           (common/simple-search ctx {:query {:id 3}})))
    (is (= []
           (common/simple-search ctx {:query {:id 4}})))))

(deftest when-store-sequence-then-ok
  (mock/with-mock-dal
    ctx
    (dal/store-sequence ctx {:id "id1"})
    (dal/store-sequence ctx {:id "id2"})
    (mock/verify-state [{:id    "id1"
                         :value 1}
                        {:id    "id2"
                         :value 2}] :sequence-store)
    (is (= 1
           (event-store/get-sequence-number-for-id (assoc ctx
                                                          :id "id1"))))
    (is (= 2
           (event-store/get-sequence-number-for-id (assoc ctx
                                                          :id "id2"))))
    (is (= "id1"
           (event-store/get-id-for-sequence-number (assoc ctx
                                                          :sequence 1))))
    (is (= "id2"
           (event-store/get-id-for-sequence-number (assoc ctx
                                                          :sequence 2))))))

(deftest when-sequnce-null-then-fail
  (mock/with-mock-dal
    ctx
    (is (thrown? AssertionError
                 (dal/store-sequence ctx {:id nil})))

    (dal/store-sequence ctx {:id "id2"})
    (is (= "id2"
           (event-store/get-id-for-sequence-number (assoc ctx
                                                          :sequence 1))))
    (is (thrown? AssertionError
                 (event-store/get-id-for-sequence-number (assoc ctx
                                                                :sequence nil))))
    (is (thrown? AssertionError
                 (event-store/get-sequence-number-for-id (assoc ctx
                                                                :id nil))))))

(deftest when-sequence-exists-then-exception
  (mock/with-mock-dal
    ctx
    (dal/store-sequence ctx {:id 1})
    (is (thrown? RuntimeException
                 (dal/store-sequence
                  ctx
                  {:id 1})))
    (mock/verify-state [{:id    1
                         :value 1}] :sequence-store)))

(deftest when-store-identity-then-ok
  (mock/with-mock-dal
    ctx
    (dal/store-identity ctx {:identity 1
                             :id       1})
    (dal/store-identity ctx {:identity 2
                             :id       2})
    (mock/verify-state [{:identity 1
                         :id       1}
                        {:identity 2
                         :id       2}] :identity-store)))

(deftest when-identity-exists-then-exception
  (mock/with-mock-dal
    ctx
    (dal/store-identity ctx {:identity 1})
    (is (thrown? RuntimeException
                 (dal/store-identity
                  ctx
                  {:identity 1})))
    (mock/verify-state [{:identity 1}] :identity-store)))

(deftest when-store-command-then-ok
  (mock/with-mock-dal
    ctx
    (dal/store-command ctx {:service "test-service" :payload "payload"})
    (mock/verify-state [{:service "test-service"
                         :payload "payload"}] :command-store)))

(deftest when-identity-exists-then-id-for-aggregate-can-be-fetched
  (mock/with-mock-dal
    ctx
    (dal/store-identity ctx {:identity 1
                             :id       2})
    (mock/verify-state [{:identity 1
                         :id       2}] :identity-store)
    (is (= 2
           (event-store/get-aggregate-id-by-identity (assoc ctx
                                                            :identity 1))))))

(def events
  [{:event-id  :name
    :name      "Me"
    :event-seq 1
    :id        2}
   {:event-id  :name
    :name      "Your"
    :event-seq 2
    :id        2}])

(deftest test-that-events-can-be-fetched-by-aggregate-id
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx (first events))
    (dal/store-event ctx (second events))
    (dal/store-event ctx {:event-id  :name
                          :name      "Bla"
                          :event-seq 3
                          :id        4})
    (mock/verify-state (conj events {:event-id  :name
                                     :name      "Bla"
                                     :event-seq 3
                                     :id        4}) :event-store)
    (is (= {:name    "Your"
            :version 2
            :id      2}
           (-> ctx
               (edd/reg-event
                :name (fn [_state event]
                        {:name (:name event)
                         :id   (:id event)}))
               (mock/get-by-id {:id 2})
               :result)))))

(deftest when-no-result-by-id-return-nil
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx (first events))
    (mock/verify-state [(first events)] :event-store)
    (is (= {:result nil}
           (mock/get-by-id ctx {:id 5})))))

(deftest when-no-id-passed-in-return-nil
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx (first events))
    (mock/verify-state [(first events)] :event-store)
    (is (= nil
           (common/get-by-id ctx {})))))

(deftest verify-predefined-state
  (mock/with-mock-dal
    (assoc ctx
           :event-store [{:event-id :e1}])
    (mock/verify-state :event-store [{:event-id :e1}])
    (mock/verify-state [] :command-store)
    (dal/store-event ctx (first events))
    (mock/verify-state [{:event-id :e1} (first events)] :event-store)
    (is (= nil
           (:aggregate (mock/get-by-id ctx {:id 5}))))))

(def v1
  {:id  1
   :at1 "val1-1"
   :at2 {:at3 "val1-3"
         :at4 "val1-4"}})

(def v2
  {:id  2
   :at1 "val2-1"
   :at2 {:at3 "val2-3"
         :at4 "val2-4"}})

(deftest test-simple-search-result
  (mock/with-mock-dal
    (view-store/update-snapshot ctx v1)
    (view-store/update-snapshot ctx v2)
    (let [resp
          (elastic-view/simple-search
           ctx
           {:query-id :id1
            :at1      "val2-1"
            :at2      {:at4 "val2-4"}})]
      (is (= v2
             (first resp))))))

(def e1
  {:id        1
   :event-seq 1})

(def e2
  {:id        1
   :event-seq 2})

(deftest test-event-seq-and-event-order
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx e1)
    (dal/store-event ctx e2)
    (is (= 2 (event-store/get-max-event-seq (assoc ctx :id 1))))
    (is (= 0 (event-store/get-max-event-seq (assoc ctx :id 3))))
    (is (= [e1 e2]
           (event-store/get-events (assoc ctx :id 1))))))

(deftest test-reverse-event-order-1
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx e1)
    (dal/store-event ctx e2)
    (is (= [e1 e2]
           (event-store/get-events (assoc ctx
                                          :id 1))))))

(deftest test-reverse-event-order-2
  (mock/with-mock-dal
    (assoc ctx
           :command-store [{:cmd 1}]
           :event-store [{:event :bla}])
    (is (= {:aggregate-store []
            :command-store   [{:cmd 1}]
            :event-store     [{:event :bla}]
            :identity-store  []
            :sequence-store  []
            :command-log     []
            :response-log    []}
           (mock/peek-state)))

    (is (= [{:cmd 1}]
           (mock/peek-state :command-store)))
    (is (= [{:event :bla}]
           (mock/peek-state :event-store)))

    (is (= [{:event :bla}]
           (mock/pop-state :event-store)))

    (is (= {:aggregate-store []
            :command-store   [{:cmd 1}]
            :event-store     []
            :identity-store  []
            :sequence-store  []
            :command-log     []
            :response-log    []}
           (mock/peek-state)))))

(deftest test-simple-query
  (is (= {:size  600
          :query {:bool
                  {:must
                   [{:term {:first.keyword "zeko"}}
                    {:term {:last.two.keyword "d"}}]}}}
         (util/to-edn (elastic-view-impl/create-simple-query {:first "zeko"
                                                              :last  {:two "d"}})))))
(def elk-objects
  [{:attrs   {:cocunut           "123456"
              :cognos            "Some random"
              :company           "Edd Imobilien"
              :ifrs              "Group Corporates & Markets"
              :int               "Financial Institution acc. CRR /BWG"
              :iso               "DE"
              :key-account       "Somebody"
              :oenb-id-number    "8324301"
              :parent-id         "#74094776-3cd9-4b48-9deb-4c38b3c96435"
              :parent-short-name "RLGMBH GROUP"
              :rbi-group-cocunut "222996"
              :rbi-knr           ""
              :reporting-ccy     "EUR"
              :share-in-%        "6,00%"
              :short-code        "ABEDD"
              :short-name        "ABEDD"
              :sorter            "Edd Austria"
              :type              ":booking-company"}
    :cocunut "222996"
    :id      "#e1a1e96f-93bb-4fdd-9605-ef2b38c1c458"
    :parents ["#74094776-3cd9-4b48-9deb-4c38b3c96435"]
    :state   ":detached"}
   {:attrs   {:cocunut           "188269"
              :cognos            "ACDE"
              :company           "Edd Immmo DE"
              :ifrs              "Group Corporates & Markets"
              :int               "Financial Institution acc. CRR /BWG"
              :iso               "DE"
              :key-account       "Edd Swing"
              :oenb-id-number    "8142262"
              :parent-id         "#74094776-3cd9-4b48-9deb-4c38b3c96435"
              :parent-short-name "EDD GROUP"
              :rbi-group-cocunut "123666"
              :rbi-knr           ""
              :reporting-ccy     "EUR"
              :share-in-%        "100,00%"
              :short-code        "ACEDD"
              :short-name        "ACEDD"
              :sorter            "Edd Austria"
              :type              ":booking-company"}
    :cocunut "188269"
    :id      "#7c30b6a3-2816-4378-8ed9-0b73b61012d4"
    :parents ["#74094776-3cd9-4b48-9deb-4c38b3c96435"]
    :state   ":detached"}])

(def elk-response
  (future {:opts    {:body      (util/to-json {:size 600
                                               :query
                                               {:bool
                                                {:must
                                                 [{:match
                                                   {:attrs.type ":booking-company"}}]}}}),
                     :headers   {"Content-Type" "application/json"
                                 "X-Amz-Date"   "20200818T113334Z"},
                     :timeout   5000,
                     :keepalive -1,
                     :method    :post
                     :url       "https://vpc-mock.eu-central-1.es.amazonaws.com/glms_risk_taker_svc/_search"}
           :body    (util/to-json {:took      42,
                                   :timed_out false,
                                   :_shards
                                   {:total      5,
                                    :successful 5,
                                    :skipped    0,
                                    :failed     0},
                                   :hits
                                   {:total     {:value 2, :relation "eq"},
                                    :max_score 0.09304003,
                                    :hits
                                    [{:_index  "glms_risk_taker_svc",
                                      :_type   "_doc",
                                      :_id
                                      "e1a1e96f-93bb-4fdd-9605-ef2b38c1c458",
                                      :_score  0.09304003,
                                      :_source (first elk-objects)}
                                     {:_index  "glms_risk_taker_svc",
                                      :_type   "_doc",
                                      :_id
                                      "7c30b6a3-2816-4378-8ed9-0b73b61012d4",
                                      :_score  0.09304003,
                                      :_source (second elk-objects)}]}})
           :headers {:access-control-allow-origin "*"
                     :connection                  "keep-alive"
                     :content-encoding            "gzip"},
           :status  200}))

(deftest elastic-search
  (with-redefs [http/request (fn [__ & _options] elk-response)]
    (is (= elk-objects
           (search/simple-search (-> {}
                                     (elastic-view/register)
                                     (assoc :service-name "test"
                                            :query {})))))))

(deftest when-identity-exists-then-exception
  (let [ctx mock/ctx]
    (mock/with-mock-dal
      ctx
      (dal/store-identity ctx {:id       "id1"
                               :identity 1})
      (is (thrown? RuntimeException
                   (dal/store-identity
                    ctx
                    {:id       "id1"
                     :identity 1})))
      (mock/verify-state :identity-store [{:id       "id1"
                                           :identity 1}]))))

(deftest when-identity-exists-then-ok
  (let [ctx (edd/reg-query ctx :get-by-identities common/get-aggregate-id-by-identity)]
    (mock/with-mock-dal
      ctx
      (dal/store-identity ctx {:id       "id1"
                               :identity 1})
      (dal/store-identity ctx {:id       "id1"
                               :identity 2})
      (mock/verify-state :identity-store [{:id "id1" :identity 1}
                                          {:id "id1" :identity 2}])
      (is (= {:result
              {1 "id1"
               2 "id1"}}
             (mock/query ctx {:query-id :get-by-identities
                              :ids      [1 2 3]})))
      (is (= {:result  "id1"}
             (mock/query ctx {:query-id :get-by-identities
                              :ids      1}))))))

(deftest test-identity-generation
  (let [id (uuid/gen)]
    (mock/with-mock-dal
      (assoc ctx
             :identities {"id1" id})
      (is (= id
             (common/create-identity "id1"))))))

(deftest verify-state-fn-ok-test
  (mock/with-mock-dal
    (assoc ctx
           :aggregate-store [{:a "a"
                              :b "b"}
                             {:a "c"
                              :d "d"}])
    (mock/verify-state-fn :aggregate-store
                          #(dissoc % :a)
                          [{:b "b"}
                           {:d "d"}])))

(deftest apply-cmd-test
  (mock/with-mock-dal
    ctx
    (let [id (uuid/gen)
          ctx (-> mock/ctx
                  (edd/reg-cmd :test-cmd (fn [_ctx _cmd]
                                           {:event-id :1}))
                  (edd/reg-cmd :test-cmd-fx (fn [_ctx _cmd]
                                              {:event-id :2}))
                  (edd/reg-fx (fn [_ctx [event]]
                                (case (:event-id event)
                                  :1 []
                                  :2 {:cmd-id :fx-cmd
                                      :attrs  (dissoc event
                                                      :event-seq)}))))]
      (mock/apply-cmd ctx {:cmd-id :test-cmd
                           :id     id})
      (is (= {:result
              {:effects    []
               :events     1
               :identities 0
               :meta       [{:test-cmd {:id id}}]
               :sequences  0
               :success    true}}
             (mock/handle-cmd ctx {:cmd-id :test-cmd
                                   :id     id})))
      (is (= {:result
              {:effects    []
               :events     [{:event-id  :1
                             :event-seq 3
                             :id        id}]
               :identities []
               :meta       [{:test-cmd {:id id}}]
               :sequences  []}}
             (mock/get-commands-response ctx {:cmd-id :test-cmd
                                              :id     id})))
      (let [request-id (uuid/gen)
            interaction-id (uuid/gen)]
        (testing "With included meta"
          (is (= {:result
                  {:effects    [{:breadcrumbs    [0
                                                  0]
                                 :commands       [{:cmd-id :fx-cmd
                                                   :attrs  {:event-id :2
                                                            :id       id}}]
                                 :interaction-id interaction-id
                                 :meta           {:realm :realm2}
                                 :request-id     request-id
                                 :service        mock/service-name}]
                   :events     [{:event-id       :2
                                 :event-seq      4
                                 :id             id
                                 :meta           {:realm :realm2}
                                 :request-id     request-id
                                 :interaction-id interaction-id}]
                   :identities []
                   :meta       [{:test-cmd-fx {:id id}}]
                   :sequences  []}
                  :invocation-id core-mock/inocation-id-0
                  :request-id request-id
                  :interaction-id interaction-id}
                 (mock/get-commands-response (assoc ctx
                                                    :include-meta true
                                                    :request-id request-id
                                                    :interaction-id interaction-id)
                                             {:commands       [{:cmd-id :test-cmd-fx
                                                                :id     id}]
                                              :meta           {:realm :realm2}
                                              :request-id     request-id
                                              :interaction-id interaction-id}))))
        (testing "Without including meta"
          (mock/verify-state :command-store [{:commands [{:cmd-id :fx-cmd
                                                          :attrs  {:event-id :2
                                                                   :id       id}}]
                                              :meta     {:realm :realm2}
                                              :service  mock/service-name}])
          (is (= {:result
                  {:effects    [{:breadcrumbs [0
                                               0]
                                 :commands    [{:cmd-id :fx-cmd
                                                :attrs  {:event-id :2
                                                         :id       id}}]
                                 :service     mock/service-name}]
                   :events     [{:event-id  :2
                                 :event-seq 5
                                 :id        id}]
                   :identities []
                   :meta       [{:test-cmd-fx {:id id}}]
                   :sequences  []}}
                 (mock/get-commands-response (assoc ctx :request-id request-id
                                                    :interaction-id interaction-id)
                                             {:commands       [{:cmd-id :test-cmd-fx
                                                                :id     id}]
                                              :meta           {:realm :realm2}
                                              :request-id     request-id
                                              :interaction-id interaction-id}))))))))

(defn my-command-handler
  [_ctx _cmd]
  (println "bAAAA"))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :cmd-1 my-command-handler
                   :dps {:facility (fn [cmd]
                                     {:query-id :query-1
                                      :identity (:external cmd)})})
      (edd/reg-query :query-1
                     (fn [ctx query]
                       (let [id (common/get-aggregate-id-by-identity ctx query)]
                         (common/get-by-id ctx id))))))

(deftest test-identity-query
  (let [agg-id (uuid/gen)
        ctx (with-redefs [my-command-handler (fn [ctx _cmd]
                                               (is (= {:attrs   {:some :value}
                                                       :id      agg-id
                                                       :version 3}
                                                      (:facility ctx)))
                                               [])]
              (register ctx))]
    (mock/with-mock-dal
      (assoc ctx
             :identity-store [{:id       agg-id
                               :identity "ext-1"}]
             :aggregate-store [{:version 3
                                :id      agg-id
                                :attrs   {:some :value}}])
      (mock/apply-cmd ctx
                      {:cmd-id   :cmd-1
                       :id       (uuid/gen)
                       :external "ext-1"}))))

(deftest storing-same-event-twice-shoulf-faile
  (mock/with-mock-dal
    ctx
    (dal/store-event ctx {:id        2
                          :info      "info"
                          :event-seq 1})
    (dal/store-event ctx {:id        2
                          :info      "info 1"
                          :event-seq 2})
    (is (thrown? ExceptionInfo
                 (dal/store-event ctx {:id        1
                                       :info      "info"
                                       :event-seq 1})
                 (dal/store-event ctx {:id        1
                                       :info      "info 1"
                                       :event-seq 1})))))
