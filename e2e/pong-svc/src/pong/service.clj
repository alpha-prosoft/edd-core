(ns pong.service
  "pong-svc domain, filters and deps resolver, shared by the AWS (pong.main)
   and local (pong.local) mains."
  (:require [clojure.tools.logging :as log]
            [aws.cognito :as cognito]
            [aws.ctx :as aws-ctx]
            [edd.client.deps :as deps]
            [edd.common :as common]
            [edd.core :as edd]
            [edd.security :as security]
            [aws.filters :as aws-filters]))

(def max-hops 5)

;; E2E only: no JWT/Cognito, the user comes straight from the request body
;; (realm from its meta). Without one the request runs as m2m traffic and
;; keeps its incoming meta.
(defn- request-user
  [{:keys [body]}]
  (if-let [user (:user body)]
    (assoc user :realm (get-in body [:meta :realm]))
    {:id "e2e" :role :non-interactive}))

;; Effects arrive through the queue from the router; they run as the service's
;; :non-interactive user regardless of who triggered the originating command.
;; The request meta is stamped since handle-commands merges it over ctx meta.
(defn non-interactive-queue
  [ctx request chain]
  (chain ctx (if (:from-api ctx)
               request
               (assoc-in request [:meta :user] {:id (name (:service-name ctx))
                                                :role :non-interactive}))))

(defn current-dep
  [_deps cmd]
  {:query-id :get-by-id
   :id (:id cmd)})

(defn cmd-pong
  [ctx cmd]
  (let [hops (long (get-in cmd [:attrs :hops] 0))
        previous (get-in ctx [:current :hops] -1)]
    (log/info "PONG received, hops:" hops "previous:" previous)
    {:event-id :ponged
     :id (:id cmd)
     :attrs {:hops hops}}))

(defn evt-ponged
  [agg event]
  (-> agg
      (assoc :last :ponged)
      (assoc :hops (get-in event [:attrs :hops]))
      (update :pong-count (fnil inc 0))))

(defn cmd-set-value
  [_ctx cmd]
  {:event-id :value-set
   :id (:id cmd)
   :attrs {:value (get-in cmd [:attrs :value])}})

(defn evt-value-set
  [agg event]
  (assoc agg :value (get-in event [:attrs :value])))

;; Remote (cross-service) dependency: synchronously queries ping-svc over the API.
(def ping-value-dep
  {:service :ping-svc
   :query (fn [_deps cmd]
            {:query-id :get-by-id
             :id (get-in cmd [:attrs :ping-id])})})

(defn cmd-combine
  [ctx cmd]
  (let [ping-agg (:ping ctx)]
    {:event-id :combined
     :id (:id cmd)
     :attrs {:ping-value (:value ping-agg)
             :ping-version (:version ping-agg)
             :pong-value (get-in cmd [:attrs :value])}}))

(defn evt-combined
  [agg event]
  (-> agg
      (assoc :ping-value (get-in event [:attrs :ping-value]))
      (assoc :ping-version (get-in event [:attrs :ping-version]))
      (assoc :pong-value (get-in event [:attrs :pong-value]))))

(defn fx-ponged
  [_ctx event]
  (let [hops (long (get-in event [:attrs :hops] 0))]
    (when (< hops max-hops)
      {:service :ping-svc
       :commands [{:cmd-id :ping
                   :id (:id event)
                   :attrs {:hops (inc hops)}}]})))

(defn cmd-fan-out
  [_ctx cmd]
  {:event-id :fanned-out
   :id (:id cmd)
   :attrs (:attrs cmd)})

(defn evt-fanned-out
  [agg event]
  (assoc agg :fanned-out (count (get-in event [:attrs :targets]))))

(defn fx-fanned-out
  [_ctx event]
  (for [target (get-in event [:attrs :targets])]
    {:service :ping-svc
     :commands [{:cmd-id :set-value
                 :id target
                 :attrs {:value (get-in event [:attrs :value])}}]}))

(defn query-summary
  [ctx _query]
  (let [agg (:agg ctx)]
    {:id (:id agg)
     :hops (:hops agg)
     :pong-count (:pong-count agg)
     :last (:last agg)}))

(defn summary-dep
  [_deps query]
  {:query-id :get-by-id
   :id (:id query)})

(defn register [ctx]
  (-> ctx
      (assoc :service-name :pong-svc)
      ;; effect-only: :pong is reachable only through the ping->pong effect
      ;; chain, so the loop completing proves effects run as :non-interactive
      (edd/reg-cmd :pong cmd-pong
                   :deps [:current current-dep]
                   :auth {:role :non-interactive})
      (edd/reg-cmd :combine cmd-combine
                   :deps [:ping ping-value-dep]
                   :auth {:role :e2e-tester})
      (edd/reg-cmd :set-value cmd-set-value
                   :auth {:role [:e2e-tester :non-interactive]})
      (edd/reg-cmd :fan-out cmd-fan-out
                   :auth {:role :e2e-tester})
      (edd/reg-event :fanned-out evt-fanned-out)
      (edd/reg-event-fx :fanned-out fx-fanned-out)
      (edd/reg-event :ponged evt-ponged)
      (edd/reg-event :combined evt-combined)
      (edd/reg-event :value-set evt-value-set)
      (edd/reg-event-fx :ponged fx-ponged)
      (edd/reg-query :get-by-id common/get-by-id
                     :auth {:role [:e2e-tester :non-interactive]})
      (edd/reg-query :summary query-summary
                     :deps [:agg summary-dep]
                     :auth {:role :e2e-tester})
      (security/register)))

(defn init
  [ctx]
  (let [with-auth
        (edd/reg-feature (aws-ctx/register ctx) :e2e/public-auth
                         :deps {}
                         :resolve-user request-user)]
    (register (deps/register with-auth {:credentials (cognito/credentials)}))))

(def request-filters
  [aws-filters/from-bucket
   non-interactive-queue])
