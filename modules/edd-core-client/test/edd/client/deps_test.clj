(ns edd.client.deps-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.client.credentials :as credentials]
            [edd.client.deps :as sut]
            [edd.ctx :as edd-ctx]
            [edd.el.query :as query]
            [edd.test.fixture.dal :as mock]
            [lambda.http-client :as http-client]
            [lambda.test.fixture.client :as client]
            [lambda.util :as util]))

(def request-id #uuid "22222eeb-e677-4d73-a10a-1d08b45fe4dd")

(def interaction-id #uuid "33333eeb-e677-4d73-a10a-1d08b45fe4dd")

(def meta {:realm :realm4})

(def static-credentials
  {:init (fn [_ctx] "#id-token")
   :authorize (fn [_ctx token request]
                (credentials/token-header request token))})

(defn- ctx
  [& [config]]
  (let [registered
        (sut/register (assoc mock/ctx
                             :meta meta
                             :request-id request-id
                             :interaction-id interaction-id)
                      (merge {:credentials static-credentials} config))]
    (edd-ctx/init-features registered)))

(def remote-dep
  {:service :some-remote-service
   :query (fn [_ctx _cmd] {:query-id :some-query-id
                           :a :b})})

(deftest query-dep-over-http-test
  (with-redefs [util/get-env (fn [v & [default]]
                               (get {"PrivateHostedZoneName" "mock.com"} v default))]
    (client/mock-http
     [{:post (sut/query-url :some-remote-service meta)
       :body (util/to-json {:result {:a :b}})}]
     (is
      (= {:a :b}
         (query/resolve-remote-dependency (ctx) {} remote-dep)))

     (client/verify-traffic [{:body (util/to-json {:query {:query-id :some-query-id
                                                           :a :b}
                                                   :meta meta
                                                   :request-id request-id
                                                   :interaction-id interaction-id})
                              :headers {"X-Authorization" "#id-token"
                                        "Content-Type" "application/json"}
                              :method :post
                              :idle-timeout 10000
                              :connect-timeout 300
                              :url "https://api.mock.com/private/prod/some-remote-service/query"}]))))

(deftest error-responses-test
  (testing "error body of the target service"
    (client/mock-http
     [{:post (sut/query-url :some-remote-service meta)
       :body (util/to-json {:exception "Sorry"})}]
     (is
      (= {:error {:to-service :some-remote-service
                  :from-service :local-test
                  :query-id :some-query-id
                  :ref nil
                  :message {:response {:exception "Sorry"}
                            :error-source :some-remote-service}}}
         (try
           (query/resolve-remote-dependency (ctx) {} remote-dep)
           (catch Exception e
             (ex-data e)))))))

  (testing "unsuccessful status"
    (client/mock-http
     [{:post (sut/query-url :some-remote-service meta)
       :status 404
       :body (util/to-json {:message "Not found"})}]
     (is
      (= {:status 404
          :message "Response status:404"}
         (try
           (query/resolve-remote-dependency (ctx) {} remote-dep)
           (catch Exception e
             (select-keys (:error (ex-data e)) [:status :message]))))))))

(deftest rtf-dep-test
  (with-redefs [util/get-env (fn [v & [default]]
                               (get {"PrivateHostedZoneName" "mock.com"} v default))]
    (client/mock-http
     [{:get "https://glms-content-svc.mock.com/load/some-remote-service/file-1"
       :body "content"}]
     (is
      (= "content"
         (query/resolve-remote-dependency (ctx)
                                          {}
                                          {:service :some-remote-service
                                           :query (fn [_ctx _cmd] {:ref "file-1"})}))))))

(deftest env-credentials-test
  (with-redefs [util/get-env (fn [v & [default]]
                               (get {"ServiceToken" "#env-token"} v default))]
    (client/mock-http
     [{:post (sut/query-url :some-remote-service meta)
       :body (util/to-json {:result {:a :b}})}]
     (query/resolve-remote-dependency (ctx {:credentials (credentials/env)}) {} remote-dep)

     (is
      (= "#env-token"
         (get-in (client/traffic 0) [:headers "X-Authorization"]))))))

(deftest credentials-init-once-test
  (let [inits
        (atom 0)

        counting
        {:init (fn [_ctx] (swap! inits inc) "#id-token")
         :authorize (fn [_ctx token request]
                      (credentials/token-header request token))}

        initialized
        (ctx {:credentials counting})]
    (client/mock-http
     {:responses [{:post (sut/query-url :some-remote-service meta)
                   :body (util/to-json {:result {:a :b}})}]
      :config {:reuse-responses true}}
     (query/resolve-remote-dependency initialized {} remote-dep)
     (query/resolve-remote-dependency initialized {} remote-dep))

    (is
     (= 1
        @inits))))

(deftest transport-failures-test
  (doseq [[description response] [["connection error" (fn [_url _request] {:error "ConnectionTimeout"})]
                                  ["client error status" (fn [_url _request] {:status 499 :body {:response true}})]
                                  ["timeout" (fn [_url _request] (throw (java.net.http.HttpTimeoutException. "")))]]]
    (testing description
      (with-redefs [util/http-post response
                    http-client/retry-count 1]
        (is
         (thrown? clojure.lang.ExceptionInfo
                  (query/resolve-remote-dependency (ctx) {} remote-dep)))))))
