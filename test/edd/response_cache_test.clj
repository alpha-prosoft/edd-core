(ns edd.response-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.dal :as dal]
            [edd.distribution.memory :as memory-distribution]
            [edd.el.ctx :as el-ctx]
            [edd.response.cache :as cache]
            [edd.test.fixture.dal :as mock]
            [edd.distribution.response :as response]
            [edd.test.fixture.runtime :as runtime]
            [lambda.request :as request]
            [lambda.util :as util]
            [lambda.uuid :as uuid]))

(defn- memory-cache
  [stored]
  {:store      (fn [ctx {:keys [idx] :as part}]
                 (let [reference
                       {:cached [(:request-id ctx) (:breadcrumbs ctx) idx]}]
                   (swap! stored assoc reference (dissoc part :idx))
                   reference))
   :reference? #(contains? % :cached)
   :fetch      (fn [_ctx reference]
                 (get @stored reference))})

(defn- service
  "A service fanning a command out to its targets, 3 effects per cached
   part, optionally with a response cache and a transport message size."
  [stored & {:keys [max-message-size]}]
  (let [sized
        (if max-message-size
          (memory-distribution/register mock/ctx nil {:max-message-size max-message-size})
          mock/ctx)

        ctx
        (el-ctx/set-effect-partition-size (assoc sized :service-name :fan-svc) 3)

        with-cmds
        (edd/reg-cmd (edd/reg-cmd ctx :fan-out
                                  (fn [_ctx cmd]
                                    {:event-id :fanned-out
                                     :id       (:id cmd)
                                     :targets  (:targets cmd)}))
                     :receive
                     (fn [_ctx cmd]
                       {:event-id :received
                        :id       (:id cmd)}))

        with-fx
        (edd/reg-event-fx with-cmds :fanned-out
                          (fn [_ctx event]
                            (for [target (:targets event)]
                              {:service  :fan-svc
                               :commands [{:cmd-id :receive
                                           :id     target}]})))]
    (if stored
      (cache/register with-fx (memory-cache stored))
      with-fx)))

(defn- fan-out-request
  [targets]
  {:request-id     (uuid/gen)
   :interaction-id (uuid/gen)
   :commands       [{:cmd-id  :fan-out
                     :id      (uuid/gen)
                     :targets targets}]})

(defn- router-records
  "Records waiting for the router."
  [ctx]
  (map #(get-in % [:Records 0])
       (memory-distribution/bodies ctx "router")))

(defn- routed-targets
  "Routes what reached the router and returns the ids of the :receive
   commands delivered to the service."
  [ctx]
  (memory-distribution/route! ctx)
  (set (map #(get-in % [:commands 0 :id])
            (memory-distribution/bodies ctx "fan-svc.test.commands"))))

(deftest response-is-cached-in-parts-test
  (mock/with-mock-dal
    (let [stored
          (atom {})

          ctx
          (runtime/start (service stored))

          targets
          (vec (repeatedly 7 uuid/gen))

          request
          (fan-out-request targets)

          response
          (runtime/post ctx request)]
      (testing "the client does not see the cache"
        (is
         (= {:success true :events 1 :identities 0}
            (select-keys (get-in response [:body :result]) [:success :events :identities :cache-result]))))

      (testing "3 effects per part, the events travel with the first part"
        (is
         (= [[3 1] [3 0] [1 0]]
            (map (fn [[_reference part]]
                   [(count (get-in part [:resp :effects]))
                    (count (get-in part [:resp :events]))])
                 (sort-by #(get-in (key %) [:cached 2]) @stored)))))

      (testing "the router gets one reference per part"
        (is
         (= (set (keys @stored))
            (set (router-records ctx)))))

      (testing "the router resolves the references and delivers every effect"
        (is
         (= (set targets)
            (routed-targets ctx))))

      (testing "a duplicate request sends the stored references again without reading the command store"
        (with-redefs [dal/get-response-effects (fn [_ctx]
                                                 (throw (ex-info "must not rebuild" {})))]
          (is
           (= 200
              (:status (runtime/post ctx request)))))

        (is
         (= (set (keys @stored))
            (set (router-records ctx))))

        (is
         (= 3
            (count @stored)))))))

(deftest small-response-skips-the-cache-test
  (mock/with-mock-dal
    (let [stored
          (atom {})

          ctx
          (runtime/start (service stored))

          targets
          [(uuid/gen)]]
      (runtime/post ctx (fan-out-request targets))

      (is
       (= {}
          @stored))

      (is
       (= (set targets)
          (routed-targets ctx))))))

(deftest response-over-the-message-size-is-cached-test
  (mock/with-mock-dal
    (let [stored
          (atom {})

          ctx
          (runtime/start (service stored :max-message-size 300))

          targets
          [(uuid/gen)]]
      (runtime/post ctx (fan-out-request targets))

      (is
       (= 1
          (count @stored)))

      (is
       (= (set targets)
          (routed-targets ctx))))))

(deftest without-cache-the-response-travels-inline-test
  (mock/with-mock-dal
    (let [ctx
          (runtime/start (service nil))

          targets
          (vec (repeatedly 7 uuid/gen))

          request
          (fan-out-request targets)]
      (runtime/post ctx request)

      (is
       (= [7]
          (map #(count (get-in % [:resp :effects])) (router-records ctx))))

      (is
       (= (set targets)
          (routed-targets ctx)))

      (testing "a duplicate request rebuilds the payloads from the command store"
        (runtime/post ctx request)

        (is
         (= [7]
            (map #(count (get-in % [:resp :effects])) (router-records ctx))))))))

(deftest stored-references-are-sent-again-test
  (let [reference
        {:s3 {:bucket {:name "bucket"} :object {:key "response/key.json"}}}

        queued
        (fn [summary]
          (binding [request/*request* (atom {})]
            (response/queue-stored-response! mock/ctx summary)
            (mapv util/to-edn (:router-payloads @request/*request*))))]
    (testing "a response stored before 2.340 keeps one reference as a map"
      (is
       (= [reference]
          (queued {:cache-result reference}))))

    (testing "references of a response stored in parts"
      (is
       (= [reference reference]
          (queued {:cache-result [reference reference]}))))))

(deftest failed-part-fails-the-command-test
  (mock/with-mock-dal
    (let [stored
          (atom {})

          service-ctx
          (service stored)

          failing
          (cache/register service-ctx
                          (update (memory-cache stored)
                                  :store
                                  (fn [store]
                                    (fn [ctx part]
                                      (when (= 1 (:idx part))
                                        (throw (ex-info "S3 down" {:error {:status 503}})))
                                      (store ctx part)))))

          ctx
          (runtime/start failing)

          response
          (runtime/post ctx (fan-out-request (vec (repeatedly 7 uuid/gen))))]
      (testing "the request fails"
        (is
         (= 500
            (:status response))))

      (testing "nothing is stored or sent to the router"
        (mock/verify-state :event-store [])

        (mock/verify-state :command-store [])

        (is
         (= []
            (router-records ctx))))

      (testing "the part stored before the failure stays in the cache"
        (is
         (= 1
            (count @stored)))))))

(deftest single-effect-over-the-message-size-is-cached-test
  (let [stored
        (atom {})

        sized
        (memory-distribution/register (assoc mock/ctx :service-name :fan-svc) nil {:max-message-size 200})

        ctx
        (cache/register sized (memory-cache stored))

        effect
        {:service  :fan-svc
         :commands [{:cmd-id :receive
                     :id     (uuid/gen)
                     :attrs  {:text (apply str (repeat 300 "x"))}}]}

        prepared
        (response/prepare ctx {:events [] :effects [effect]})]
    (is
     (= 1
        (count (:cache-result prepared))))

    (is
     (= [[effect]]
        (map #(get-in % [:resp :effects]) (vals @stored))))))

