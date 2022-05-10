(ns edd.el.query-test
  (:require [clojure.test :refer :all]
            [edd.core :as edd]
            [edd.el.query :as query]
            [lambda.filters :as fl]
            [lambda.util :as util]
            [lambda.api-test :as api-test]
            [lambda.uuid :as uuid]
            [aws.runtime :as runtime]
            [edd.test.fixture.dal :as mock]
            [sdk.aws.sqs :as sqs]))

(deftest test-if-meta-is-resolved-to-query

  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        meta {:realm            :realm12
              :some-other-stuff :yes
              :user             {:id    "admin@example.com"
                                 :email "admin@example.com"
                                 :roles [:admin :read-only]
                                 :role  :admin}}
        query {:request-id     request-id,
               :interaction-id interaction-id,
               :query          {:query-id :get-by-id}}
        ctx mock/ctx
        is-body (atom {})
        is-meta (atom {})]
    (testing
     "Check if meta is from user passed to query handler"
      (mock/with-mock-dal
        (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                        (is (= {:Records [{:key (str "response/"
                                                                     request-id
                                                                     "/0/local-test.json")}]}
                                               (util/to-edn message))))
                      query/handle-query (fn [ctx body]
                                           (reset! is-body body)
                                           (reset! is-meta (:meta ctx)))]
          (runtime/lambda-requests
           ctx
           edd/handler
           [(api-test/cognito-authorizer-request (util/to-json query))]
           :filters [fl/from-api])
          (is (= query
                 @is-body))
          (is (= {:realm :test,
                  :user
                  {:id "rbi-glms-m2m-prod@rbi.cloud",
                   :roles [:users],
                   :email "rbi-glms-m2m-prod@rbi.cloud",
                   :role :users}}
                 @is-meta)))))
    (testing
     "Check ig meta passed from query request is passed into handler. TODO: think about security"
      (mock/with-mock-dal
        (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                        (is (= {:Records [{:key (str "response/"
                                                                     request-id
                                                                     "/0/local-test.json")}]}
                                               (util/to-edn message))))
                      query/handle-query (fn [ctx body]
                                           (reset! is-body body)
                                           (reset! is-meta (:meta ctx)))]
          (runtime/lambda-requests
           ctx
           edd/handler
           [(api-test/cognito-authorizer-request (util/to-json (assoc query
                                                                      :meta meta)))]
           :filters [fl/from-api])
          (is (= (assoc query
                        :meta meta)
                 @is-body))
          (is (= meta
                 @is-meta)))))))

