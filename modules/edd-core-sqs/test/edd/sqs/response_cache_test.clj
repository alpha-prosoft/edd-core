(ns edd.sqs.response-cache-test
  "A service on the SQS distributor with the S3 response cache: what goes to
   S3 and to the router queue, for a request and for its retry."
  (:require [clojure.test :refer [deftest is testing]]
            [edd.core :as edd]
            [edd.el.ctx :as el-ctx]
            [edd.response.s3 :as response-cache]
            [edd.sqs.distributor :as distributor]
            [edd.test.fixture.dal :as mock]
            [edd.test.fixture.runtime :as runtime]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [sdk.aws.s3 :as s3]
            [sdk.aws.sqs :as sqs]))

(defn- service
  []
  (let [ctx
        (assoc mock/ctx
               :service-name :fan-svc
               :environment-name-lower "local"
               :aws {:region                "eu-west-1"
                     :account-id            "123"
                     :aws-access-key-id     "key"
                     :aws-secret-access-key "secret"
                     :aws-session-token     "token"})

        with-cache
        (response-cache/register (el-ctx/set-effect-partition-size (distributor/register ctx) 3))

        with-cmd
        (edd/reg-cmd with-cache :fan-out
                     (fn [_ctx cmd]
                       {:event-id :fanned-out
                        :id       (:id cmd)
                        :targets  (:targets cmd)}))]
    (edd/reg-event-fx with-cmd :fanned-out
                      (fn [_ctx event]
                        (for [target (:targets event)]
                          {:service  :other-svc
                           :commands [{:cmd-id :receive
                                       :id     target}]})))))

(deftest retry-sends-the-s3-references-again-test
  (mock/with-mock-dal
    (let [uploads
          (atom [])

          sent
          (atom [])

          request-id
          (uuid/gen)

          request
          {:request-id     request-id
           :interaction-id (uuid/gen)
           :commands       [{:cmd-id  :fan-out
                             :id      (uuid/gen)
                             :targets (vec (repeatedly 7 uuid/gen))}]}

          references
          (mapv (fn [part]
                  {:Records [{:s3 {:bucket {:name "123-local-sqs"}
                                   :object {:key (str "response/" request-id "/0/fan-svc-part." part ".json")}}}]})
                (range 3))]
      (with-redefs [s3/put-object (fn [_ctx object]
                                    (swap! uploads conj (get-in object [:s3 :object :key]))
                                    {:status 200})
                    sqs/sqs-publish (fn [{:keys [queue message]}]
                                      (swap! sent conj [queue (util/to-edn message)])
                                      {:success true})]
        (let [ctx
              (runtime/start (service))]
          (testing "the response is uploaded in 3 parts and the router queue gets the 3 references"
            (is
             (= 200
                (:status (runtime/post ctx request))))

            (is
             (= (mapv #(get-in % [:Records 0 :s3 :object :key]) references)
                @uploads))

            (is
             (= (mapv #(vector "local-glms-router-svc-response" %) references)
                @sent)))

          (testing "the retry uploads nothing and sends the same references again"
            (is
             (= 200
                (:status (runtime/post ctx request))))

            (is
             (= 3
                (count @uploads)))

            (is
             (= (mapv #(vector "local-glms-router-svc-response" %) (concat references references))
                @sent))))))))
