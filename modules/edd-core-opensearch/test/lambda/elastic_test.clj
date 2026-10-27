(ns lambda.elastic-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [edd.elastic.view-store :as view-store]
            [lambda.elastic :as sut]
            [lambda.http-client :as client]
            [lambda.util :as util]))

(defn- sent-headers
  [elastic-search]
  (let [sent
        (atom nil)]
    (with-redefs [sut/create-date (fn [] "20210322T232540Z")
                  client/retry-n (fn [f & _]
                                   (f 3))
                  util/http-get (fn [_url request & _]
                                  (reset! sent (:headers request))
                                  {:status 200 :body "{}"})]
      (sut/query {:method "GET"
                  :path "/index/_doc/1"
                  :elastic-search elastic-search
                  :aws {:region "eu-west-1"
                        :aws-access-key-id "key"
                        :aws-secret-access-key "secret"
                        :aws-session-token "token"}}))
    @sent))

(deftest signing-test
  (testing "SigV4 by default"
    (let [headers
          (sent-headers {:url "search.local:9200"})]
      (is
       (str/starts-with? (get headers "Authorization") "AWS4-HMAC-SHA256"))

      (is
       (= "token"
          (get headers "X-Amz-Security-Token")))))

  (testing "plain clusters without signing"
    (let [headers
          (sent-headers {:url "search.local:9200" :auth :none})]
      (is
       (nil? (get headers "Authorization")))

      (is
       (nil? (get headers "X-Amz-Security-Token"))))))

(deftest register-test
  (with-redefs [util/get-env (fn [name & [default]]
                               (get {"IndexDomainAuth" "none"
                                     "IndexDomainEndpoint" "search.local:9200"}
                                    name
                                    default))]
    (is
     (= {:scheme "https" :url "search.local:9200" :auth :none}
        (:elastic-search (view-store/register {:service-name :svc
                                               :environment-name-lower "dev"
                                               :hosted-zone-name "example.com"}))))))
