(ns edd.test.fixture.runtime
  "Runs a service on the in-memory runtime (lambda.runtime.memory) with the
   in-memory distribution of edd.test.fixture.dal/ctx, inside with-mock-dal:
   API requests as a given user, queue records, and what reached the router."
  (:require [edd.core :as edd]
            [edd.distribution.memory :as memory-distribution]
            [edd.test.fixture.dal :as mock]
            [lambda.runtime.memory :as runtime]
            [lambda.util :as util]))

(def user
  {:id    "john.smith@example.com"
   :email "john.smith@example.com"
   :roles [:group-1 :group-3 :group-2]
   :role  :group-1
   :realm :test})

(defn start
  "Runtime ctx of the service ctx, built from edd.test.fixture.dal/ctx.
   Options: :user of its API requests, :handler (default edd.core/handler),
   :filters, runtime filters. Invocations get :invocation-id 0."
  [ctx & {:keys [handler filters]
          api-user :user
          :or {handler edd/handler
               filters []
               api-user user}}]
  (let [authenticated
        (edd/reg-feature ctx :edd.test/authentication
                         :resolve-user (fn [_ctx] api-user))]
    (runtime/start (assoc authenticated :invocation-id 0)
                   handler
                   :filters filters)))

(defn post
  "Posts the edd request to the API, returns {:status :body} with the parsed
   body."
  [runtime-ctx request]
  (let [response
        (runtime/handle-http runtime-ctx {:path       "/"
                                          :httpMethod "POST"
                                          :headers    {}
                                          :body       (util/to-json request)})]
    {:status (:statusCode response)
     :body   (util/to-edn (:body response))}))

(defn handle-records
  "Handles the edd requests as one batch of queue records, returns
   {:responses :redeliver?}."
  [runtime-ctx requests]
  (runtime/handle-records runtime-ctx
                          (mapv #(hash-map :body (util/to-json %)) requests)))

(defn routed
  "Records the service sent to the router."
  []
  (map #(get-in % [:Records 0])
       (mapcat val (memory-distribution/published mock/ctx))))
