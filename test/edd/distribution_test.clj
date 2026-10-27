(ns edd.distribution-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.distribution :as sut]
            [edd.distribution.memory :as memory]
            [edd.distribution.response :as response]
            [edd.distribution.router :as router]
            [edd.test.fixture.dal :as mock]
            [lambda.runtime :as runtime]
            [edd.core]
            [lambda.request :as request]
            [lambda.util :as util]))

(def ctx
  (memory/register {:service-name :order-svc
                    :environment-name-lower "dev"}
                   (atom {})))

(defn- succeed-all
  [_ctx _destination messages]
  (mapv #(hash-map :id (:id %) :success true) messages))

(defn- scripted
  "Test transport whose behaviour comes from functions in behaviour:
   :publish (fn [ctx destination messages]), :consumer (fn [ctx source]),
   :poll (fn []), :ack/:retry (fn [message]), :dead-letter."
  ([behaviour]
   (scripted {} behaviour))
  ([ctx {:keys [publish consumer poll ack retry dead-letter]
         :or {publish succeed-all
              poll (fn [] (Thread/sleep 10) [])
              ack (fn [_])
              retry (fn [_])}}]
   (apply sut/register-transport ctx :scripted
          :publish-filter (fn [ctx {:keys [destination messages]} _chain]
                            (publish ctx destination messages))
          :consumer (fn [ctx source]
                      (when consumer
                        (consumer ctx source))
                      {:poll poll :ack ack :retry retry :close (fn [])})
          (when dead-letter
            [:dead-letter dead-letter]))))

(defn- wait-for
  [pred]
  (loop [attempts 300]
    (cond
      (pred) true
      (zero? attempts) false
      :else (do
              (Thread/sleep 10)
              (recur (dec attempts))))))

(deftest topology-test
  (is
   (= "router"
      (sut/response-destination ctx)))

  (is
   (= ["order-svc.prod.commands"
       "order-svc.prod.events"
       "order-svc.test.commands"
       "order-svc.test.events"]
      (sut/service-sources ctx)))

  (is
   (= ["test" "prod" "uat"]
      (map sut/stage [{:realm :test} {:realm :prod} {:stage "uat"}]))))

(deftest consume-sources-test
  (testing "service sources by default"
    (is
     (= 4
        (count (sut/consume-sources ctx)))))

  (testing "explicit configuration wins"
    (is
     (= ["custom"]
        (sut/consume-sources (assoc ctx :edd/consume ["custom"])))))

  (testing "an empty list disables consuming"
    (is
     (= []
        (sut/consume-sources (assoc ctx :edd/consume []))))))

(deftest missing-distributor-test
  (is
   (thrown-with-msg?
    clojure.lang.ExceptionInfo
    #"No distributor registered for :order-svc"
    (sut/validate! {:service-name :order-svc})))

  (testing "a later transport replaces the one registered before"
    (is
     (= :scripted
        (:feature-id (sut/transport (scripted ctx {})))))))

(deftest publish-filters-wrap-the-transport-test
  (let [seen
        (atom [])

        wrapped
        (edd.core/reg-feature (scripted {})
                              :tagging
                              :publish-filter (fn [ctx request chain]
                                                (swap! seen conj (:destination request))
                                                (chain ctx (update request :messages
                                                                   (fn [messages]
                                                                     (mapv #(assoc % :id (str "tagged-" (:id %))) messages))))))]
    (is
     (= [{:id "tagged-1" :success true}]
        (sut/publish! wrapped "d" [{:id "1" :body "{}"}])))

    (is
     (= ["d"]
        @seen))))

(deftest distribute-response-test
  (binding [request/*request* (atom {:router-payloads ["{\"service\":\":order-svc\",\"resp\":{\"events\":[],\"effects\":[]}}"
                                                       "{\"n\":2}"]})]
    (sut/distribute-response! ctx)

    (is
     (= [{:Records [{:service :order-svc :resp {:events [] :effects []}}]}
         {:Records [{:n 2}]}]
        (memory/bodies ctx "router")))))

(deftest payloads-fit-the-transport-test
  (let [small-ctx
        (sut/register-transport {} :small
                                :consumer (fn [_ _])
                                :max-message-size 200)

        effect
        (fn [index]
          {:service :pong-svc :commands [{:cmd-id :pong :n index}]})

        payloads
        (response/payloads small-ctx :ping-svc {:events [{:id 1 :event-id :pinged}]
                                                :effects (map effect (range 6))})

        parsed
        (map util/to-edn payloads)]

    (testing "every message fits"
      (is
       (every? #(<= (+ (count "{\"Records\":[]}") (sut/byte-size %)) 200) payloads)))

    (testing "split into several messages"
      (is
       (< 1 (count payloads))))

    (testing "events travel first with only what the router reads, effects keep their order"
      (is
       (= [{:id 1}]
          (get-in (first parsed) [:resp :events])))

      (is
       (= (range 6)
          (map #(get-in % [:commands 0 :n]) (mapcat #(get-in % [:resp :effects]) parsed)))))

    (testing "nothing to send, no payload"
      (is
       (= []
          (response/payloads small-ctx :ping-svc {:events [] :effects []}))))))

(deftest duplicate-request-replays-stored-response-test
  (mock/with-mock-dal
    (let [ctx
          (edd.core/reg-cmd (assoc mock/ctx :service-name :ping-svc)
                            :ping (fn [_ cmd]
                                    {:event-id :pinged :id (:id cmd)})
                            :consumes [:map])

          request
          {:request-id #uuid "33333333-3333-3333-3333-333333333333"
           :interaction-id #uuid "44444444-4444-4444-4444-444444444444"
           :commands [{:cmd-id :ping :id #uuid "55555555-5555-5555-5555-555555555555"}]}

          payloads-of
          (fn []
            (binding [request/*request* (atom {})]
              (mock/handle-cmd ctx request)
              (mapv util/to-edn (:router-payloads @request/*request*))))

          first-run
          (payloads-of)

          replay
          (payloads-of)]
      (is
       (= 1
          (count (get-in (first first-run) [:resp :events]))))

      (is
       (= (map #(get-in % [:resp :events 0 :id]) first-run)
          (map #(get-in % [:resp :events 0 :id]) replay))))))

(deftest router-fans-out-inline-response-test
  (let [routing-ctx
        (memory/register {:environment-name-lower "dev"} (atom {}))

        effect
        {:service :pong-svc
         :commands [{:cmd-id :pong :id #uuid "11111111-1111-1111-1111-111111111111"}]
         :meta {:realm :test}}

        event
        {:id #uuid "22222222-2222-2222-2222-222222222222"
         :event-id :pinged
         :meta {:realm :prod}}]
    (sut/distribute! routing-ctx
                     (sut/response-destination routing-ctx)
                     [{:id "0"
                       :body (util/to-json {:Records [{:service :ping-svc
                                                       :resp {:effects [effect]
                                                              :events [event]}}]})}])

    (is
     (= [{:success true}]
        (memory/route! routing-ctx)))

    (testing "effects go to the target service commands destination of the realm stage"
      (is
       (= [effect]
          (memory/bodies routing-ctx "pong-svc.test.commands"))))

    (testing "events go to the producing service events destination"
      (is
       (= [{:apply {:aggregate-id (:id event)
                    :service :ping-svc}
            :meta {:realm :prod}
            :request-id nil
            :interaction-id nil}]
          (memory/bodies routing-ctx "ping-svc.prod.events"))))

    (testing "messages are grouped per aggregate"
      (is
       (= (get-in effect [:commands 0 :id])
          (:group-id (first (memory/messages routing-ctx "pong-svc.test.commands"))))))

    (is
     (empty?
      (memory/messages routing-ctx (sut/response-destination routing-ctx))))))

(deftest undelivered-messages-test
  (let [failing
        {:publish (fn [_ctx destination messages]
                    (mapv (fn [{:keys [id]}]
                            {:id id
                             :success (not= destination "pong-svc.test.commands")})
                          messages))}

        effect
        {:service :pong-svc
         :commands [{:cmd-id :pong :id #uuid "11111111-1111-1111-1111-111111111111"}]
         :meta {:realm :test}}]
    (is
     (= {:effects [effect]}
        (router/route (scripted {:environment-name-lower "dev"} failing)
                      {:service :ping-svc
                       :resp {:effects [effect]
                              :events [{:id #uuid "22222222-2222-2222-2222-222222222222"}]}})))))

(deftest consumer-acks-and-retries-test
  (sut/distribute! ctx "work" [{:id "ok" :body "{\"ok\":true}"}
                               {:id "fail" :body "{\"ok\":false}"}])
  (let [attempts
        (atom {})

        stop
        (sut/start-consumer! ctx
                             "work"
                             (fn [{:keys [messageId]}]
                               (let [tries (get (swap! attempts update messageId (fnil inc 0)) messageId)]
                                 (or (= "ok" messageId) (> tries 1))))
                             :retry-delay-ms 10)]
    (is
     (wait-for #(empty? (memory/messages ctx "work"))))

    (stop)

    (is
     (= {"ok" 1 "fail" 2}
        @attempts))))

(deftest fixture-registers-memory-distributor-test
  (mock/with-mock-dal
    (sut/distribute! mock/ctx "fixture-destination" [{:id "1" :body "{}"}])

    (is
     (= {"fixture-destination" [{}]}
        (memory/published mock/ctx))))

  (testing "every with-mock-dal starts empty"
    (mock/with-mock-dal
      (is
       (= {}
          (memory/published mock/ctx))))))

(deftest failed-batch-message-stops-the-batch-test
  (let [acked
        (atom [])

        retried
        (atom [])

        batch
        {:poll (fn []
                 [{:record {:messageId "1"}} {:record {:messageId "2"}} {:record {:messageId "3"}}])
         :ack (fn [message]
                (swap! acked conj (get-in message [:record :messageId])))
         :retry (fn [message]
                  (swap! retried conj (get-in message [:record :messageId])))}

        stop
        (sut/start-consumer! (scripted batch)
                             "source"
                             (fn [{:keys [messageId]}]
                               (when (= "2" messageId)
                                 (throw (StackOverflowError.)))
                               true)
                             :retry-delay-ms 10)]
    (is
     (wait-for #(>= (count @retried) 2)))

    (stop)

    (testing "records after a failure are never acknowledged past it"
      (is
       (= #{"1"}
          (set @acked))))

    (testing "a throwing handler, even an Error, is retried with what follows it and the consumer keeps running"
      (is
       (every? #{"2" "3"} @retried))

      (is
       (contains? (set @retried) "2")))))

(deftest unreported-messages-count-as-failed-test
  (is
   (= #{"b" "c"}
      (sut/failed-ids [{:id "a"} {:id "b"} {:id "c"}]
                      [{:id "a" :success true} {:id "b" :success false}]))))

(deftest partial-failure-requeues-remainder-test
  (let [delivered-once
        (atom #{})

        broker
        (atom {})

        memory-ctx
        (memory/register {:environment-name-lower "dev"} broker)

        routing-ctx
        (scripted {:environment-name-lower "dev"}
                  {:publish (fn [_ctx destination messages]
                              (if (and (= destination "pong-svc.test.commands")
                                       (not (contains? @delivered-once destination)))
                                (do
                                  (swap! delivered-once conj destination)
                                  (mapv #(hash-map :id (:id %) :success false) messages))
                                (sut/publish! memory-ctx destination messages)))})

        ping-effect
        {:service :ping-svc :commands [{:cmd-id :ping :id #uuid "11111111-1111-1111-1111-111111111111"}] :meta {:realm :test}}

        pong-effect
        {:service :pong-svc :commands [{:cmd-id :pong :id #uuid "22222222-2222-2222-2222-222222222222"}] :meta {:realm :test}}]
    (is
     (= {:success true}
        (router/handle-record routing-ctx {:service :ping-svc
                                           :resp {:effects [ping-effect pong-effect]}})))

    (testing "the delivered effect is not sent again"
      (is
       (= [ping-effect]
          (mapv #(util/to-edn (:body %))
                (get-in @broker [:distribution "ping-svc.test.commands"])))))

    (testing "only the undelivered effect goes back to the router"
      (is
       (= [{:Records [{:service :ping-svc
                       :resp {:events [] :effects [pong-effect]}}]}]
          (mapv #(util/to-edn (:body %))
                (get-in @broker [:distribution "router"])))))))

(deftest transport-unwraps-bodies-test
  (let [unwrapping
        (sut/register-transport {} :unwrapping
                                :consumer (fn [_ _])
                                :unwrap (fn [_ctx body]
                                          (if (:parked body)
                                            {:commands [{:cmd-id :big}]}
                                            body)))]
    (is
     (= [{:commands [{:cmd-id :big}]}]
        (runtime/handle-records (runtime/init unwrapping (fn [_ctx request] request) {})
                                [{:eventSource "edd:kafka"
                                  :body "{\"parked\":true}"}])))

    (is
     (= {:a 1}
        (sut/unwrap ctx {:a 1})))))

(deftest consumer-creation-is-retried-test
  (let [attempts
        (atom 0)

        flaky
        {:consumer (fn [_ _source]
                     (when (= 1 (swap! attempts inc))
                       (throw (ex-info "broker down" {}))))}

        stop
        (sut/start-consumer! (scripted flaky) "source" (constantly true) :retry-delay-ms 10)]
    (is
     (wait-for #(>= @attempts 2)))

    (stop)))

(deftest router-consumes-response-destination-test
  (is
   (= ["router"]
      (sut/consume-sources (assoc ctx :edd/router true)))))

(deftest consumer-thread-sees-fixture-broker-test
  (mock/with-mock-dal
    (sut/distribute! mock/ctx "fixture-source" [{:id "1" :body "{}"}])
    (let [handled
          (atom [])

          stop
          (sut/start-consumer! mock/ctx "fixture-source" #(swap! handled conj %))]
      (is
       (wait-for #(seq @handled)))

      (stop)

      (is
       (empty?
        (memory/messages mock/ctx "fixture-source"))))))

(deftest failure-rewinds-every-unprocessed-message-test
  (let [retried
        (atom [])

        batch
        {:poll (fn []
                 [{:record {:messageId "a5"}} {:record {:messageId "b9"}} {:record {:messageId "a6"}}])
         :retry (fn [message]
                  (swap! retried conj (get-in message [:record :messageId])))}

        stop
        (sut/start-consumer! (scripted batch)
                             "source"
                             (constantly false)
                             :retry-delay-ms 50)]
    (is
     (wait-for #(>= (count @retried) 3)))

    (stop)

    (is
     (= ["a6" "b9" "a5"]
        (take 3 @retried)))))

(deftest same-group-keeps-order-test
  (let [published
        (atom [])

        recording
        {:publish (fn [_ctx destination messages]
                    (Thread/sleep (long (rand-int 5)))
                    (swap! published into (map #(vector destination (:group-id %) (:id %)) messages))
                    (mapv #(hash-map :id (:id %) :success true) messages))}

        messages
        (for [index (range 35)]
          {:id (str index)
           :destination "d"
           :group-id (if (even? index) "a" "b")
           :body "{}"})]
    (router/publish-all! (scripted recording) messages)

    (doseq [group ["a" "b"]]
      (is
       (= (map :id (filter #(= group (:group-id %)) messages))
          (map last (filter #(= group (second %)) @published)))))))

(deftest poison-message-goes-to-dead-letters-test
  (let [dead-letter-ctx
        (memory/register {:service-name :order-svc} (atom {}))

        _
        (sut/distribute! dead-letter-ctx "poison-source" [{:id "bad" :body "{\"bad\":true}"}
                                                          {:id "good" :body "{}"}])

        handled
        (atom [])

        stop
        (sut/start-consumer! dead-letter-ctx
                             "poison-source"
                             (fn [{:keys [messageId] :as record}]
                               (swap! handled conj messageId)
                               (not= "bad" messageId))
                             :retry-delay-ms 5
                             :max-attempts 3)]
    (is
     (wait-for #(empty? (memory/messages dead-letter-ctx "poison-source"))))

    (stop)

    (is
     (= ["bad" "bad" "bad" "good"]
        @handled))

    (is
     (= [{:bad true}]
        (memory/bodies dead-letter-ctx "poison-source.dlq")))))

(deftest drain-keeps-message-when-handler-throws-test
  (let [drain-ctx
        (memory/register {} (atom {}))]
    (sut/distribute! drain-ctx "work" [{:id "1" :body "{}"}])

    (is
     (thrown? clojure.lang.ExceptionInfo
              (memory/drain! drain-ctx "work" (fn [_] (throw (ex-info "boom" {}))))))

    (is
     (= 1
        (count (memory/messages drain-ctx "work"))))))

(deftest failed-dead-letter-move-retries-message-test
  (let [retried
        (atom 0)

        failing-dead-letters
        {:poll (fn [] [{:record {:messageId "poison"}}])
         :ack (fn [_] (throw (ex-info "must not ack" {})))
         :retry (fn [_] (swap! retried inc))
         :dead-letter (fn [_ _ _] (throw (ex-info "dlq down" {})))}

        stop
        (sut/start-consumer! (scripted failing-dead-letters) "source" (constantly false)
                             :retry-delay-ms 5
                             :max-attempts 2)]
    (is
     (wait-for #(>= @retried 3)))

    (stop)))

(deftest unrecognized-router-record-test
  (is
   (thrown-with-msg?
    clojure.lang.ExceptionInfo
    #"Unrecognized router record"
    (router/handle-record ctx {:unknown true}))))

(deftest failed-chunk-holds-back-rest-of-lane-test
  (let [published
        (atom [])

        first-chunk-fails
        {:publish (fn [_ctx _destination messages]
                    (if (some #(= "0" (:id %)) messages)
                      (mapv #(hash-map :id (:id %) :success false) messages)
                      (do
                        (swap! published into (map :id messages))
                        (mapv #(hash-map :id (:id %) :success true) messages))))}

        results
        (router/publish-all! (scripted first-chunk-fails)
                             (for [index (range 15)]
                               {:id (str index) :destination "d" :group-id "a" :body "{}"}))]
    (is
     (empty? @published))

    (is
     (= 15
        (count (remove :success results))))))

