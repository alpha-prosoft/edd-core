(ns lambda.runtime
  (:require [lambda.util :as util]
            [lambda.request :as request]
            [clojure.tools.logging :as log]
            [lambda.ctx :as lambda-ctx]
            [lambda.uuid :as uuid]))

(defn apply-filters
  [ctx filters]
  (let  [{:keys [condition handler]} (first filters)
         remaining-filters (rest filters)
         remaining-filters (if (empty? remaining-filters)
                             nil
                             remaining-filters)
         filter-chain (if remaining-filters
                        #(apply-filters % remaining-filters)
                        nil)]
    (if (apply condition [ctx])
      ;; => Syntax error compiling at (src/lambda/core.clj:16:9).
      ;;    Unable to resolve symbol: condition in this context
      (apply handler [ctx filter-chain])
      (if remaining-filters
        (apply-filters ctx remaining-filters)
        (throw (ex-info "Last filter (Handler) should always be applicatble"
                        {:remaining remaining-filters}))))))

(defn invoke-handler
  [{:keys [body] :as ctx} & {:keys [handler]}]
  (util/d-time
   "time-invoke-handler"
   (handler ctx body)))

(defn get-loop
  "Extracting lambda looping as infinite loop to be able to mock it"
  []
  (range))

(defn init-filters
  [ctx filters]
  (reduce
   (fn [c {:keys [init]}]
     (if init
       (init c)
       c))
   (merge ctx
          (util/load-config (get-in ctx
                                    [:edd :config :secrets-file]
                                    "secret.json"))
          {:filter-initialized true})
   filters))

(defn handle-request
  [ctx request & {:keys [handler
                         filters
                         invocation-id
                         send-response]}]
  (println "Handling request")
  (when-not (bound? #'util/*cache*)
    (throw (ex-info "*cache* is not bound"
                    {:message "*cache* is not bound. Runtime is responsible
                               for binding *cace* to ensure application
                               scoped atom"})))

  (when-not (:filter-initialized ctx)
    (throw (ex-info "Filters not initialized"
                    {:message "Runtime should initize all filters"})))
  (let [invocation-id (uuid/parse invocation-id)
        ctx (assoc ctx
                   :body request
                   :request request
                   :invocation-id invocation-id)]
    (binding [request/*request* (atom {:mdc {:invocation-id invocation-id}})]
      (util/d-time
       (str "handling-request: " invocation-id)
       (try
         (let [ctx (lambda-ctx/init ctx)
               filters (conj filters
                             {:condition (fn [_] true)
                              :handler (fn [ctx _]
                                         (invoke-handler ctx :handler handler))})
               response (apply-filters ctx filters)]
           (doall
            (send-response ctx response)))

         (catch Exception e
           (log/error "Error processing request" e)
           (send-response ctx (util/exception->response e)))
         (catch AssertionError e
           (log/error "Error processing request" e)
           (send-response ctx (util/exception->response e))))))))

