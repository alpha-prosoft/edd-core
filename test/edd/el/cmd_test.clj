(ns edd.el.cmd-test
  (:require [clojure.test :refer [deftest is]]
            [edd.el.cmd :as el-cmd]
            [aws.runtime :as runtime]
            [edd.core :as edd]
            [lambda.filters :as fl]
            [lambda.util :as util]
            [lambda.test.fixture.client :as client]
            [edd.test.fixture.dal :as mock]
            [lambda.test.fixture.core :refer [mock-core] :as core-mock]
            [lambda.api-test :as api-test]
            [lambda.uuid :as uuid]
            [sdk.aws.sqs :as sqs]
            [edd.el.ctx :as el-ctx]
            [lambda.request :as request]
            [aws.aws :as aws])
  (:import (clojure.lang ExceptionInfo)))

(def ctx (-> mock/ctx
             (edd/reg-cmd :ssa (fn [_ _]))))

(deftest test-empty-commands-list
  (try
    (el-cmd/validate-commands ctx [])
    (catch ExceptionInfo ex
      (is (= {:error "No commands present in request"}
             (ex-data ex))))))

(deftest test-invalid-command
  (try
    (el-cmd/validate-commands ctx [{}])
    (catch ExceptionInfo ex
      (is (= {:error [{:cmd-id ["missing required key"]}]}
             (ex-data ex))))))

(deftest test-invalid-cmd-id-type
  (try
    (el-cmd/validate-commands ctx [{:id     (uuid/gen)
                                    :cmd-id "wrong"}])
    (catch ExceptionInfo ex
      (is (= {:error [{:cmd-id ["should be a keyword"]}]}
             (ex-data ex)))))

  (try
    (el-cmd/validate-commands ctx [{:cmd-id :test
                                    :id     (uuid/gen)}])
    (catch ExceptionInfo ex
      (is (= {:error ["Missing handler: :test"]}
             (ex-data ex))))))

(deftest test-custom-schema
  (let [ctx (edd/reg-cmd ctx
                         :test (fn [_ _])
                         :consumes [:map [:name :string]])
        cmd-missing {:cmd-id :test-unknown
                     :id     (uuid/gen)}
        cmd-invalid {:cmd-id :test
                     :id     (uuid/gen)
                     :name   :wrong}
        cmd-valid {:cmd-id :test
                   :name   "name"
                   :id     (uuid/gen)}]

    (try
      (el-cmd/validate-commands ctx [cmd-missing cmd-invalid cmd-valid])
      (catch ExceptionInfo ex
        (is (= {:error ["Missing handler: :test-unknown"
                        {:name ["should be a string"]}
                        :valid]}
               (ex-data ex)))))))

(defn register []
  (edd/reg-cmd mock/ctx
               :ping
               (fn [_ _] {:event-id :ping})))

(deftest api-handler-test
  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :commands       [{:cmd-id :ping}]}]
    (mock-core
     {:env {"Region" "eu-west-1"}}
     (runtime/lambda-requests
      (register)
      edd/handler
      [(api-test/cognito-authorizer-request (util/to-json cmd))]
      :filters [fl/from-api])

     (is (= [{:body   {:body            {:invocation-id  core-mock/inocation-id-0
                                         :request-id     request-id
                                         :interaction-id interaction-id
                                         :exception          [{:id ["missing required key"]}]}
                       :headers         fl/default-headers
                       :isBase64Encoded false
                       :statusCode      200}
              :method :post
              :url   core-mock/response-endpoint-o}]
            (client/traffic-edn))))))

(deftest api-handler-response-test

  (let [request-id (uuid/gen)
        interaction-id (uuid/gen)
        id (uuid/gen)
        ctx (register)
        cmd {:request-id     request-id,
             :interaction-id interaction-id,
             :user           {:selected-role :users}
             :commands       [{:cmd-id :ping
                               :id     id}]}]
    (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                    (is (= {:Records [{:key (str "response/"
                                                                 request-id
                                                                 "/0/local-svc.json")}]}
                                           (util/to-edn message))))]
      (mock/with-mock-dal
        {:env {"Region" "eu-west-1"}}
        (runtime/lambda-requests
         ctx
         edd/handler
         [(api-test/cognito-authorizer-request (util/to-json cmd))]
         :filters [fl/from-api])
        (do
          (mock/verify-state :event-store [{:event-id  :ping
                                            :event-seq 1
                                            :id        id
                                            :meta      {:realm :test
                                                        :user
                                                        {:id "rbi-glms-m2m-prod@rbi.cloud",
                                                         :roles [:users],
                                                         :email "rbi-glms-m2m-prod@rbi.cloud",
                                                         :role :users}}}])
          (is (= [{:body   {:body            {:result         {:success    true
                                                               :effects    []
                                                               :events     1
                                                               :meta       [{:ping {:id id}}]
                                                               :identities 0
                                                               :sequences  0}
                                              :invocation-id  core-mock/inocation-id-0
                                              :request-id     request-id
                                              :interaction-id interaction-id}
                            :headers        fl/default-headers
                            :isBase64Encoded false
                            :statusCode      200}
                   :method :post
                   :url    core-mock/response-endpoint-o}]
                 (client/traffic-edn))))))))

(deftest test-cache-partitioning
  (let [ctx {:service-name "local-svc"
             :breadcrumbs  "0"
             :request-id   "1"}]
    (is (= {:key "response/1/0/local-svc.json"}
           (el-cmd/resp->cache-partitioned ctx {:effects [{:a :b}]})))
    (is (= [{:key "response/1/0/local-svc-part.0.json"}
            {:key "response/1/0/local-svc-part.1.json"}]
           (el-cmd/resp->cache-partitioned (el-ctx/set-effect-partition-size ctx 2)
                                           {:effects [{:a :b} {:a :b} {:a :b}]})))))

(deftest enqueue-response
  (binding [request/*request* (atom {:cache-keys [{:key "response/0/0/local-svc-0.json"}
                                                  {:key "response/1/0/local-svc-part.0.json"}
                                                  {:key "response/1/0/local-svc-part.1.json"}
                                                  {:key "response/2/0/local-svc-2.json"}]})]
    (let [messages (atom [])]
      (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                      (swap! messages #(conj % message)))]
        (aws/enqueue-response ctx {})
        (is (= [{:Records [{:key "response/0/0/local-svc-0.json"}]}
                {:Records [{:key "response/1/0/local-svc-part.0.json"}]}
                {:Records [{:key "response/1/0/local-svc-part.1.json"}]}
                {:Records [{:key "response/2/0/local-svc-2.json"}]}]
               (map
                #(util/to-edn %)
                @messages))))))
  (binding [request/*request* (atom {:cache-keys [{:key "response/0/0/local-svc-0.json"}
                                                  [{:key "response/1/0/local-svc-part.0.json"}
                                                   {:key "response/1/0/local-svc-part.1.json"}]
                                                  {:key "response/2/0/local-svc-2.json"}]})]
    (let [messages (atom [])]
      (with-redefs [sqs/sqs-publish (fn [{:keys [message]}]
                                      (swap! messages #(conj % message)))]
        (aws/enqueue-response ctx {})
        (is (= [{:Records [{:key "response/0/0/local-svc-0.json"}]}
                {:Records [{:key "response/1/0/local-svc-part.0.json"}]}
                {:Records [{:key "response/1/0/local-svc-part.1.json"}]}
                {:Records [{:key "response/2/0/local-svc-2.json"}]}]
               (map
                #(util/to-edn %)
                @messages)))))))
