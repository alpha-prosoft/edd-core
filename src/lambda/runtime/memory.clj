(ns lambda.runtime.memory
  "In-memory runtime for tests. start initializes ctx like every runtime;
   the handle-* fns run one invocation each in its own request scope, without
   a server or a queue: an HTTP request in the lambda.http shape, a batch of
   queue records or a plain request."
  (:require [edd.ctx :as edd-ctx]
            [lambda.ctx :as lambda-ctx]
            [lambda.http :as http]
            [lambda.request :as request]
            [lambda.runtime :as runtime]
            [lambda.uuid :as uuid]))

(defn start
  "Returns the runtime ctx the handle-* fns take. Options: :filters, runtime
   filters (see lambda.runtime)."
  [init-ctx handler & {:as opts}]
  (edd-ctx/init-features
   (lambda-ctx/init
    (runtime/init init-ctx handler opts))))

(defn- invoke
  [ctx f]
  (binding [request/*request* (atom {:scoped true})]
    (f (update ctx :invocation-id #(or % (uuid/gen))))))

(defn handle-http
  "HTTP response of an HTTP request."
  [ctx http-request]
  (invoke ctx #(http/handle % http-request)))

(defn handle-records
  "Handles queue records {:body json} as one batch. Returns the :responses
   of the handled records and whether the batch has to be delivered again
   (:redeliver?)."
  [ctx records]
  (invoke ctx
          (fn [invocation-ctx]
            (let [responses
                  (runtime/handle-records invocation-ctx records)]
              {:responses responses
               :redeliver? (runtime/complete-records! invocation-ctx records responses)}))))

(defn handle-request
  "Response of a request that is neither HTTP nor a queue record."
  [ctx request]
  (invoke ctx #(runtime/distribute! % (runtime/handle % request))))
