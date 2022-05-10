(ns edd.event-store.store-it
  (:require [clojure.test :refer [deftest testing is]]
            [edd.core :as edd]
            [edd.view-store.elastic :as elastic]
            [edd.dynamodb.event-store :as dynamodb]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid]
            [aws.ctx :as aws-ctx]
            [lambda.util :as util]
            [edd.response.cache :as response-cache]
            [edd.dal :as dal]))

(def ctx (-> {}
             (response-cache/register-default)
             (aws-ctx/init)
             (assoc :service-name "it"
                    :environment-name-lower (util/get-env
                                             "EnvironmentNameLower"))
             (assoc-in [:db :name] "dynamodb-svc")
             (elastic/register :implementation :mock)
             (edd/reg-cmd :create-product (fn [_ctx cmd]
                                            {:event-id :product-created
                                             :attrs    (:attrs cmd)}))
             (edd/reg-event-fx :product-created (fn [_ctx _event]
                                                  {:cmd-id :notify-product-created}))))

(deftest effect-storage-test
  (let [interaction-id (uuid/gen)
        request-id (uuid/gen)
        ctx (assoc ctx
                   :interaction-id interaction-id
                   :request-id request-id)
        id (uuid/gen)
        cmd {:cmd-id :create-product
             :id     id
             :attrs  {:name "Product name"}}
        ctx (-> ctx
                (dynamodb/register))]
    (mock/execute-cmd ctx cmd)
    (is (= {:events  [{:attrs          {:name "Product name"}
                       :event-id       :product-created
                       :event-seq      1
                       :id             id
                       :interaction-id interaction-id
                       :meta           {}
                       :request-id     request-id}]
            :effects [{:breadcrumbs    [0
                                        0]
                       :commands       [{:cmd-id :notify-product-created}]
                       :interaction-id interaction-id
                       :meta           {}
                       :request-id     request-id
                       :service        "it"}]}
           (dal/get-records ctx {:interaction-id interaction-id})))))

(deftest test-no-events-generated
  (let [interaction-id (uuid/gen)
        request-id (uuid/gen)
        ctx (assoc ctx
                   :interaction-id interaction-id
                   :request-id request-id)

        id (uuid/gen)
        cmd {:cmd-id :do-nothing
             :id     id}
        ctx (-> ctx
                (edd/reg-cmd :do-nothing (fn [_ctx _cmd]))
                (dynamodb/register))]
    (is (= {:result
            {:effects    []
             :events     0
             :identities 0
             :meta       [{:do-nothing {:id id}}]
             :sequences  0
             :success    true}}
           (mock/handle-cmd ctx cmd)))
    (is (= {:events  []
            :effects []}
           (dal/get-records ctx {:interaction-id interaction-id})))))
