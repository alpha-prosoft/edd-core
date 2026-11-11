(ns edd.java-lambda-runtime.core
  (:require
   [aws.lambda :as lambda]
   [aws.ctx :as aws-ctx]
   [lambda.ctx :as lambda-ctx]
   [lambda.metrics :as metrics]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [lambda.logging]
   [lambda.util :as util]
   [lambda.uuid :as uuid]
   [edd.core :as edd]
   [lambda.runtime :as runtime]
   [edd.el.cmd :as el-cmd]
   [edd.memory.view-store :as memory-view-store]
   [edd.memory.event-store :as memory-event-store]
   [lambda.test.fixture.state :refer [*dal-state* *queues*]])
  (:import
   [com.amazonaws.services.lambda.runtime Context]
   [org.crac Core]))

(defonce init-cache (atom {}))
(defonce restore-ctx (atom nil))

(defn ms-since [start-ns]
  (/ (- (System/nanoTime) (long start-ns)) 1e6))

(def ^:private creds-max-age-ms (* 30 60 1000))

(defn refresh-aws-creds!
  [ctx]
  (let [refresh-ctx
        (-> ctx
            (dissoc :aws-ctx-initialized)
            (update :aws dissoc
                    :aws-access-key-id
                    :aws-secret-access-key
                    :aws-session-token))

        aws
        (:aws (aws-ctx/init refresh-ctx))]
    (swap! init-cache assoc
           :aws aws
           :aws-fetched-at (System/currentTimeMillis))
    aws))

(defn- cached-aws
  [ctx]
  (let [{:keys [aws aws-fetched-at]}
        @init-cache]
    (if (and aws
             aws-fetched-at
             (< (- (System/currentTimeMillis) (long aws-fetched-at))
                creds-max-age-ms))
      aws
      (refresh-aws-creds! ctx))))

(defn- ensure-metrics! [ctx]
  (when-not (:metrics-started @init-cache)
    (metrics/start-metrics-publishing! ctx)
    (swap! init-cache assoc :metrics-started true)))

(defn- warm-up-ctx [ctx]
  ;; Warm-up traffic is infrastructure: registered features (e.g. security
  ;; filters and their deps) must not run against the synthetic warmup-cmd,
  ;; which is registered after init-features and would be denied.
  (-> ctx
      (update :edd-core dissoc :features)
      (assoc :meta {:realm :warmup})
      (memory-view-store/register)
      (memory-event-store/register)
      (edd/reg-cmd :warmup-cmd
                   (fn [_ctx cmd]
                     {:event-id :warmup-done
                      :id (:id cmd)})
                   :consumes [:map
                              [:cmd-id [:= :warmup-cmd]]
                              [:id uuid?]])
      (edd/reg-event :warmup-done
                     (fn [agg _event]
                       (assoc agg :warmed true)))))

(defn run-warm-up! [base-ctx]
  (try
    (let [preloaded
          (-> base-ctx
              (lambda-ctx/init)
              (aws-ctx/init))

          ctx
          (warm-up-ctx preloaded)]
      (binding [*dal-state* (atom {:realm :warmup
                                   :realms {:warmup {}}})
                *queues* {:command-queue (atom [])
                          :seed 0}]
        (runtime/with-invocation init-cache {}
          (edd/with-stores ctx
            #(el-cmd/handle-commands
              %
              {:request-id     (uuid/gen)
               :interaction-id (uuid/gen)
               :commands       [{:cmd-id :warmup-cmd
                                 :id     (uuid/gen)}]}))))
      (log/info "SnapStart warm-up complete."))
    (catch Exception e
      (log/warn "SnapStart warm-up failed (non-fatal):" (ex-message e)))))

(defn- write-response! [output ^String payload]
  (with-open [o (io/writer output)]
    (.write o payload)))

(defn java-request-handler
  "Options: :filters, runtime filters (see lambda.runtime)."
  [init-ctx handler & {:as opts}]
  (let [init-ctx
        (runtime/start init-ctx handler opts)]
    (fn [_this input output ^Context lambda-context]
      (runtime/with-invocation init-cache {}
        (let [ctx
              (assoc (lambda-ctx/init (assoc init-ctx
                                             :aws (cached-aws init-ctx)
                                             :aws-ctx-initialized true))
                     :invocation-id (uuid/parse (.getAwsRequestId lambda-context)))

              _
              (ensure-metrics! ctx)

              {:keys [response error?]}
              (lambda/handle-event ctx (util/to-edn (slurp input)))

              json
              (util/to-json response)]
          (write-response! output json)
          (when error?
            (throw (RuntimeException. json))))))))

(defmacro start
  [ctx handler & other]
  (let [this-sym# (gensym "this")
        input-stream-sym# (gensym "input-stream")
        output-stream-sym# (gensym "output-stream")
        lambda-context-sym# (gensym "lambda-context")
        crac-context-sym# (gensym "crac-context")]
    `(do
       (gen-class
        :name "lambda.Handler"
        :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler
                     org.crac.Resource]
        :prefix "-"
        :post-init "post-init"
        :main false)

       (def ~'-post-init
         (fn [~this-sym#]
           (.register (org.crac.Core/getGlobalContext) ~this-sym#)
           (clojure.tools.logging/info "lambda.Handler registered with CRaC global context.")))

       (def ~'-handler-fn
         (delay (edd.java-lambda-runtime.core/java-request-handler ~ctx ~handler ~@other)))

       (def ~'-handleRequest
         (fn [~this-sym#
              ^java.io.InputStream ~input-stream-sym#
              ^java.io.OutputStream ~output-stream-sym#
              ^com.amazonaws.services.lambda.runtime.Context ~lambda-context-sym#]
           ((deref ~'-handler-fn) ~this-sym# ~input-stream-sym# ~output-stream-sym# ~lambda-context-sym#)))

       (def ~'-beforeCheckpoint
         (fn [~this-sym# ^org.crac.Context ~crac-context-sym#]
           (let [start# (System/nanoTime)]
             (reset! edd.java-lambda-runtime.core/restore-ctx ~ctx)
             (deref ~'-handler-fn)
             (edd.java-lambda-runtime.core/run-warm-up! ~ctx)
             (swap! edd.java-lambda-runtime.core/init-cache dissoc
                    :aws :aws-fetched-at)
             (System/gc)
             (clojure.tools.logging/info
              (format "lambda.Handler -beforeCheckpoint done in %.1f ms"
                      (edd.java-lambda-runtime.core/ms-since start#))))))

       (def ~'-afterRestore
         (fn [~this-sym# ^org.crac.Context ~crac-context-sym#]
           (let [start# (System/nanoTime)]
             (swap! edd.java-lambda-runtime.core/init-cache dissoc :metrics-started)
             (try
               (edd.java-lambda-runtime.core/refresh-aws-creds!
                @edd.java-lambda-runtime.core/restore-ctx)
               (catch Exception e#
                 (clojure.tools.logging/warn
                  "lambda.Handler -afterRestore: credential refresh failed, retrying on first request:"
                  (ex-message e#))))
             (clojure.tools.logging/info
              (format "lambda.Handler -afterRestore done in %.1f ms"
                      (edd.java-lambda-runtime.core/ms-since start#)))))))))
