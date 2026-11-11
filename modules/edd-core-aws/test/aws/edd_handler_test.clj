(ns aws.edd-handler-test
  "edd.core/handler on the Lambda runtime with Cognito users and S3 events."
  (:require [aws.cognito :as cognito]
            [aws.filters :as aws-filters]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [edd.core :as edd]
            [edd.distribution.memory :as memory-distribution]
            [edd.el.query :as query]
            [edd.test.fixture.dal :as mock]
            [lambda.api-test :as api :refer [api-request]]
            [aws.lambda :as core]
            [lambda.jwt :as jwt]
            [lambda.s3-test :as s3]
            [lambda.test.fixture.client :refer [verify-traffic-edn]]
            [lambda.test.fixture.core :refer [mock-core]]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [sdk.aws.common :as sdk-common]))

(defn dummy-command-handler
  [_ctx cmd]
  {:event-id :dummy-event
   :id       (:id cmd)
   :handled  true})

(def fx-id #uuid "22222111-1111-1111-1111-111111111111")

(def fx-command
  {:commands [{:cmd-id :fx-command
               :id     fx-id}]
   :service  :local-test})

(defn prepare
  []
  (-> mock/ctx
      (assoc-in [:meta :realm] :test)
      (assoc :service-name :local-test)
      (edd/reg-cmd :dummy-cmd dummy-command-handler)
      (edd/reg-cmd :object-uploaded dummy-command-handler)
      (edd/reg-fx (fn [_ctx _events]
                    fx-command))))

(def cmd-id (uuid/parse "111111-1111-1111-1111-111111111111"))

(def request-id #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491")
(def interaction-id #uuid "2222b7b5-9f50-4dc4-86d1-2e4fe1f6d491")

(defn- verify-distributed-response
  []
  (let [[router-message & more]
        (mapcat val (memory-distribution/published mock/ctx))]
    (is
     (empty? more))

    (is
     (= :local-test
        (get-in router-message [:Records 0 :service])))

    (is
     (= [:fx-command]
        (map #(get-in % [:commands 0 :cmd-id]) (get-in router-message [:Records 0 :resp :effects]))))))

(deftest test-s3-bucket-request
  (mock/with-mock-dal
    (with-redefs [sdk-common/create-date (fn [] "20200426T061823Z")
                  uuid/gen (fn [] request-id)
                  key (str "test/2021-12-27/" interaction-id "/"
                           request-id)]
      (mock-core
       :invocations [(s3/records key)]
       :requests [{:get  (str "https://example-bucket.s3.eu-central-1.amazonaws.com/"
                              key)
                   :body (char-array "Of something")}]
       (core/lambda-custom-runtime
        (cognito/register (prepare))
        edd/handler
        :filters [aws-filters/from-bucket])
       (verify-distributed-response)
       (verify-traffic-edn [{:method  :get
                             :timeout 90000000
                             :url     "http://mock/2018-06-01/runtime/invocation/next"}
                            {:as              :stream
                             :connect-timeout 300
                             :headers         {"Authorization"        "AWS4-HMAC-SHA256 Credential=/20200426/eu-central-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=5c9b0ebe8bc4504c8885115a1699e4d6d3e8d0078aa57e44cff9a8a4d37e0779"
                                               "x-amz-content-sha256" "UNSIGNED-PAYLOAD"
                                               "x-amz-date"           "20200426T061823Z"
                                               "x-amz-security-token" ""}
                             :idle-timeout    5000
                             :method          :get
                             :url             "https://example-bucket.s3.eu-central-1.amazonaws.com/test/2021-12-27/2222b7b5-9f50-4dc4-86d1-2e4fe1f6d491/1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491"}
                            {:body   {:result         {:effects    [{:cmd-id       :fx-command
                                                                     :id           #uuid "22222111-1111-1111-1111-111111111111"
                                                                     :service-name :local-test}]
                                                       :events     1
                                                       :identities 0
                                                       :meta       [{:object-uploaded {:id #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491"}}]
                                                       :success    true}
                                      :invocation-id  0
                                      :interaction-id interaction-id
                                      :request-id     request-id}
                             :method :post
                             :url    "http://mock/2018-06-01/runtime/invocation/0/response"}])))))

(deftest test-api-request-with-fx
  (testing "Test that user is added to events and summary is properly returned"
    (mock/with-mock-dal
      (do
        (mock-core
         :env {"Region" "eu-west-1"}
         :invocations [(api/api-request
                        {:commands       [{:cmd-id :dummy-cmd,
                                           :bla    "ble",
                                           :id     cmd-id}],
                         :user           {:selected-role :group-2}
                         :request-id     request-id,
                         :interaction-id interaction-id})]

         :requests [{:get  "https://example-bucket.s3.eu-central-1.amazonaws.com/test/key"
                     :body (char-array "Of something")}]
         (core/lambda-custom-runtime
          (cognito/register (prepare))
          edd/handler)
         (verify-distributed-response)
         (verify-traffic-edn [{:method  :get
                               :timeout 90000000
                               :url     "http://mock/2018-06-01/runtime/invocation/next"}
                              {:body   {:body            (util/to-json
                                                          {:result         {:success    true
                                                                            :effects    [{:id           fx-id
                                                                                          :cmd-id       :fx-command
                                                                                          :service-name :local-test}]
                                                                            :events     1
                                                                            :meta       [{:dummy-cmd {:id cmd-id}}]
                                                                            :identities 0}
                                                           :invocation-id  0
                                                           :request-id     request-id
                                                           :interaction-id interaction-id})
                                        :headers         {:Access-Control-Allow-Headers  "Id, VersionId, X-Authorization,Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token"
                                                          :Access-Control-Allow-Methods  "OPTIONS,POST,PUT,GET"
                                                          :Access-Control-Allow-Origin   "*"
                                                          :Access-Control-Expose-Headers "*"
                                                          :Content-Type                  "application/json"}
                                        :isBase64Encoded false
                                        :statusCode      200}
                               :method :post
                               :url    "http://mock/2018-06-01/runtime/invocation/0/response"}]))))))

(deftest test-if-meta-is-resolved-to-query

  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        meta {:realm            :realm12
              :some-other-stuff :yes
              :user             {:id    "admin@example.com"
                                 :email "admin@example.com"
                                 :roles [:admin :read-only]
                                 :role  :admin}}
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :meta           meta
             :breadcrumbs [0]
             :query          [{:query-id :get-by-id}]}
        user {:id             ""
              :email          ""
              :cognito:groups ["non-interactive" "realm-test"]}]
    (mock/with-mock-dal
      (with-redefs [jwt/parse-token (fn [_ctx _]
                                      user)
                    query/handle-query (fn [ctx body]
                                         (is (= cmd
                                                body))
                                         (is (= (assoc meta
                                                       :user {:id    "admin@example.com"
                                                              :email "admin@example.com"
                                                              :roles [:admin :read-only]
                                                              :role  :admin})
                                                (:meta ctx))))]
        (mock-core
         :invocations [(api-request cmd)]
         (core/lambda-custom-runtime
          (cognito/register mock/ctx)
          edd/handler)
         (log/info "Nothing nere to check"))))))

(deftest test-query-when-missing-selected-role

  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        realm :realm11
        roles ["roles-account-manager" "realm-realm11"]
        user {:id             ""
              :email          ""
              :cognito:groups roles}
        meta {:realm realm
              :user  user}
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :meta           meta
             :breadcrumbs [0]
             :query          [{:query-id :get-by-id}]}]
    (mock/with-mock-dal
      (with-redefs [jwt/parse-token (fn [_ctx _]
                                      user)
                    query/handle-query (fn [ctx body]
                                         (is (= cmd body))
                                         (is (= {:realm realm
                                                 :user  {:email ""
                                                         :id    ""
                                                         :role  :account-manager
                                                         :roles [:account-manager]}}
                                                (:meta ctx))))]
        (mock-core
         :invocations [(api-request cmd)]
         (core/lambda-custom-runtime
          (cognito/register mock/ctx)
          edd/handler)
         (log/info "Nothing nere to check"))))))
