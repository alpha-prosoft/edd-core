(ns edd.memory.event-store
  (:require
   [clojure.tools.logging :as log]
   [lambda.test.fixture.state :as state]
   [edd.dal :refer [with-init
                    get-events
                    get-max-event-seq
                    get-sequence-number-for-id
                    get-id-for-sequence-number
                    get-aggregate-id-by-identity
                    get-command-response
                    log-dps
                    log-request
                    log-request-error
                    log-response
                    store-results]]
   [lambda.util :as util])
  (:import (java.util Collection ArrayList Random Collections)
           (clojure.lang RT)))

(defn fix-keys
  [val]
  (-> val
      (util/to-json)
      (util/to-edn)))

(def default-db
  {:event-store    []
   :identity-store []
   :sequence-store []
   :command-store  []
   :response-log   []
   :command-log    []})

(def ^:dynamic *dal-state* (atom (assoc default-db
                                        :global true)))

(defn get-db
  [ctx]
  *dal-state*)

(defn store-sequence
  "Stores sequence in memory structure.
  Raises RuntimeException if sequence is already taken"
  [ctx {:keys [id]}]
  {:pre [id]}
  (log/info "Emulated 'store-sequence' dal function")
  (let [store (:sequence-store @(get-db ctx))
        sequence-already-exists (some
                                 #(= (:id %) id)
                                 store)
        max-number (count store)]
    (if sequence-already-exists
      (throw (RuntimeException. "Sequence already exists")))
    (swap! (get-db ctx)
           #(update % :sequence-store (fn [v] (conj v {:id    id
                                                       :value (inc max-number)}))))))

(defn store-identity
  "Stores identity in memory structure.
  Raises RuntimeException if identity is already taken"
  [ctx identity]
  (log/info "Emulated 'store-identity' dal function")
  (let [id-fn (juxt :id :identity)
        id (id-fn identity)
        store (:identity-store @(get-db ctx))
        id-already-exists (some #(= (id-fn %) id) store)]
    (when id-already-exists
      (throw (RuntimeException. "Identity already exists")))
    (swap! (get-db ctx)
           #(update % :identity-store (fn [v] (conj v (dissoc identity
                                                              :request-id
                                                              :interaction-id
                                                              :meta)))))))

(defn deterministic-shuffle
  [^Collection coll seed]
  (let [al (ArrayList. coll)
        rng (Random. seed)]
    (Collections/shuffle al rng)
    (RT/vector (.toArray al))))

(defn enqueue [q item seed]
  (vec (deterministic-shuffle (conj (or q []) item) seed)))

(defn peek-cmd!
  []
  (let [popq (fn [q] (if (seq q) (pop q) []))
        [old new] (swap-vals! (:command-queue state/*queues*) popq)]
    (peek old)))

(defn enqueue-cmd! [cmd]
  (swap! (:command-queue state/*queues*) enqueue cmd (:seed state/*queues*)))

(defn clean-commands
  [cmd]
  (dissoc cmd
          :request-id
          :interaction-id
          :breadcrumbs))

(defn store-command
  "Stores command in memory structure"
  [ctx cmd]
  (log/info "Emulated 'store-cmd' dal function")
  (swap! (get-db ctx)
         #(update % :command-store (fnil conj []) (clean-commands cmd)))
  (enqueue-cmd! cmd))

(defn get-stored-commands
  [ctx]
  (get (get-db ctx) :command-store []))

(defn store-event
  "Stores event in memory structure"
  [ctx event]
  (log/info "Emulated 'store-event' dal function")
  (let [aggregate-id (:id event)]
    (when (some (fn [e]
                  (and (= (:id e)
                          aggregate-id)
                       (= (:event-seq e)
                          (:event-seq event))))
                (:event-store @(get-db ctx)))
      (throw (ex-info "Already existing event" {:id        aggregate-id
                                                :event-seq (:event-seq event)})))
    (swap! (get-db ctx)
           #(update % :event-store (fn [v]
                                     (sort-by
                                      :event-seq
                                      (conj v (dissoc
                                               event
                                               :interaction-id
                                               :request-id))))))))
(defn store-events
  [events])

(defn store-results-impl
  [ctx resp]
  (let [resp (fix-keys resp)]
    (log-response ctx)
    (doseq [i (:events resp)]
      (store-event ctx i))
    (doseq [i (:identities resp)]
      (store-identity ctx i))
    (doseq [i (:sequences resp)]
      (store-sequence ctx i))
    (doseq [i (:effects resp)]
      (store-command ctx i))
    (log/info resp)
    (log/info "Emulated 'with-transaction' dal function")
    ctx))

(defmethod store-results
  :memory
  [{:keys [resp] :as ctx}]
  (store-results-impl ctx resp))

(defmethod get-events
  :memory
  [{:keys [id version] :as ctx}]
  {:pre [id]}
  "Reads event from vector under :event-store key"
  (log/info "Emulated 'get-events' dal function")
  (let [resp (->> @(get-db ctx)
                  (:event-store)
                  (filter #(and (= (:id %) id)
                                (if version (> (:event-seq %) version) true)))
                  (into [])
                  (sort-by #(:event-seq %)))]
    (log/info "Found: " (count resp) " events with last having version: " (:event-seq (last resp)))
    resp))

(defmethod get-sequence-number-for-id
  :memory
  [{:keys [id] :as ctx}]
  {:pre [id]}
  (let [store (:sequence-store @(get-db ctx))
        entry (first (filter #(= (:id %) id)
                             store))]
    (:value entry)))

(defmethod get-command-response
  :memory
  [{:keys [request-id breadcrumbs] :as ctx}]

  (log/info "Emulating get-command-response-log" request-id breadcrumbs)
  (when (and request-id breadcrumbs)
    (let [store (:response-log (get-db ctx))]
      (first
       (filter #(and (= (:request-id %) request-id)
                     (= (:breadcrumbs %) breadcrumbs))
               store)))))

(defmethod get-id-for-sequence-number
  :memory
  [{:keys [sequence] :as ctx}]
  {:pre [sequence]}
  (let [store (:sequence-store @(get-db ctx))
        entry (first (filter #(= (:value %) sequence)
                             store))]
    (:id entry)))

(defn get-max-event-seq-impl
  [{:keys [id] :as ctx}]
  (log/info "Emulated 'get-max-event-seq' dal function with fixed return value 0")
  (let [resp (map
              #(:event-seq %)
              (filter
               #(= (:id %) id)
               (:event-store @(get-db ctx))))]
    (if (> (count resp) 0)
      (apply
       max
       resp)
      0)))

(defmethod get-max-event-seq
  :memory
  [ctx]
  (get-max-event-seq-impl ctx))

(defmethod get-aggregate-id-by-identity
  :memory
  [{:keys [identity] :as ctx}]
  {:pre [identity]}
  (log/info "Emulating get-aggregate-id-by-identity" identity)
  (let [store (:identity-store @(get-db ctx))]
    (if (coll? identity)
      (->> store
           (filter (fn [it]
                     (some #(= %
                               (:identity it))
                           identity)))
           (reduce
            (fn [p v]
              (assoc p
                     (:identity v)
                     (:id v)))
            {}))

      (->> store
           (filter #(= (:identity %) identity))
           (first)
           :id))))

(defmethod log-request
  :memory
  [ctx body]
  (log/info "Storing mock request" body)
  (swap! (get-db ctx)
         #(update % :command-log (fn [v] (conj v body)))))

(defmethod log-request-error
  :memory
  [ctx body error]
  (log/info "Should store mock request error" body error))

(defmethod log-dps
  :memory
  [{:keys [dps-resolved] :as ctx}]
  (log/debug "Storing mock dps" dps-resolved)
  (swap! (get-db ctx)
         #(update % :dps-log (fn [v] (conj v dps-resolved))))
  ctx)

(defmethod log-response
  :memory
  [{:keys [response-summary request-id breadcrumbs] :as ctx}]
  (log/info "Storing mock response" response-summary)
  (swap! (get-db ctx)
         #(update % :response-log (fn [v] (conj v {:request-id  request-id
                                                   :breadcrumbs breadcrumbs
                                                   :data        response-summary})))))

(defn is-locally-bound
  "Returns is DB is filled in locally. It will be always bound?
   but we want it to be bound per test also and globally for repl testing"
  [state-ref]
  (not
   (:global @state-ref)))

(defmethod with-init
  :memory
  [ctx body-fn]
  (log/debug "Initializing memory event store")
  (if (is-locally-bound *dal-state*)
    (do
      (swap! *dal-state*
             #(merge
               default-db
               (select-keys
                ctx
                (keys default-db))
               %))
      (body-fn ctx))
    (binding [*dal-state* (atom (merge
                                 default-db
                                 (util/fix-keys
                                  (select-keys
                                   ctx
                                   (keys default-db)))
                                 {:global false}))]
      (body-fn ctx))))

(defn register
  [ctx]
  (assoc ctx :edd-event-store :memory))

