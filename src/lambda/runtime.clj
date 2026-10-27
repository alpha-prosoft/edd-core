(ns lambda.runtime
  "Request pipeline shared by every runtime (AWS Lambda, Undertow, queue
   consumers). A runtime decodes its input into edd requests (lambda.http for
   HTTP, handle-records for queue records) and runs each through its filters
   and the handler.

   Filters are runtime options, (fn [ctx request chain]) wrapping the handler
   like servlet filters: call (chain ctx request) to proceed and get the
   response, or return a response without calling it. The first filter is
   the outermost."
  (:require [clojure.tools.logging :as log]
            [edd.distribution :as distribution]
            [lambda.util :as util]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private migration-hint
  "see MIGRATIONS.md, \"Runtime filters\"")

(defn options
  "Validates runtime options, rejecting the removed :post-filter (responses
   are encoded by the runtime) and {:cond :fn} filters."
  [{:keys [filters] :as opts}]
  (when (contains? opts :post-filter)
    (throw (ex-info (str ":post-filter was removed, the runtime encodes responses itself and "
                         "filters post-process the response of (chain ctx request); " migration-hint)
                    {:option :post-filter})))
  (doseq [f filters]
    (when-not (fn? f)
      (throw (ex-info (str "Filters are (fn [ctx request chain]); lambda.filters/from-api, from-queue "
                           "and to-api are built into the runtimes; " migration-hint)
                      {:filter (if (map? f) (vec (keys f)) f)}))))
  opts)

(defn init
  [ctx handler opts]
  (assoc ctx
         :handler handler
         :filters (vec (:filters (options opts)))))

(defn error-response
  [_ctx e]
  (log/error e "Error processing request")
  {:exception {:message (or (ex-data e)
                            (ex-message e)
                            "Unable to parse exception")}})

(defn handle-with
  "Calls f, turning exceptions into an error response."
  [ctx f]
  (try
    (f)
    (catch Exception e
      (error-response ctx e))
    (catch AssertionError e
      (error-response ctx e))))

(defn- chain
  [filters handler]
  (reduce
   (fn [next-chain f]
     (fn [ctx request]
       (f ctx request next-chain)))
   (fn [ctx request]
     (doall (handler ctx request)))
   (reverse filters)))

(defn handle
  "Runs one edd request through the filters and the handler."
  [{:keys [filters handler] :as ctx} request]
  (util/d-time
   "time-invoke-handler"
   (handle-with ctx #((chain filters handler) ctx request))))

(defn failed?
  [response]
  (some? (if (sequential? response)
           (some :exception response)
           (:exception response))))

(defn handle-records
  "Handles queue records in order, each body as one request, and stops at
   the first failure. Returns the responses of the handled records."
  [ctx records]
  (loop [[record & more] records
         responses []]
    (if-not record
      responses
      (let [response
            (handle-with ctx #(handle ctx (distribution/unwrap ctx (util/to-edn (:body record)))))

            handled
            (conj responses response)]
        (if (failed? response)
          handled
          (recur more handled))))))

(defn distribute!
  "Distributes the router payloads of the request. Returns the response, or
   an error response when distribution failed: the request is then repeated
   and its stored response distributed again."
  [ctx response]
  (handle-with ctx (fn []
                     (distribution/distribute-response! ctx)
                     response)))

(defn complete-records!
  "Distributes the router payloads of the handled records. When one failed,
   the ones before it are acknowledged so only the rest is delivered again;
   when distribution failed nothing is acknowledged. Returns whether the
   records have to be delivered again."
  [ctx records responses]
  (cond
    (failed? (distribute! ctx nil))
    true

    (failed? (last responses))
    (do
      (distribution/acknowledge-delivered! ctx (take (dec (count responses)) records))
      true)

    :else
    false))
