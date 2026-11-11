(ns edd.postgres.distributor-it
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.dal :as dal]
            [edd.flyway :as flyway]
            [edd.distribution :as distribution]
            [edd.memory.view-store :as view-store]
            [edd.postgres.event-store :as event-store]
            [edd.postgres.distributor :as sut]
            [edd.postgres.distributor.outbox :as outbox]
            [edd.postgres.pool :as pool]
            [edd.test.fixture.dal :as mock]
            [lambda.request :as request]
            [lambda.runtime.memory :as runtime]
            [lambda.test.fixture.state :refer [*dal-state*]]
            [lambda.uuid :as uuid]
            [next.jdbc :as jdbc]))

(def ^:private finish-fails?
  (atom false))

(defn- finish
  [_ctx cmd]
  (when @finish-fails?
    (throw (ex-info "Finish is broken" {:id (:id cmd)})))
  {:event-id :finished
   :id (:id cmd)})

(defn- service
  [& {:as options}]
  (let [base
        {:service-name (keyword (str "local-" (uuid/gen)))
         :environment-name-lower "local"
         :hosted-zone-name "example.com"
         :realms [:test]}

        with-stores
        (view-store/register
         (event-store/register
          (sut/register (flyway/register base) (merge {:poll-interval-ms 10} options))))

        with-commands
        (edd/reg-cmd
         (edd/reg-cmd
          (edd/reg-cmd with-stores :start (fn [_ctx cmd]
                                            {:event-id :started
                                             :id (:id cmd)}))
          :finish finish)
         :start-remote (fn [_ctx cmd]
                         {:event-id :remote-started
                          :id (:id cmd)}))

        with-events
        (edd/reg-event
         (edd/reg-event with-commands :started (fn [aggregate _event]
                                                 (assoc aggregate :status :started)))
         :finished (fn [aggregate _event]
                     (assoc aggregate :status :finished)))

        with-effects
        (edd/reg-event-fx
         (edd/reg-event-fx with-events :started (fn [_ctx event]
                                                  {:cmd-id :finish
                                                   :id (:id event)}))
         :remote-started (fn [_ctx event]
                           {:service :other-svc
                            :commands [{:cmd-id :finish
                                        :id (:id event)}]}))]
    (runtime/start with-effects edd/handler)))

(defn- realm
  [svc]
  (assoc svc :meta {:realm :test}))

(defn- command!
  [svc cmd-id id]
  (runtime/handle-request svc {:request-id (uuid/gen)
                               :interaction-id (uuid/gen)
                               :meta {:realm :test}
                               :commands [{:cmd-id cmd-id
                                           :id id}]}))

(defn- consumer
  [svc]
  ((:consumer (distribution/transport svc)) svc (sut/source :test)))

(defn- deliver!
  "Handles one poll like the consumer loop of edd.distribution; returns the
   number of delivered requests."
  [svc {:keys [poll ack retry]}]
  (loop [[message & more :as remaining] (poll)
         delivered 0]
    (cond
      (nil? message)
      delivered

      (:redeliver? (runtime/handle-records svc [(:record message)]))
      (do
        (doseq [unprocessed (reverse remaining)]
          (retry unprocessed))
        delivered)

      :else
      (do
        (ack message)
        (recur more (inc delivered))))))

(defn- markers
  [svc]
  (pool/with-pool [svc]
    (outbox/markers (realm svc))))

(defn- make-due!
  [svc]
  (pool/with-pool [svc]
    (jdbc/execute! pool/*DB*
                   ["UPDATE test_distributor.outbox SET next_attempt_at = now() WHERE service_name = ?"
                    (name (:service-name svc))])))

(defn- event-ids
  [svc id]
  (pool/with-pool [svc]
    (mapv :event-id (dal/get-events (assoc (realm svc) :id id)))))

(defn- status
  [id]
  (:status (first (filter #(= id (:id %)) (mock/peek-state :aggregate-store)))))

(deftest effect-to-own-service-is-delivered-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service)

          poller
          (consumer svc)

          id
          (uuid/gen)

          response
          (command! svc :start id)]

      (testing "the command is answered before anything is delivered"
        (is
         (= {:success true
             :events 1}
            (select-keys (:result response) [:success :events])))

        (is
         (= 1
            (count (markers svc))))

        (is
         (true?
          (sut/pending? svc :test))))

      (testing "the marker delivers the apply and the effect, whose own marker delivers its apply"
        (is
         (= 2
            (deliver! svc poller)))

        (is
         (= 1
            (deliver! svc poller)))

        (is
         (= 0
            (deliver! svc poller))))

      (is
       (= [:started :finished]
          (event-ids svc id)))

      (is
       (= :finished
          (status id)))

      (is
       (false?
        (sut/pending? svc :test)))

      (is
       (= []
          (markers svc))))))

(deftest marker-without-wake-is-delivered-by-next-poll-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service)

          id
          (uuid/gen)]
      (binding [request/*request* (atom {})]
        (edd/handler (assoc svc :invocation-id (uuid/gen))
                     {:request-id (uuid/gen)
                      :interaction-id (uuid/gen)
                      :meta {:realm :test}
                      :commands [{:cmd-id :finish
                                  :id id}]}))

      (is
       (= 1
          (count (markers svc))))

      (is
       (= 1
          (deliver! svc (consumer svc))))

      (is
       (= :finished
          (status id)))

      (is
       (= []
          (markers svc))))))

(deftest failing-effect-keeps-marker-until-fixed-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service)

          poller
          (consumer svc)

          id
          (uuid/gen)]
      (try
        (reset! finish-fails? true)
        (command! svc :start id)

        (testing "the apply is delivered, the failing effect keeps the marker"
          (is
           (= 1
              (deliver! svc poller)))

          (is
           (= [{:attempts 1
                :last-error "Delivery failed: commands [0 0]"
                :failed-at nil}]
              (mapv #(select-keys % [:attempts :last-error :failed-at]) (markers svc)))))

        (testing "it is not polled before its next attempt is due"
          (is
           (= 0
              (deliver! svc poller))))

        (finally
          (reset! finish-fails? false)))

      (testing "delivered once the cause is fixed"
        (make-due! svc)

        (is
         (= 2
            (deliver! svc poller)))

        (is
         (= 1
            (deliver! svc poller)))

        (is
         (= [:started :finished]
            (event-ids svc id)))

        (is
         (= []
            (markers svc)))))))

(deftest marker-is-given-up-after-max-attempts-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service :max-attempts 2)

          poller
          (consumer svc)]
      (try
        (reset! finish-fails? true)
        (command! svc :start (uuid/gen))
        (deliver! svc poller)
        (make-due! svc)
        (deliver! svc poller)

        (is
         (= [[2 true]]
            (mapv (juxt :attempts (comp some? :failed-at)) (markers svc))))

        (testing "a failed marker is kept but no longer polled"
          (make-due! svc)

          (is
           (= 0
              (deliver! svc poller)))

          (is
           (= 1
              (count (markers svc)))))

        (finally
          (reset! finish-fails? false))))))

(deftest two-pollers-claim-disjoint-markers-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service :batch-size 2)

          first-poller
          (consumer svc)

          second-poller
          (consumer svc)

          _
          (dotimes [_ 4]
            (command! svc :finish (uuid/gen)))

          polled
          (vec (for [_ (range 2)
                     poller [first-poller second-poller]]
                 (mapv #(get-in % [:record :messageId]) ((:poll poller)))))]

      (is
       (= [1 1 1 1]
          (mapv count polled)))

      (is
       (= 4
          (count (distinct (flatten polled))))))))

(deftest rolled-back-command-leaves-no-marker-test
  (binding [*dal-state* (atom {})]
    (let [svc
          (service)

          id
          (uuid/gen)

          response
          (command! svc :start-remote id)]

      (is
       (re-find #"delivers to .* only"
                (str response)))

      (is
       (= []
          (event-ids svc id)))

      (is
       (= []
          (markers svc))))))
