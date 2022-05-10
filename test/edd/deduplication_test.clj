(ns edd.deduplication-test
  (:require
   [clojure.test :refer [deftest is]]
   [edd.core :as edd]
   [edd.common :as common]
   [lambda.uuid :as uuid]
   [edd.test.fixture.dal :as mock]))

(def ctx
  (-> mock/ctx
      (assoc :service-name :local-test)
      (edd/reg-cmd :do-sth
                   (fn [_ctx _cmd]
                     {:event-id :sth-done})
                   :deps {:sth
                          (fn [_ctx cmd]
                            {:query-id :get-by-id
                             :id       (:id cmd)})})

      (edd/reg-query :get-by-id common/get-by-id)))

(def id1 (uuid/parse "111111-1111-1111-1111-111111111111"))
(def request-id (uuid/parse "222222-1111-1111-1111-111111111111"))

(def cmd {:commands    [{:cmd-id :do-sth
                         :id     id1}]
          :breadcrumbs [0]})

(deftest test-command-with-no-response-gets-processed
  (mock/with-mock-dal
    ctx
    (let [resp
          (mock/handle-commands (assoc ctx
                                       :request-id request-id)
                                cmd)
          breadcrumbs (select-keys
                       (first (mock/peek-state :response-log))
                       [:request-id :breadcrumbs])]
      (is (= {:result {:success    true
                       :effects    []
                       :events     1
                       :meta       [{:do-sth {:id id1}}]
                       :identities 0
                       :sequences  0}}
             resp))
      (is (= {:request-id  request-id
              :breadcrumbs [0]}
             breadcrumbs)))))


