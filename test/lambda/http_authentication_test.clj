(ns lambda.http-authentication-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [lambda.http :as http]
            [lambda.runtime.memory :as runtime]
            [lambda.util :as util])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private base
  {:service-name :auth-test-svc
   :environment-name-lower "local"
   :hosted-zone-name "example.com"})

(defn- seen-meta
  [ctx _request]
  {:meta (:meta ctx)})

(defn- post
  [filters request]
  (let [response
        (runtime/handle-http (runtime/start base seen-meta :filters filters)
                             {:path "/"
                              :httpMethod "POST"
                              :headers {}
                              :body (util/to-json request)})]
    {:status (:statusCode response)
     :body (util/to-edn (:body response))}))

(defn- authenticate-as
  [user]
  (http/authenticate (fn [_ctx] user)))

(deftest filter-resolves-the-user-of-an-http-request-test
  (let [response
        (post [(authenticate-as {:id "ann" :role :editor :realm :test})]
              {:meta {:realm :other
                      :trace "t1"}})]

    (is
     (= 200
        (:status response)))

    (testing "the authenticated user and realm win over the request meta"
      (is
       (= {:realm :test
           :trace "t1"
           :user {:id "ann"
                  :role :editor}}
          (get-in response [:body :meta]))))))

(deftest non-interactive-user-keeps-its-request-meta-test
  (let [response
        (post [(authenticate-as {:id "svc" :role :non-interactive})]
              {:meta {:realm :other
                      :user {:id "caller"}}})]

    (is
     (= {:realm :other
         :user {:id "caller"}}
        (get-in response [:body :meta])))))

(deftest http-request-without-a-resolved-user-is-refused-test
  (let [response
        (post [] {:meta {:realm :test}})]

    (is
     (= 500
        (:status response)))

    (is
     (re-find #"No user resolved for the HTTP request"
              (str (:body response))))))

(deftest other-sources-pass-the-filter-test
  (let [ctx
        (runtime/start base seen-meta :filters [(authenticate-as {:id "ann" :role :editor :realm :test})])

        {:keys [responses]}
        (runtime/handle-records ctx [{:body (util/to-json {:meta {:realm :queue}})}])]

    (is
     (= [{:meta nil}]
        responses))))

(deftest resolve-user-feature-is-refused-test
  (is
   (thrown-with-msg? ExceptionInfo
                     #":resolve-user was removed"
                     (edd/reg-feature base :my/auth
                                      :resolve-user (fn [_ctx] {})))))
