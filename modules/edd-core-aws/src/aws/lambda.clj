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
                       (log/info "Getting next request" @requests)
                       (when @requests
                         (let [request @requests]
                           (reset! requests nil)
                           request)))
       :send-response (fn [_ctx response]
                        (log/info "Sending response" response)
                        (with-open [o (io/writer output)]
                          (.write o (util/to-json response))))))))

(comment
  (def ctx {})
  (def handler (fn []))
  (gen-class
   :name "lambda.Handler"
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler])
  (def -handleRequest (java-request-handler ctx handler :filters [])))
