(ns mono-svc.chain
  (:require [edd.common :as common]
            [edd.core :as edd]))

(defn cmd-start-chain
  [_ctx cmd]
  {:event-id :chain-started
   :id (:id cmd)
   :attrs {:steps (get-in cmd [:attrs :steps])}})

(defn evt-chain-started
  [agg event]
  (assoc agg
         :step 0
         :steps (get-in event [:attrs :steps])))

(defn- advance
  [id step steps]
  (when (<= step steps)
    {:cmd-id :advance
     :id id
     :attrs {:step step
             :steps steps}}))

(defn fx-chain-started
  [_ctx event]
  (advance (:id event) 1 (get-in event [:attrs :steps])))

(defn cmd-advance
  [_ctx cmd]
  {:event-id :chain-advanced
   :id (:id cmd)
   :attrs (:attrs cmd)})

(defn evt-chain-advanced
  [agg event]
  (assoc agg :step (get-in event [:attrs :step])))

(defn fx-chain-advanced
  [_ctx event]
  (let [{:keys [step steps]}
        (:attrs event)]
    (advance (:id event) (inc step) steps)))

(defn cmd-call-remote
  [_ctx cmd]
  {:event-id :remote-called
   :id (:id cmd)})

(defn evt-remote-called
  [agg _event]
  (assoc agg :remote :called))

(defn fx-remote-called
  [_ctx event]
  {:service :pong-svc
   :commands [{:cmd-id :set-value
               :id (:id event)
               :attrs {:value "unreachable"}}]})

(defn register
  [ctx]
  (-> ctx
      (edd/reg-cmd :start-chain cmd-start-chain
                   :auth {:role :e2e-tester})
      (edd/reg-cmd :advance cmd-advance
                   :auth {:role :non-interactive})
      (edd/reg-cmd :call-remote cmd-call-remote
                   :auth {:role :e2e-tester})
      (edd/reg-event :chain-started evt-chain-started)
      (edd/reg-event :chain-advanced evt-chain-advanced)
      (edd/reg-event :remote-called evt-remote-called)
      (edd/reg-event-fx :chain-started fx-chain-started)
      (edd/reg-event-fx :chain-advanced fx-chain-advanced)
      (edd/reg-event-fx :remote-called fx-remote-called)
      (edd/reg-query :get-by-id common/get-by-id
                     :auth {:role [:e2e-tester :non-interactive]})))
