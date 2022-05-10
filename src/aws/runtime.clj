(ns aws.runtime
  (:require [lambda.util :as util]
            [lambda.core :as core]
            [aws.aws :as aws]
            [aws.ctx :as aws-ctx]
            [lambda.uuid :as uuid]
            [clojure.tools.logging :as log]))

(defn aws->send-response
  [ctx response]
  (log/info "Send response " response)
  (let [exception (if (vector? response)
                    (-> (filter #(contains? % :exception) response)
                        (first))
                    (:exception response))] ()
       (if exception
         (aws/send-error ctx response)
         (aws/send-success ctx response))))

(defn loop-runtime
  [ctx handler & {:keys [filters next-request send-response]
                  :or   {filters     []
                         send-response aws->send-response}}]
  (util/log-startup)
  (binding [util/*cache* (atom {})]
    (let [ctx (-> ctx
                  (core/init-filters filters))]
      (loop [i 0
             next (next-request 0)]
        (let [{:keys [body] :as request} next]
          (when body
            (when (not= body :skip)
              (util/d-time
               (str "Handling next request: " i)
               (core/handle-request
                (-> ctx
                    (assoc :request request))
                body
                :handler handler
                :filters filters
                :send-response send-response
                :invocation-id (-> request
                                   :invocation-id))))
            (recur (inc i)
                   (next-request (inc i)))))))))

(defn lambda-custom-runtime
  [ctx handler & {:keys [filters]
                  :or   {filters     []}}]
  (loop-runtime (-> ctx
                    (aws-ctx/init))
                handler
                :filters filters
                :next-request (fn [_i]
                                (let [{:keys [error body] :as request} (aws/get-next-request)]
                                  (if-not error
                                    {:body (util/to-edn body)
                                     :invocation-id  (-> request
                                                         :headers
                                                         :lambda-runtime-aws-request-id
                                                         uuid/parse)}

                                    (do (log/error error)
                                        :skip))))))

(def start lambda-custom-runtime)

(defn form-invocation-number
  [i]
  (format "00000000-0000-0000-0000-%012d" i))

(defn lambda-requests
  [ctx handler requests
   & {:keys [filters]
      :or   {filters     []}}]
  (util/log-startup)
  (let [requests (atom requests)]
    (loop-runtime (-> ctx
                      (aws-ctx/init))
                  handler
                  :filters filters
                  :next-request (fn [i]
                                  (let [next (first @requests)]
                                    (swap! requests rest)
                                    {:body next
                                     :invocation-id (form-invocation-number i)})))))





