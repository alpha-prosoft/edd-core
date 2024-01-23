(ns aws.dynamodb-it
  (:require [clojure.test :refer [deftest testing is]]
            [lambda.util :as util]
            [aws.dynamodb :as ddb]
            [clojure.string :as str]
            [edd.dal :as dal]
            [edd.dynamodb.event-store :as event-store]
            [lambda.uuid :as uuid]))

(def ctx
  (-> {:elastic-search         {:url (util/get-env "IndexDomainEndpoint")}
       :service-name           "test-source"
       :environment-name-lower "pipeline"
       :meta                   {:realm :test}
       :aws                    {:region               "eu-west-1"
                                :aws-access-key-id     (util/get-env "AWS_ACCESS_KEY_ID")
                                :aws-secret-access-key (util/get-env "AWS_SECRET_ACCESS_KEY")
                                :aws-session-token     (util/get-env "AWS_SESSION_TOKEN")}}
      (event-store/register)))

(deftest test-list-tables
  (is (= ["pipeline-main-test-effect-store-ddb"
          "pipeline-main-test-event-store-ddb"
          "pipeline-main-test-identity-store-ddb"
          "pipeline-main-test-request-log-ddb"
          "pipeline-main-test-response-log-ddb"]
         (->> (ddb/list-tables ctx)
              (:TableNames)
              (filter #(str/includes? % "pipeline-main-test"))
              (map #(str/replace % #".*-svc-" ""))))))

(deftest store-results-test
  (let [request-id (uuid/gen)
        agg-id (uuid/gen)
        effect-id (uuid/gen)
        interaction-id (uuid/gen)
        invocation-id (uuid/gen)
        ctx (assoc ctx
                   :request-id             request-id
                   :interaction-id         interaction-id
                   :breadcrumbs            [0]
                   :invocation-id          invocation-id)
        id-val (str "iden-" (uuid/gen))
        identity {:identity id-val
                  :id       agg-id}
        event {:event-id  :e1
               :event-seq 1
               :id        agg-id}
        command {:service  :test-svc
                 :breadcrumbs [0 1]
                 :commands [{:cmd-id :cmd-test
                             :id     agg-id}]}]
    (with-redefs [uuid/gen (fn [] effect-id)]
      (dal/store-results (assoc ctx
                                :resp {:events     [event]
                                       :effects    [command]
                                       :sequences  []
                                       :identities [identity]})))

    (is (= {:Item {:Data          {:S (util/to-json event)}
                   :EventSeq      {:N "1"}
                   :AggregateId   {:S agg-id}
                   :InteractionId {:S interaction-id}
                   :Breadcrumbs {:S "0"},
                   :InvocationId {:S invocation-id}
                   :ItemType      {:S :event}
                   :RequestId     {:S request-id}
                   :Service       {:S :test-source}}}
           (ddb/make-request
            (assoc ctx :action "GetItem"
                   :body {:Key       {:AggregateId
                                      {:S agg-id}
                                      :EventSeq
                                      {:N "1"}}
                          :TableName (event-store/table-name ctx :event-store)}))))
    (is (= {:Item {:Data          {:S (util/to-json (assoc command
                                                           :request-id request-id
                                                           :interaction-id interaction-id))}
                   :Id            {:S (event-store/create-effect-id
                                       request-id
                                       (:breadcrumbs command))}
                   :InteractionId {:S interaction-id}
                   :ItemType      {:S :effect}
                   :RequestId     {:S request-id}
                   :Breadcrumbs {:S "0"},
                   :InvocationId {:S invocation-id}
                   :TargetService {:S :test-svc}
                   :Service       {:S :test-source}}}
           (ddb/make-request
            (assoc ctx :action "GetItem"
                   :body {:Key       {:Id {:S (event-store/create-effect-id
                                               request-id
                                               (:breadcrumbs command))}}
                          :TableName (event-store/table-name ctx :effect-store)}))))
    (is (= {:Item {:Data          {:S (util/to-json identity)}
                   :AggregateId   {:S agg-id}
                   :Id            {:S (str "test-source/" id-val)}
                   :InteractionId {:S interaction-id}
                   :ItemType      {:S :identity}
                   :RequestId     {:S request-id}
                   :Breadcrumbs {:S "0"},
                   :InvocationId {:S invocation-id}
                   :Service       {:S :test-source}}}
           (ddb/make-request
            (assoc ctx :action "GetItem"
                   :body {:Key       {:Id {:S (str "test-source/" id-val)}}
                          :TableName (event-store/table-name ctx :identity-store)}))))))
