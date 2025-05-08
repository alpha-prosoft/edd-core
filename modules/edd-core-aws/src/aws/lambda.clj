(ns aws.lambda
  (:require [aws.runtime :as runtime]
            [aws.ctx :as aws-ctx]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [clojure.java.io :as io])
  (:import (com.amazonaws.services.lambda.runtime Context)))

(defn java-request-handler
  [ctx handler & {:keys [filters]}]
  (fn [_this input output ^Context context]
    (log/info "Started handling")
    (let [req (util/to-edn
               (slurp input))
          req {:body req
               :invocation-id (.getAwsRequestId context)}
          requests (atom req)]
      (runtime/loop-runtime
       (-> ctx
           (aws-ctx/init))
       handler
       :filters filters
       :next-request (fn [_i]
                       (log/info "Getting next request")
                       (when @requests
                         (let [request @requests]
                           (reset! requests nil)
                           request)))
       :send-response (fn [_ctx response]
                        (log/info "Sending response")
                        (with-open [o (io/writer output)]
                          (.write o (util/to-json response))))))))

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
        :main false)

       (def ~'-handleRequest
         (fn
           [~this-sym#
            ^java.io.InputStream ~input-stream-sym#
            ^java.io.OutputStream ~output-stream-sym#
            ^com.amazonaws.services.lambda.runtime.Context ~lambda-context-sym#]
           (clojure.tools.logging/info "lambda.Handler -handleRequest invoked." {:ctx ~ctx :handler ~handler :other (list ~@other)})
           (let [actual-handler-fn# (aws.lambda/java-request-handler ~ctx ~handler ~@other)]
             (actual-handler-fn# ~this-sym# ~input-stream-sym# ~output-stream-sym# ~lambda-context-sym#))))

       (def ~'-beforeCheckpoint
         (fn
           [~this-sym# ^org.crac.Context ~crac-context-sym#]
           (clojure.tools.logging/info "lambda.Handler -beforeCheckpoint invoked.")))

       (def ~'-afterRestore
         (fn
           [~this-sym# ^org.crac.Context ~crac-context-sym#]
           (clojure.tools.logging/info "lambda.Handler -afterRestore invoked."))))))


