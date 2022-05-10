(ns edd.aggregate-test
  (:require [edd.core :as edd]
            [edd.dal :as dal]
            [edd.el.event :as event]
            [clojure.test :refer [deftest testing is]]
            [lambda.util :as util]
            [edd.view-store.elastic :as elastic-view-store]
            [edd.view-store.impl.elastic.mock :as mock-elastic-impl]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]))

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(def apply-ctx
  (-> mock/ctx
      (edd/reg-event
       :event-1 (fn [p v]
                  (assoc p :e1 v)))
      (edd/reg-event
       :event-2 (fn [p v]
                  (assoc p :e2 v)))
      (edd/reg-agg-filter
       (fn [{:keys [agg] :as _ctx}]
         (assoc
          agg
          :filter-result
          (str (get-in agg [:e1 :k1])
               (get-in agg [:e2 :k2])))))))

(deftest test-apply
  (let [agg (event/get-current-state
             (assoc apply-ctx
                    :events
                    [{:event-id  :event-1
                      :id        cmd-id
                      :event-seq 1
                      :k1        "a"}
                     {:event-id  :event-2
                      :id        cmd-id
                      :event-seq 2
                      :k2        "b"}]
                    :id "ag1"))
        agg (:aggregate agg)]
    (is (= {:id            cmd-id
            :filter-result "ab"
            :version       2
            :e1            {:event-id  :event-1,
                            :k1        "a"
                            :event-seq 1
                            :id        cmd-id},
            :e2            {:event-id  :event-2
                            :k2        "b"
                            :event-seq 2
                            :id        cmd-id}}
           agg))))

(deftest test-apply-cmd-storing-error
  (mock/with-mock-dal
    (with-redefs [mock-elastic-impl/update-aggregate-impl (fn [_ _]
                                                            (throw (ex-info "Error saving" {})))
                  dal/get-events (fn [_]
                                   [{:event-id :event-1
                                     :id       cmd-id
                                     :k1       "a"}
                                    {:event-id :event-2
                                     :id       cmd-id
                                     :k2       "b"}])]

      (is (= {:exception {}}
             (mock/handle-event (-> apply-ctx
                                    (elastic-view-store/register :implementation :mock))
                                {:apply {:aggregate-id cmd-id
                                         :apply        :cmd-1}}))))))

(deftest test-apply-cmd-storing-response-error
  (let [posts (atom [])
        gets (atom [])]
    (with-redefs [util/http-request (fn [url {:keys [method] :as request} & {:keys [_raw]}]
                                      (case method
                                        :get (do (swap! gets conj url)
                                                 {:status 404})
                                        :put (do
                                               (swap! posts conj request)
                                               {:status 303
                                                :body   "Sorry"})
                                        :post (do
                                                (swap! posts conj request)
                                                {:status 303
                                                 :body   "Sorry"})))]
      (mock/with-mock-dal
        {:event-store [{:event-id :event-1
                        :id       cmd-id
                        :k1       "a"}
                       {:event-id :event-2
                        :id       cmd-id
                        :k2       "b"}]}

        (is (= {:exception {:message "Sorry", :status 303}}
               (mock/handle-event (-> apply-ctx
                                      elastic-view-store/register)
                                  {:apply {:aggregate-id cmd-id}})))
        (is (= [{:e1
                 {:event-id :event-1,
                  :id #uuid "00111111-1111-1111-1111-111111111111",
                  :k1 "a"},
                 :e2
                 {:event-id :event-2,
                  :id #uuid "00111111-1111-1111-1111-111111111111",
                  :k2 "b"},
                 :filter-result "ab",
                 :id #uuid "00111111-1111-1111-1111-111111111111",
                 :version nil}]
               (->> @posts
                    (map :body)
                    (mapv util/to-edn))))
        (is (= ["https://127.0.0.1:9200/no_realm_local_svc/_doc/00111111-1111-1111-1111-111111111111"]
               @gets))))))
