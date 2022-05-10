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

(def ^:dynamic *event-store* (atom default-db))

(defn get-db
  []
  *event-store*)

(defn store-sequence
  "Stores sequence in memory structure.
  Raises RuntimeException if sequence is already taken"
  [ctx {:keys [id]}]
  {:pre [id]}
  (log/info "Emulated 'store-sequence' dal function")
  (let [store (:sequence-store @(get-db))
        sequence-already-exists (some
                                 #(= (:id %) id)
                                 store)
        max-number (count store)]
    (if sequence-already-exists
      (throw (RuntimeException. "Sequence already exists")))
    (swap! (get-db)
           #(update % :sequence-store (fn [v] (conj v {:id    id
                                                       :value (inc max-number)}))))))

(defn store-identity
  "Stores identity in memory structure.
  Raises RuntimeException if identity is already taken"
  [ctx identity]
  (log/info "Emulated 'store-identity' dal function")
  (let [id-fn (juxt :id :identity)
        id (id-fn identity)
        store (:identity-store @(get-db))
        id-already-exists (some #(= (id-fn %) id) store)]
    (when id-already-exists
      (throw (RuntimeException. "Identity already exists")))
    (swap! (get-db)
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
        [old _new] (swap-vals! (:command-queue state/*queues*) popq)]
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
  [_ctx cmd]
  (log/info "Emulated 'store-cmd' dal function")
  (swap! (get-db)
         #(update % :command-store (fnil conj []) (clean-commands cmd)))
  (enqueue-cmd! cmd))

(defn get-stored-commands
  [_ctx]
  (get (get-db) :command-store []))

(defn store-event
  "Stores event in memory structure"
  [_ctx event]
  (log/info "Emulated 'store-event' dal function")
  (let [aggregate-id (:id event)]
    (when (some (fn [e]
                  (and (= (:id e)
                          aggregate-id)
                       (= (:event-seq e)
                          (:event-seq event))))
                (:event-store @(get-db)))
      (throw (ex-info "Already existing event" {:id        aggregate-id
                                                :event-seq (:event-seq event)})))
    (swap! (get-db)
           #(update % :event-store (fn [v]
                                     (sort-by
                                      :event-seq
                                      (conj v (dissoc
                                               event
                                               :interaction-id
                                               :request-id))))))))
(defn store-events
  [_events])

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
  (let [resp (->> @(get-db)
                  (:event-store)
                  (filter #(and (= (:id %) id)
                                (if version (> (:event-seq %) version) true)))
                  (into [])
                  (sort-by #(:event-seq %)))]
    (log/info "Found: " (count resp) " events with last having version: " (:event-seq (last resp)))
    resp))

(defmethod get-sequence-number-for-id
  :memory
  [{:keys [id] :as _ctx}]
  {:pre [id]}
  (let [store (:sequence-store @(get-db))
        entry (first (filter #(= (:id %) id)
                             store))]
    (:value entry)))

(defmethod get-command-response
  :memory
  [{:keys [request-id breadcrumbs] :as _ctx}]

  (log/info "Emulating get-command-response-log" request-id breadcrumbs)
  (when (and request-id breadcrumbs)
    (let [store (:response-log (get-db))]
      (first
       (filter #(and (= (:request-id %) request-id)
                     (= (:breadcrumbs %) breadcrumbs))
               store)))))

(defmethod get-id-for-sequence-number
  :memory
  [{:keys [sequence] :as _ctx}]
  {:pre [sequence]}
  (let [store (:sequence-store @(get-db))
        entry (first (filter #(= (:value %) sequence)
                             store))]
    (:id entry)))

(defn get-max-event-seq-impl
  [{:keys [id] :as _ctx}]
  (log/info "Emulated 'get-max-event-seq' dal function with fixed return value 0")
  (let [resp (map
              #(:event-seq %)
              (filter
               #(= (:id %) id)
               (:event-store @(get-db))))]
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
  [{:keys [identity] :as _ctx}]
  {:pre [identity]}
  (log/info "Emulating get-aggregate-id-by-identity" identity)
  (let [store (:identity-store @(get-db))]
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
  [_ctx body]
  (log/info "Storing mock request" body)
  (swap! (get-db)
         #(update % :command-log (fn [v] (conj v body)))))

(defmethod log-request-error
  :memory
  [_ctx body error]
  (log/info "Should store mock request error" body error))

(defmethod log-dps
  :memory
  [{:keys [dps-resolved] :as ctx}]
  (log/debug "Storing mock dps" dps-resolved)
  (swap! (get-db)
         #(update % :dps-log (fn [v] (conj v dps-resolved))))
  ctx)

(defmethod log-response
  :memory
  [{:keys [response-summary request-id breadcrumbs] :as _ctx}]
  (log/info "Storing mock response" response-summary)
  (swap! (get-db)
         #(update % :response-log (fn [v] (conj v {:request-id  request-id
                                                   :breadcrumbs breadcrumbs
                                                   :data        response-summary})))))

(defmethod with-init
  :memork
  [ctx body-fn]
  (log/debug "Initializing memory event store")
  (body-fn ctx))

(defn register
  [ctx]
  (assoc ctx :edd-event-store :memory))

