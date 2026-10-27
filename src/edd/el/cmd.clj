(ns edd.el.cmd
  (:require
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [lambda.util :as util]
   [edd.dal :as dal]
   [lambda.request :as request]
   [malli.error :as me]
   [edd.distribution.response :as response]
   [edd.ctx :as edd-ctx]
   [edd.el.ctx :as el-ctx]
   [edd.common :as common]
   [edd.el.event :as event]
   [edd.request-cache :as request-cache]
   [edd.schema.core :as edd-schema]
   [edd.el.query :as query])
  (:import
   (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def resolve-remote-dependency query/resolve-remote-dependency)
(def resolve-local-dependency query/resolve-local-dependency)

(defn fetch-dependencies-for-command
  [ctx {:keys [cmd-id] :as cmd}]
  (log/debug "Fetching context for: " cmd-id)
  (util/d-time
   (str "Fetching all deps for " cmd-id)
   (let [{:keys [deps]} (edd-ctx/get-cmd ctx cmd-id)

         deps
         (query/merge-feature-deps ctx deps)]
     (query/fetch-dependencies-for-deps ctx deps cmd))))

(defn with-breadcrumbs
  [ctx resp]
  (let [parent-breadcrumb (or (get-in ctx [:breadcrumbs]) [])]
    (update resp :effects
            (fn [cmds]
              (map-indexed
               (fn [i cmd]
                 (assoc cmd :breadcrumbs
                        (conj parent-breadcrumb i))) cmds)))))

(defn wrap-commands
  [ctx commands]
  (let [commands (if (map? commands)
                   [commands]
                   commands)]
    (map
     (fn [cmd]
       (let [cmd (if-not (:commands cmd)
                   {:commands [cmd]}
                   cmd)
             cmd (update cmd
                         :service
                         #(or %
                              (:service-name ctx)))]
         cmd))
     commands)))

(defn clean-effects
  [ctx effects]
  (if (seq effects)
    (->> effects
         (remove nil?)
         (wrap-commands ctx)
         (filter #(seq (:commands %))))
    []))

(defn produce-effects
  [ctx events]
  (let [effects (reduce
                 (fn [cmd fx-fn]
                   (let [resp (fx-fn ctx events)]
                     (into cmd
                           (if (map? resp)
                             [resp]
                             resp))))
                 []
                 (:fx ctx))
        effects (flatten effects)
        effects (clean-effects ctx effects)]
    (map (fn [effect]
           (let [updated-fx
                 (assoc effect
                        :request-id (:request-id ctx)
                        :interaction-id (:interaction-id ctx)
                        :meta (merge (:meta ctx {}) (:meta effect)))]
             (if (and (contains? (:meta effect) :group-id) (nil? (get-in effect [:meta :group-id])))
               (update updated-fx :meta dissoc :group-id)
               updated-fx)))
         effects)))

(defn handle-effects
  [ctx & {:keys [resp aggregate]}]
  (let [ctx (el-ctx/put-aggregate ctx aggregate)
        effects (edd-ctx/run-filters ctx
                                     :effect-filter
                                     produce-effects
                                     (:events resp))]
    (assoc resp :effects effects)))

(defn to-clean-vector
  [resp]
  (if (or (vector? resp)
          (list? resp))
    (remove nil?
            resp)
    (if resp
      [resp]
      [])))

(defn resp->add-meta-to-events
  [ctx {:keys [events] :as resp}]
  (let [created-on
        (util/date->string)

        meta
        (-> (:meta ctx {})
            (assoc :created-on created-on)
            (cond->
             (:invocation-id ctx) (assoc :invocation-id (:invocation-id ctx))))]
    (assoc resp
           :events
           (map
            (fn [{:keys [error] :as %}]
              (if-not error
                (assoc % :meta meta
                       :request-id (:request-id ctx)
                       :interaction-id (:interaction-id ctx))
                %))
            events))))

(defn resp->validate-and-add-user
  [{:keys [user]} resp]
  (update resp :events
          (fn [events]
            (map (fn [event]
                   (edd-schema/validate-event event)
                   (if user
                     (assoc event
                            :user (:id user)
                            :role (:role user))
                     event))
                 events))))

(defn resolve-command-id-with-id-fn
  "Resolving command id. Taking into account override function of id.
  If id-fn returns null we fallback to command id. Override should
  be only used when it is impossible to create id on client. Like in case
  of import"
  [ctx cmd]
  (let [cmd-id (:cmd-id cmd)
        {:keys [id-fn]} (edd-ctx/get-cmd ctx cmd-id)]

    (if id-fn
      (let [new-id (id-fn ctx cmd)]
        (if new-id
          (assoc cmd :id new-id)
          cmd))
      cmd)))

(defn resp->assign-event-seq
  [ctx {:keys [events] :as resp}]
  (let [aggregate (el-ctx/get-aggregate ctx)
        last-sequence (long (:version aggregate 0))]
    (assoc resp
           :events (map-indexed (fn [^long idx event]
                                  (assoc event
                                         :event-seq
                                         (+ last-sequence idx 1)))
                                events))))

(defn verify-command-version
  [ctx cmd]
  (let [aggregate (el-ctx/get-aggregate ctx)
        {:keys [version]} cmd
        current-version (:version aggregate)]
    (cond
      (nil? version) ctx
      (not= version current-version) (throw (ex-info "Wrong version"
                                                     {:error   :concurrent-modification
                                                      :message "Version mismatch"
                                                      :state   {:current current-version
                                                                :version version}}))
      :else ctx)))

(defn invoke-handler
  "We add try catch here in order to parse
  all Exceptions thrown by handler in to data. Afterwards we want only ex-info"
  [handler cmd ctx]
  (try
    (handler ctx cmd)
    (catch Exception e
      (let [msg
            (format
             "Command handler has failed, cmd-id: %s, id: %s"
             (-> cmd :cmd-id)
             (-> cmd :id))]
        (log/warnf e
                   msg
                   (-> cmd :cmd-id)
                   (-> cmd :id))
        (if (ex-data e)
          (throw e)
          (throw (ex-info msg
                          {:message msg}
                          e)))))))

(defn get-response-from-command-handler
  [ctx & {:keys [command-handler cmd]}]
  (verify-command-version ctx cmd)
  (let [events (->> ctx
                    (invoke-handler command-handler cmd)
                    (to-clean-vector)
                    (map #(assoc % :id (:id cmd)))
                    (remove nil?))
        response {:events     []
                  :identities []}]
    (if (some :error events)
      (assoc response
             :error (vec
                     (filter :error events)))
      (reduce
       (fn [p event]
         (cond-> p
           (contains? event :identity) (update :identities conj event)
           (contains? event :event-id) (update :events conj event)))
       response
       events))))

(defn validate-single-command
  [ctx {:keys [cmd-id] :as cmd}]
  (let [{:keys [consumes
                handler]} (edd-ctx/get-cmd ctx cmd-id)]
    (cond
      (not (m/validate (edd-schema/EddCoreCommand) cmd))
      {:error (->> cmd
                   (m/explain (edd-schema/EddCoreCommand))
                   (me/humanize))}
      (not handler)
      {:error (str "Missing handler: " cmd-id)}
      (not (m/validate consumes cmd))
      {:error (->> cmd
                   (m/explain consumes)
                   (me/humanize))}

      :else true)))

(defn with-aggregate
  [aggregate]
  (when aggregate
    (swap! request/*request* #(update % :mdc
                                      assoc
                                      :aggregate
                                      (select-keys aggregate
                                                   [:id :version])))))

(defn handle-command
  [ctx {:keys [cmd-id] :as cmd}]
  (log/info "Requst meta: " (:meta ctx))
  (util/d-metrics ctx [:command cmd-id]
                  (util/d-time
                   (str "handling-command: " cmd-id)
                   (let [{:keys [handler]} (edd-ctx/get-cmd ctx cmd-id)
                         ctx (fetch-dependencies-for-command ctx cmd)
                         {:keys [id] :as cmd} (resolve-command-id-with-id-fn ctx cmd)
                         {:keys [error]
                          :as validation} (validate-single-command ctx cmd)]
                     (when-not handler
                       (throw (ex-info "Missing command handler"
                                       {:cmd-id (:cmd-id cmd)})))
                     (if error
                       (do
                         (log/errorf "Command validation failed, cmd-id: %s, id: %s, error: %s"
                                     cmd-id
                                     id
                                     (pr-str error))
                         validation)
                       (let [snapshot
                             (common/get-by-id ctx id)

                             _
                             (with-aggregate snapshot)

                             ctx
                             (el-ctx/put-aggregate ctx snapshot)

                             handler-resp
                             (edd-ctx/run-filters
                              ctx
                              :command-filter
                              (fn [ctx cmd]
                                (get-response-from-command-handler
                                 ctx
                                 :cmd cmd
                                 :command-handler handler))
                              cmd)]
                         (if (:error handler-resp)
                           (do
                             (log/errorf "Command rejected, cmd-id: %s, id: %s, error: %s"
                                         cmd-id
                                         id
                                         (pr-str (:error handler-resp)))
                             handler-resp)
                           (let [resp
                                 (->> handler-resp
                                      (resp->validate-and-add-user ctx)
                                      (resp->assign-event-seq ctx))

                                 resp
                                 (assoc resp :meta [{cmd-id {:id id}}])

                                 aggregate-schema
                                 (edd-ctx/get-service-schema ctx)

                                 aggregate
                                 (event/get-current-state
                                  ctx
                                  {:id id
                                   :events (:events resp [])
                                   :snapshot snapshot})

                                 _
                                 (with-aggregate aggregate)]

                             (when (and
                                    aggregate
                                    (not
                                     (m/validate aggregate-schema aggregate)))
                               (throw (ex-info "Invalid aggregate state"
                                               {:error (me/humanize
                                                        (m/explain aggregate-schema aggregate))})))

                             (let [resp
                                   (handle-effects ctx
                                                   :resp resp
                                                   :aggregate aggregate)

                                   resp
                                   (resp->add-meta-to-events ctx resp)

                                   annotated-aggregate
                                   (event/annotate-aggregate aggregate snapshot (:events resp []))

                                   history-entry
                                   {:snapshot snapshot
                                    :aggregate annotated-aggregate}

                                   conjv
                                   (fnil conj [])

                                   resp
                                   (update resp :history conjv history-entry)]

                               (request-cache/update-aggregate ctx annotated-aggregate)
                               (request-cache/store-identities ctx (:identities resp))
                               resp)))))))))

(def initial-response
  {:meta       []
   :events     []
   :effects    []
   :history    []
   :identities []})

(defn validate-commands
  "Validate if commands match spec and if they are valid commands"
  [_ commands]
  (when (empty? commands)
    (throw (ex-info "No commands present"
                    {:error "No commands present in request"}))))

(defn resp->response-summary
  [{:keys [no-summary]} resp]

  (let [;; history should not be a part of response to the client
        resp (dissoc resp :history)
        sample (take 11 (:effects resp))
        sample (if (> (count sample) 10)
                 []
                 sample)]
    (cond
      (:error resp) resp
      no-summary resp :else {:success    true
                             :effects    (reduce
                                          (fn [p v]
                                            (into
                                             p
                                             (map
                                              (fn [%] {:id           (:id %)
                                                       :cmd-id       (:cmd-id %)
                                                       :service-name (:service v)})
                                              (:commands v))))
                                          []
                                          sample)
                             :events     (count (:events resp))
                             :meta       (:meta resp)
                             :identities (count (:identities resp))})))

(defn e-concurrent-modification?
  "
  Given an exception, check if was triggered
  by a concurrent modification case.
  "
  ^Boolean [^ExceptionInfo e]
  (some-> e ex-data :error :key (= :concurrent-modification)))

(defn retry [f ^long n]
  (try
    (f)
    (catch ExceptionInfo e
      (request-cache/clear)
      (if (and (e-concurrent-modification? e)
               (> n 1))
        (do
          (log/warnf e
                     "retry has failed, attempts left: %s, function: %s"
                     n f)
          (Thread/sleep (+ 1000 (long (rand-int 1000))))
          (retry f (dec n)))
        (throw e)))))

(defn- log-request
  "Logs the request, returns how many times it was logged before when the
   event store counts."
  [ctx body]
  (let [logged
        (dal/log-request ctx body)]
    (when (integer? logged)
      logged)))

(defn- with-handler-info
  "Tells a repeated request apart: metadata {:handler {:attempt n :cached
   true}} on the response, :attempt for a request seen before and :cached
   when its stored response is returned. Nothing for a first execution."
  [resp attempt cached?]
  (let [info
        (cond-> {}
          (and attempt (pos? (long attempt))) (assoc :attempt attempt)
          cached? (assoc :cached true))]
    (if (seq info)
      (with-meta resp {:handler info})
      resp)))

(defn store-results
  "Persists the response and queues its router payloads, distributed once
   the request is answered."
  [ctx resp]
  (let [{:keys [payloads cache-result]}
        (response/prepare ctx resp)

        stored
        (cond-> resp
          cache-result (assoc-in [:summary :cache-result] cache-result))]
    (doall
     (dal/store-results (assoc ctx :resp stored)))
    (response/queue! payloads)
    (:summary resp)))

(defn process-commands
  [ctx {:keys [commands] :as body}]
  (let [ctx (-> ctx
                (assoc :commands commands
                       :breadcrumbs (or (get body :breadcrumbs)
                                        [0])))

        attempt
        (log-request ctx body)]

    (if-let [resp (:data (dal/get-command-response ctx))]
      (do
        (response/queue-stored-response! ctx resp)
        (with-handler-info (dissoc resp :cache-result) attempt true))
      (do
        (validate-commands ctx commands)
        (let [resp
              (util/d-time
               "handle-request"
               (loop [queue commands
                      resp initial-response]
                 (let [c (first queue)
                       r (handle-command ctx c)
                       resp (merge-with into resp r)]
                   (cond
                     (:error r) (assoc initial-response
                                       :error (:error r))
                     (seq
                      (rest queue)) (recur (rest queue)
                                           resp)
                     :else resp))))

              resp
              (util/d-time
               "assign-breadcumbts"
               (with-breadcrumbs ctx resp))

              summary
              (util/d-time
               "summarize-response"
               (resp->response-summary ctx resp))

              resp
              (assoc resp
                     :summary summary)

              result
              (util/d-time
               "store-final-result"
               (store-results ctx resp))]

          (with-handler-info result attempt false))))))

(defn handle-commands
  [ctx body]
  (let [ref-date
        (util/date-time)

        authenticated
        (when (:from-api ctx)
          (select-keys (:meta ctx) [:user :realm]))

        ctx
        (assoc ctx
               :meta (merge
                      (:meta ctx {})
                      (:meta body {})
                      authenticated)
               :ref-date ref-date)]

    (try
      (retry #(process-commands ctx body)
             3)
      (catch ExceptionInfo e
        (dal/log-request-error ctx body (ex-data e))
        (throw e)))))
