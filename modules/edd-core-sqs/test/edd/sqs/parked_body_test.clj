(ns edd.sqs.parked-body-test
  (:require [clojure.test :refer [deftest testing is]]
            [edd.core :as edd]
            [edd.el.cmd :as el-cmd]
            [edd.sqs.distributor :as sqs-distributor]
            [aws.lambda :as core]
            [lambda.test.fixture.core :refer [mock-core]]
            [lambda.util :as util]
            [lambda.uuid :as uuid]
            [sdk.aws.common :as common]))

(def interaction-id #uuid "0000b7b5-9f50-4dc4-86d1-2e4fe1f6d491")

(def request-id #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491")

(defn sqs-commands
  [body]
  {:Records
   [{:md5OfBody
     "fda65eb3c65bf08466667a54227de6ca",
     :eventSourceARN
     "arn:aws:sqs:eu-central-1:111111111:queue-name",
     :awsRegion         "eu-central-1",
     :messageId
     "f0153036-04c9-4afd-af00-d085c49da408",
     :eventSource       "aws:sqs",
     :messageAttributes {},
     :body              (util/to-json body)
     :receiptHandle
     "AQEBrvNGAn733odb9JN0X1N4YMzCVLYWXCCFjPx3YycpCSMaGjKyZxrbwZkz9CLtWMGVDwyJv/5sdS9N4epHvmexQVe8+lSfsGwtqFtN1zbKRjItRMO//BrZkp29BZ1LQv3zOAfCc4JJBGEINfaOpyo41Ajvqx0Dq0MwcYs8UR3RAH5kcILtsUD5NrdbR0sF0db8HmHNCZ7B875xm0dF5IB4WffEB+xH4vwoAEx8HuaBAoL2SOfUDT7+40uS8K3K+UZlzvKqOq9tfj7voCv+6Bi4e5u13S/z50e2YjHZZ00bNe4CBwNzUcDjZTtVIL/5cHEHLm8Rw8+wPwLYRkvsRt76j4M0DC7QObkgw8ixtnkBGmb5cnsXWSYZ8TraVPZkb00y1/30zl0s96VoEUSJ7qd40tBgUzSvwOcjoBOozC4AYH4=",
     :attributes
     {:ApproximateReceiveCount "1",
      :SentTimestamp           "1582094349942",
      :SenderId                "AIDAIZRJAGH76OE2BC64E",
      :ApproximateFirstReceiveTimestamp
      "1582094349946"}}]})

(deftest test-sqs-commands-in-bucket
  (testing "When we have s3 as source we shold download and pass it on"
    (let [cmd-log (atom [])
          payload {:request-id (uuid/gen)
                   :interaction-id (uuid/gen)
                   :commands [{:cmd-id :capitano-cmd}]
                   :breadcrumbs [0]}]
      (with-redefs [common/create-date (fn [] "20200426T061823Z")
                    el-cmd/handle-commands (fn [_ctx cmds]
                                             (swap! cmd-log conj cmds))]
        (let [bucket "sqs-s3-bucket"
              key (str "test/2021-12-27/"
                       interaction-id
                       "/"
                       request-id
                       ".limedocu.txt")]
          (mock-core
           :invocations [(util/to-json (sqs-commands {:s3 {:bucket {:name bucket}
                                                           :object {:key key}}}))]
           :requests [{:get  (str "https://"
                                  bucket
                                  ".s3.eu-central-1.amazonaws.com/"
                                  key)
                       :body (char-array (util/to-json
                                          payload))}]
           (core/lambda-custom-runtime
            (sqs-distributor/register {})
            edd/handler
            :filters [])
           (is (= [payload]
                  @cmd-log))))))))
