(ns aws.lambda
  "AWS Lambda custom runtime. Decodes every invocation event itself: API
   Gateway requests go through lambda.http, SQS records are handled one by
   one, any other event (e.g. S3 notifications) reaches the filters as is."
  (:require [lambda.util :as util]
            [lambda.ctx :as lambda-ctx]
            [lambda.http :as http]
            [lambda.metrics :as metrics]
            [aws.aws :as aws]
            [lambda.request :as request]
            [lambda.uuid :as uuid]
            [aws.ctx :as aws-ctx]
            [lambda.logging]
            [lambda.runtime :as runtime]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn with-cache
  [f]
  (binding [util/*cache* (atom {})]
    (f)))

(defn get-loop
  "Extracting lambda looping as infinite loop to be able to mock it"
  []
  (range))

(defn- queue-event?
  [event]
  (= "aws:sqs" (get-in event [:Records 0 :eventSource])))

(defn handle-event
  "Handles one invocation event: {:response r :error? true} when it has to
   be reported to Lambda as failed (a queue record or other event failed)."
  [ctx event]
  (cond
    (http/request? event)
    {:response (http/handle ctx event)}

    (queue-event? event)
    (let [records
          (:Records event)

          responses
          (runtime/handle-records ctx records)]
      {:response responses
       :error? (runtime/complete-records! ctx records responses)})

    :else
    (let [response
          (runtime/distribute! ctx (runtime/handle ctx event))]
      {:response response
       :error? (runtime/failed? response)})))

(defn init
  [init-ctx handler opts]
  (runtime/start (aws-ctx/init (lambda-ctx/init init-ctx)) handler opts))

(defn- store-trace-headers!
  [invocation]
  (let [trace-header
        (get-in invocation [:headers :lambda-runtime-trace-id])]
    (swap! request/*request*
           assoc
           :trace-headers
           (cond-> {}
             trace-header (assoc "X-Amzn-Trace-Id" trace-header)))))

(defn lambda-custom-runtime
  "Options: :filters, runtime filters (see lambda.runtime)."
  [init-ctx handler & {:as opts}]
  (with-cache
    #(let [api
           (util/get-env "AWS_LAMBDA_RUNTIME_API")

           ctx
           (assoc (init init-ctx handler opts) :api api)]
       (metrics/start-metrics-publishing! ctx)
       (doseq [i (get-loop)]
         (let [invocation
               (aws/get-next-request api)

               invocation-id
               (get-in invocation [:headers :lambda-runtime-aws-request-id])

               invocation-ctx
               (assoc ctx :invocation-id (if (int? invocation-id)
                                           invocation-id
                                           (uuid/parse invocation-id)))]
           (util/d-time
            (str "Handling next request: " i ", invocation-id: " invocation-id)
            (runtime/with-invocation util/*cache* {:scoped true}
              (store-trace-headers! invocation)
              (let [{:keys [response error?]}
                    (handle-event invocation-ctx (:body invocation))]
                (if error?
                  (aws/send-loop-runtime-error invocation-ctx response)
                  (aws/send-loop-runtime-success invocation-ctx response))))))))))
