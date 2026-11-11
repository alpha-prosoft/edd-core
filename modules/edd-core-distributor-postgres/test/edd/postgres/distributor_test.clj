(ns edd.postgres.distributor-test
  (:require [clojure.test :refer [deftest is]]
            [edd.core :as edd]
            [edd.ctx :as edd-ctx]
            [edd.distribution :as distribution]
            [edd.postgres.distributor :as sut]
            [edd.postgres.distributor.outbox :as outbox]
            [edd.test.fixture.dal :as mock]
            [lambda.uuid :as uuid])
  (:import [clojure.lang ExceptionInfo]
           [java.util.concurrent Semaphore]))

(defn- register
  [ctx]
  (edd/reg-event-fx
   (edd/reg-cmd
    (edd/reg-cmd ctx :start (fn [_ctx cmd]
                              {:event-id :started
                               :id (:id cmd)}))
    :finish (fn [_ctx cmd]
              {:event-id :finished
               :id (:id cmd)}))
   :started (fn [_ctx event]
              {:cmd-id :finish
               :id (:id event)})))

(deftest marker-expands-into-own-requests-test
  (mock/with-mock-dal
    (let [ctx
          (register mock/ctx)

          id
          (uuid/gen)

          request-id
          (uuid/gen)

          interaction-id
          (uuid/gen)

          _
          (mock/handle-cmd ctx {:request-id request-id
                                :interaction-id interaction-id
                                :commands [{:cmd-id :start
                                            :id id}]})

          [apply-request effect :as requests]
          (sut/requests ctx {:request-id request-id
                             :interaction-id interaction-id
                             :breadcrumbs [0]})]

      (is
       (= 2
          (count requests)))

      (is
       (= {:request-id request-id
           :interaction-id interaction-id
           :apply {:aggregate-id id
                   :service (:service-name ctx)}}
          (dissoc apply-request :meta)))

      (is
       (= {:request-id request-id
           :interaction-id interaction-id
           :breadcrumbs [0 0]
           :service (:service-name ctx)
           :commands [{:cmd-id :finish
                       :id id}]}
          (select-keys effect [:request-id :interaction-id :breadcrumbs :service :commands]))))))

(deftest effect-for-another-service-is-rejected-test
  (is
   (thrown-with-msg? ExceptionInfo
                     #"delivers to :own-svc only, effects for \[:other-svc\]"
                     (outbox/add! {:service-name :own-svc
                                   :request-id (uuid/gen)
                                   :breadcrumbs [0]}
                                  {:effects [{:service :own-svc}
                                             {:service :other-svc}]}))))

(defn- started
  [ctx]
  (edd-ctx/init-features (assoc (sut/register ctx) :edd-event-store :postgres)))

(deftest publish-wakes-the-pollers-test
  (let [ctx
        (sut/register {:service-name :own-svc})

        signals
        (:signals (distribution/transport ctx))

        signal
        (#'sut/signal! signals (sut/source :prod))

        results
        (distribution/publish! ctx (distribution/response-destination ctx) [{:id "0"
                                                                             :body "{}"}])]

    (is
     (= [{:id "0"
          :success true}]
        results))

    (is
     (= 1
        (.availablePermits ^Semaphore signal)))))

(deftest polls-the-realms-of-ctx-test
  (is
   (= ["postgres-distributor.prod"]
      (distribution/consume-sources (started {:service-name :own-svc}))))

  (is
   (= ["postgres-distributor.main" "postgres-distributor.test"]
      (distribution/consume-sources (started {:service-name :own-svc
                                              :realms [:main :test]}))))

  (is
   (= []
      (distribution/consume-sources (started {:service-name :own-svc
                                              :edd/consume []})))))

(deftest declares-its-migration-test
  (is
   (= [{:id :edd/postgres-distributor
        :location "classpath:edd/postgres/distributor/migrations"
        :schema "main_distributor"}]
      (mapv (fn [{:keys [schema] :as migration}]
              (assoc migration :schema (schema {} :main)))
            (edd-ctx/get-migrations (sut/register {:service-name :own-svc}))))))

(deftest publishing-elsewhere-fails-test
  (is
   (thrown-with-msg? ExceptionInfo
                     #"delivers to :own-svc only"
                     (distribution/publish! (sut/register {:service-name :own-svc})
                                            "other-svc.prod.commands"
                                            [{:id "0"
                                              :body "{}"}]))))

(deftest other-event-store-fails-at-startup-test
  (is
   (thrown-with-msg? ExceptionInfo
                     #"needs the Postgres event store"
                     (edd-ctx/init-features (sut/register {:service-name :own-svc})))))

(deftest only-this-transport-takes-part-in-storing-test
  (is
   (nil?
    (:on-store (distribution/transport mock/ctx))))

  (is
   (some?
    (:on-store (distribution/transport (sut/register mock/ctx))))))
