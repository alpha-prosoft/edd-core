(ns lambda.runtime-records-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambda.http :as http]
            [lambda.runtime :as sut]
            [lambda.runtime.memory :as runtime]
            [lambda.util :as util]))

(def ^:private base
  {:service-name :records-test-svc
   :environment-name-lower "local"
   :hosted-zone-name "example.com"})

(defn- seen
  [ctx request]
  {:user (get-in request [:meta :user])
   :realm (get-in request [:meta :realm])
   :source (get-in ctx [:record :eventSource])})

(defn- record
  [source meta]
  {:eventSource source
   :body (util/to-json {:meta meta})})

(defn- handle
  [filters records]
  (:responses (runtime/handle-records (runtime/start base seen :filters filters) records)))

(deftest queue-records-run-as-the-service-test
  (is
   (= [{:user {:id "records-test-svc"
               :role :non-interactive}
        :realm :test
        :source "edd:kafka"}]
      (handle [(sut/authenticate-records)]
              [(record "edd:kafka" {:realm :test
                                    :user {:id "ann"
                                           :role :editor}})]))))

(deftest only-records-of-the-source-are-filled-in-test
  (let [filters
        [(sut/authenticate-records {:source "edd:kafka"})]]

    (is
     (= "records-test-svc"
        (get-in (first (handle filters [(record "edd:kafka" {:realm :test})])) [:user :id])))

    (testing "a record of another source keeps its meta"
      (is
       (= {:id "ann"}
          (:user (first (handle filters [(record "aws:sqs" {:user {:id "ann"}})]))))))))

(deftest user-comes-from-the-given-function-test
  (is
   (= {:id "ann"
       :role :non-interactive}
      (:user (first (handle [(sut/authenticate-records
                              {:resolve-user (fn [_ctx request]
                                               (assoc (get-in request [:meta :user]) :role :non-interactive))})]
                            [(record "edd:kafka" {:user {:id "ann"
                                                         :role :editor}})]))))))

(deftest http-requests-pass-the-filter-test
  (let [response
        (runtime/handle-http (runtime/start base
                                            (fn [ctx _request]
                                              {:user (get-in ctx [:meta :user])})
                                            :filters [(http/authenticate (fn [_ctx]
                                                                           {:id "ann" :role :editor :realm :test}))
                                                      (sut/authenticate-records)])
                             {:path "/"
                              :httpMethod "POST"
                              :headers {}
                              :body "{}"})]

    (is
     (= {:id "ann"
         :role :editor}
        (:user (util/to-edn (:body response)))))))
