(ns lambda.test.fixture.client-test
  (:require [clojure.test :refer :all]
            [lambda.util :as util]
            [lambda.test.fixture.client :as client]))

(deftest test-handling-of-request
  (testing
   "When there is no responses"
    (binding [client/*world* (atom {})]
      (is (= {:status 200, :body "{\"result\":null}"}
             @(client/handle-request
               {}
               {:url  "http://google.com"
                :method :get
                :body (util/to-json {:a :b})})))))

  (testing
   "When ther is one matchin response"
    (let [response {:body (util/to-json {:happy :you})}]
      (binding [client/*world* (atom {:mock-traffic {"http://google.com"
                                                     {:get
                                                      {1 {:response response}}}}})]
        (is (= {:status 200, :body (:body response)}
               @(client/handle-request
                 {}
                 {:url  "http://google.com"
                  :method :get}))))))

  (testing
   "When ther is one matchin response"
    (let [request {:body (util/to-json {:happy :me})}
          response {:body (util/to-json {:happy :you})}]
      (binding [client/*world* (atom {:mock-traffic {"http://google.com"
                                                     {:post
                                                      {1 {:response response
                                                          :request request}}}}})]
        (is (= {:status 200, :body (:body response)}
               @(client/handle-request
                 {}
                 {:url  "http://google.com"
                  :method :post
                  :body (:body request)}))))))
  (testing
   "When not matchin request"
    (let [request {:body (util/to-json {:happy :me})}
          response {:body (util/to-json {:happy :you})}]
      (binding [client/*world* (atom {:mock-traffic {"http://google.com"
                                                     {:get
                                                      {1 {:response response
                                                          :request request}}}}})]
        (is (= {:status 200, :body "{\"result\":null}"}
               @(client/handle-request
                 {}
                 {:url  "http://google.com"
                  :method :post
                  :body (:body request)}))))))
  (testing
   "When response fits into request (diff)"
    (let [request {:body (util/to-json {:happy :me})}
          response {:body (util/to-json {:happy :you})}]
      (binding [client/*world* (atom {:mock-traffic {"http://google.com"
                                                     {:post
                                                      {1 {:response (assoc response
                                                                           :status 304)
                                                          :request request}}}}})]
        (is (= {:status 304, :body (:body response)}
               @(client/handle-request
                 {}
                 {:url  "http://google.com"
                  :method :post
                  :headers {:some :garbage}
                  :body {:happy :me
                         :request-id "randon-request-id"}}))))))
  (testing
   "When request fits into response then we ignore it"
    (let [request {:body (util/to-json {:happy :me
                                        :request-id "rangom-id"})}
          response {:body (util/to-json {:happy :you})}]
      (binding [client/*world* (atom {:mock-traffic {"http://google.com"
                                                     {:post
                                                      {1 {:response response
                                                          :request request}}}}})]
        (is (= {:status 200, :body "{\"result\":null}"}
               @(client/handle-request
                 {}
                 {:url  "http://google.com"
                  :method :post
                  :body {:happy :me}})))))))

(deftest test-when-bo-request-body
  (client/mock-http
   {}
   [{:url  "http://google.com"
     :method :get
     :response {:body (util/to-json {:a :b})}}]
   (is (= {:status 200
           :body {:a :b}}
          (util/http-get "http://google.com" {})))

   (is (= (client/traffic-edn)
          [{:method :get
            :url    "http://google.com"}]))))

(deftest test-mock-multiple-calls
  (client/mock-http
   {}
   [{:method :get
     :url "http://google.com"
     :response {:body (util/to-json {:a :b})}}
    {:method :get
     :url "http://google.com"
     :response {:body (util/to-json {:a :c})}}]
   (is (= {:status 200
           :body {:a :b}}
          (util/http-get "http://google.com")))

   (is (= {:status 200
           :body {:a :c}}
          (util/http-get "http://google.com")))

   (is (= [{:method :get
            :url    "http://google.com"}
           {:method :get
            :url    "http://google.com"}]
          (client/traffic)))))

(deftest test-mock-post-calls
  (client/mock-http
   {}
   [{:method :post
     :url "http://google.com"
     :request {:body {:request :payload1}}
     :response {:body (util/to-json {:a :1})}}
    {:method :post
     :url "http://google.com"
     :request {:body {:request :payload2}}
     :response {:body (util/to-json {:a :2})}}]

   (is (= {:status 200
           :body {:a :2}}
          (util/http-post "http://google.com" {:request :payload2})))

   (is (= {:status 200
           :body {:a :1}}
          (util/http-post "http://google.com" {:request :payload1})))

   (client/verify-traffic
    [{:body   "{\"request\":\":payload1\"}"
      :method :post
      :url    "http://google.com"}
     {:body   "{\"request\":\":payload2\"}"
      :method :post
      :url    "http://google.com"}])))

(deftest test-mock-no-json
  (let [request-body "Action=Bla"]
    (client/mock-http
     {}
     [{:method :post
       :url "http://google.com"
       :request {:body request-body}
       :response {:body (util/to-json {:a :b})}}]
     (is (= {:status 200
             :body {:a :b}}
            (util/http-post "http://google.com" request-body)))

     (client/verify-traffic
      [{:method :post
        :body   request-body
        :url    "http://google.com"}]))))

(deftest test-mock-no-no-body-check
  (let [request-body "random body"]
    (client/mock-http
     {}
     [{:method   :post
       :url      "http://google.com"
       :response {:body (util/to-json {:a :b})}}
      {:method   :post
       :url      "http://google.com"
       :response {:body (util/to-json {:a :b})}}]
     (is (= {:status 200
             :body   {:a :b}}
            (util/http-post "http://google.com" request-body)))
     (is (= {:status 200
             :body   "{\"a\":\":b\"}"}
            (util/http-request "http://google.com"
                               {:method :post
                                :body   request-body}
                               :raw true)))

     (client/verify-traffic
      [{:method :post
        :body   request-body
        :url    "http://google.com"}
       {:method :post
        :body   request-body
        :url    "http://google.com"}]))))

(deftest traefic-to-edn-test
  (is (= [{:body {:test :vaue}
           :headers {}}]
         (client/try-parse-traffic-to-edn
          [{:body (util/to-json {:test :vaue})
            :headers {}}])))

  (is (= [{:body {:body
                  {:nested-body :value}}
           :headers {}}]
         (client/try-parse-traffic-to-edn
          [{:body (util/to-json
                   {:body
                    (util/to-json {:nested-body :value})})
            :headers {}}]))))
