;; This namespace functions emulating Data Access Layer
;; by redefining Data Access Layer function and binding
;; state to thread local. Should be used only in tests.
;; Any test wanted to use emulated Data Access Layer
;; should call "defdaltest" macro.
;;
;; Example:
;;
;; (defdaltest when-store-and-load-events
;;   (dal/store-event {} {} {:id 1 :info "info"})
;;   (verify-state [{:id 1 :info "info"}] :event-store)
;;   (let [events (dal/get-events {} 1)]
;;     (is (= [{:id 1 :info "info"}]
;;            events))))
;;

(ns edd.test.fixture.dal
  (:require [clojure.tools.logging :as log]
            [clojure.test :refer [is]]
            [malli.core :as m]
            [malli.error :as me]
            [edd.el.cmd :as cmd]
            [edd.response.cache :as response-cache]
            [edd.core :as edd]
            [lambda.test.fixture.core :as lambda-fixture-core]
            [lambda.util :as util]
            [edd.common :as common]
            [lambda.uuid :as uuid]
            [edd.memory.event-store :as event-store]
            [edd.view-store.elastic :as elastic-view-store]
            [edd.view-store.common :as view-store-common]
            [edd.view-store.s3 :as s3-view-store]
            [lambda.test.fixture.client :as client]
            [lambda.test.fixture.state :refer [*queues*]]
            [aws.aws :as aws]
            [aws.runtime :as aws-runtime]
            [edd.ctx :as edd-ctx]
            [lambda.ctx :as lambda-ctx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Data Access Layer ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- like-cond
  "Returns function which checks if map contains key value pair
  described in condition, where value is not exact match"
  [condition]
  (log/info "like-cond" condition)
  (let [k (key (first condition))
        v (val (first condition))]
    (fn [x] (> (.indexOf (get x k) v) 0))))

(defn- equal-cond
  "Returns function which checks if map contains key value pair
  described in condition, where value is exact match"
  [condition]
  (log/info "equal-cond" condition)
  (let [k (key (first condition))
        v (val (first condition))]
    (fn [x] (= (get x k) v))))

(defn- full-search-cond
  "Returns function which checks if map contains any value which contains
  condition"
  [condition]
  (log/info "full-search-cond" condition)
  (fn [x] (some #(> (.indexOf % condition) 0) (vals x))))

(defn map-conditions [condition]
  (cond
    (:like condition) (like-cond (:like condition))
    (:equal condition) (equal-cond (:equal condition))
    (:search condition) (full-search-cond (:search condition))
    :else (fn [_] false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;   Test Fixtures   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def service-name lambda-ctx/default-service-name)

(def ctx
  (-> {:service-name service-name}
      (assoc-in [:edd :config :secrets-file] "files/secret-eu-west.json")
      (response-cache/register-default)
      (elastic-view-store/register :implementation :mock)
      (event-store/register)))

(def mock-s3-ctx
  (-> {:service-name service-name}
      (assoc-in [:edd :config :secrets-file] "files/secret-eu-west.json")
      (response-cache/register-default)
      (s3-view-store/register :implementation :mock)
      (event-store/register)))

(def current-ctx)

(defn create-identity
  [identities & [id]]
  (get identities id (uuid/gen)))

(def DepsSchema
  (m/schema
   [:vector
    [:map
     [:resp {:optional true}
      [:or [:map]
       nil?]]
     [:service [:or string? keyword?]]
     [:query
      [:map
       [:query-id keyword?]]]]]))

(defn prepare-dps-calls
  [deps]
  (when-not (m/validate DepsSchema deps)
    (throw (ex-info "Invcalid schema for :deps"
                    {:message "Invalid schema for :deps"
                     :validation (-> (m/explain DepsSchema deps)
                                     (me/humanize))})))
  (mapv
   #(-> {:method :post
         :url (cmd/calc-service-query-url (:service %))
         :response {:body (util/to-json {:result (:resp %)})}
         :request {:body {:query
                          (:query %)}}})
   deps))

(defn aws-get-token
  [_ctx]
  "#mock-id-token")

(defn init-db
  [params default-store]
  (merge
   default-store
   (util/fix-keys
    (select-keys
     params
     (keys default-store)))))

(def ^:dynamic *params* (atom {}))

(defmacro with-mock-dal [& body]
  (let [params (cond
                 (or (map? (first body))
                     (symbol? (first body))) (first body)
                 (= (-> body
                        first
                        first)
                    'assoc) (first body)
                 :else {})]
    `(do
       (when  lambda-fixture-core/*mocking*
         (throw (ex-info "Nested mocking" {:message "Nested mocking not allowed"})))
       (with-redefs [util/get-env (partial lambda-fixture-core/get-env-mock (:env ~params))]
         (binding [lambda-fixture-core/*mocking* true
                   event-store/*event-store* (atom (init-db ~params
                                                            event-store/default-db))
                   view-store-common/*view-store* (atom (init-db
                                                         ~params
                                                         view-store-common/default-store))
                   *params* (atom (merge {:seed (rand-int 10000000)}
                                         ~params))]
           (let [deps# (-> (get ~params
                                :deps
                                (get ~params :dps []))
                           (prepare-dps-calls)
                           (concat (get ~params :responses []))
                           (vec))
                 identities# (get ~params :identities {})]
             (client/mock-http
              ~params
              deps#
              (with-redefs [aws/get-token aws-get-token
                            common/create-identity (partial create-identity identities#)]
                (log/info "with-mock-dal using seed" (:seed *queues*))
                (do ~@body)))))))))

(defn get-state
  [x]
  (vec (if (= x :aggregate-store)
         (get @edd.view-store.common/*view-store* x)
         (get @edd.memory.event-store/*event-store* x))))

(defmacro verify-state [x & [y]]
  `(let [key# (if (keyword? ~y) ~y ~x)
         value# (if (keyword? ~y) ~x ~y)]
     (is (= value#
            (get-state key#)))))

(defmacro verify-state-fn [x fn y]
  `(is (= ~y (mapv
              ~fn
              (get-state ~x)))))

(defmacro verify-db
  [state-ctx x y]
  `(do (println (edd-ctx/get-realm ~state-ctx))
       (println (lambda-ctx/get-service-name))
       (is (= ~y
              (->> (~x @(event-store/get-db))
                   (filter
                    #(and (= (:realm %) (edd-ctx/get-realm ~state-ctx))
                          (= (:service-name %) (lambda-ctx/get-service-name ~state-ctx))))
                   (mapv :data))))))

(defn pop-state
  "Retrieves commands and removes them from the store"
  [x]
  (let [current-state (x @(event-store/get-db))]
    (swap! (event-store/get-db)
           #(update % x (fn [v] [])))
    current-state))

(defn peek-state
  "Retrieves the first command without removing it from the store"
  [& x]
  (let [all-db (merge @(event-store/get-db)
                      @(view-store-common/get-store))]
    (if x
      ((first x) all-db)
      (dissoc all-db                   :global))))

(defn- re-parse
  "Sometimes we parse things from outside differently
  because keywordize keys is by default. If you have map of string
  keys they would be keywordized. But if we pass in to test map
  that has string we would receive string. So here we re-parse requests"
  [cmd]
  (util/fix-keys cmd))

(def ^:dynamic *responses*)

(defn mock-runtime
  [ctx requests]
  (binding [*responses* (atom [])]
    (let [requests (if (map? requests)
                     [requests]
                     requests)
          requests (atom requests)]
      (aws.runtime/loop-runtime
       (assoc-in ctx [:edd :config :secrets-file] "files/secret-eu-west.json")
       edd/handler
       :send-response (fn [_ctx response]
                        (swap! *responses* conj response))
       :next-request (fn [i]
                       (let [next (first @requests)]
                         (swap! requests rest)
                         {:body next
                          :invocation-id (aws.runtime/form-invocation-number i)})))
      @*responses*)))

(defn remove-meta
  [resp]
  (cond-> resp
    true (dissoc :request-id :invocation-id :interaction-id)
    (get-in resp [:result :events]) (update-in [:result :events] #(if (number? %)
                                                                    %
                                                                    (mapv
                                                                     (fn [event]
                                                                       (dissoc event
                                                                               :request-id
                                                                               :interaction-id
                                                                               :meta))
                                                                     %)))
    (get-in resp [:result :effects]) (update-in [:result :effects] #(if (number? %)
                                                                      %
                                                                      (mapv
                                                                       (fn [cmd]
                                                                         (dissoc cmd
                                                                                 :request-id
                                                                                 :interaction-id
                                                                                 :meta))
                                                                       %)))))

(defn handle-cmd
  [{:keys [include-meta
           service-name]
    :or {include-meta false}
    :as ctx}
   cmd]
  (try
    (let [request-id (or (:request-id cmd)
                         (:request-id ctx)
                         (uuid/gen))
          interaction-id (or (:interaction-id cmd
                                              (:interaction-id ctx))
                             (uuid/gen))

          cmd (if (contains? cmd :commands)
                (re-parse cmd)
                {:commands [(re-parse cmd)]
                 :service-name service-name})
          cmd (assoc cmd
                     :request-id request-id
                     :interaction-id interaction-id)
          resp  (when (or (= service-name
                             (:service cmd))
                          (= nil
                             (:service cmd)))
                  (mock-runtime ctx (re-parse cmd)))
          resp (if include-meta
                 resp
                 (mapv remove-meta resp))]
      (first resp))
    (catch Exception ex
      (log/error "CMD execution ERROR" ex)
      (ex-data ex))))

(def handle-commands handle-cmd)

(defn get-commands-response
  [ctx cmd]
  (handle-cmd (assoc ctx
                     :no-summary true)
              cmd))

(defn apply-events
  [{:keys [include-meta request-id interaction-id]
    :or {request-id (uuid/gen)
         interaction-id (uuid/gen)
         include-meta false}
    :as ctx} id]
  (let [resp (mock-runtime (assoc ctx
                                  :request-id request-id
                                  :interaction-id interaction-id)
                           {:apply {:aggregate-id id}
                            :request-id request-id
                            :interaction-id interaction-id})]
    (if include-meta
      resp
      (mapv remove-meta resp))))

(defn apply-cmd
  [{:keys [no-summary]
    :or {no-summary true}
    :as ctx}
   cmd]
  (log/info "apply-cmd" cmd)
  (let [ctx (assoc ctx :no-summary no-summary)
        resp (handle-cmd ctx cmd)
        ids (if (map? resp)
              [resp]
              resp)
        ids (mapv #(-> (-> %
                           :result
                           :events)) ids)
        ids (flatten ids)
        ids (distinct (map :id ids))]
    (log/info "apply-cmd returned event ids: " ids)
    (mapv
     #(apply-events ctx %)
     ids)
    resp))

(def max-depth 5)

(defn execute-cmd
  "Executes a command and applies all the side effects
   and executes also the produced commands until the
  command store is empty."
  [ctx cmd]
  (let [ctx (assoc ctx
                   :include-meta true
                   :no-summary true)
        cmds (if (seq? cmd)
               cmd
               [cmd])]
    (loop [depth 0
           cmds cmds
           responses []]
      (when (> depth max-depth)
        (throw (ex-info "Too deep man"
                        {:depth depth
                         :max max-depth})))
      (let [resp (mapv
                  #(apply-cmd ctx %)
                  cmds)
            effects (flatten
                     (map
                      #(-> %
                           :result
                           :effects)
                      resp))
            effects (remove nil? effects)]
        (if-not (empty? effects)
          (recur (inc depth)
                 effects
                 (conj responses resp))
          responses)))))

(defn execute-fx [ctx]
  (execute-cmd ctx (peek-state :command-store)))

(defn handle-event
  [{:keys [include-meta request-id interaction-id]
    :or {request-id (uuid/gen)
         interaction-id (uuid/gen)
         include-meta false}
    :as ctx} event]
  (let [resp (first (mock-runtime (assoc ctx
                                         :request-id request-id
                                         :interaction-id interaction-id)
                                  (assoc
                                   event
                                   :request-id request-id
                                   :interaction-id interaction-id)))]
    (if include-meta
      resp
      (remove-meta resp))))

(defn query
  [{:keys [include-meta]
    :or {include-meta false}
    :as ctx}
   query]
  (let [request-id (or (:request-id query)
                       (:request-id ctx)
                       (uuid/gen))
        interaction-id (or (:interaction-id query)
                           (:interaction-id ctx)
                           (uuid/gen))
        query (if (contains? query :query)
                (re-parse query)
                (re-parse {:query query}))
        query (assoc query
                     :request-id request-id
                     :interaction-id interaction-id)
        resp (mock-runtime (assoc ctx
                                  :request-id request-id
                                  :interaction-id interaction-id)
                           query)
        resp (if (vector? resp)
               (first resp)
               resp)]
    (if include-meta
      resp
      (remove-meta resp))))

(def handle-query query)

(defn get-by-id
  [ctx {:keys [id]}]
  (let [ctx (-> ctx
                (edd/reg-query :mock->get-by-id common/get-by-id))
        resp (query ctx {:query-id :mock->get-by-id
                         :id id})]
    resp))
