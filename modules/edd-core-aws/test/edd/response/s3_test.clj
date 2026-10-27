(ns edd.response.s3-test
  (:require [clojure.test :refer [deftest is testing]]
            [edd.response.cache :as cache]
            [edd.response.s3 :as sut]
            [lambda.util :as util]
            [sdk.aws.s3 :as s3])
  (:import (java.io ByteArrayInputStream)))

(def request-id #uuid "1111b7b5-9f50-4dc4-86d1-2e4fe1f6d491")

(def ctx
  (sut/register {:aws                    {:account-id "11111111111"}
                 :environment-name-lower "local"
                 :service-name           :local-test
                 :request-id             request-id
                 :breadcrumbs            [0 2]}))

(defn- object-store
  "sdk.aws.s3 put-object / get-object over an atom."
  [objects]
  {:put (fn [_ctx object]
          (swap! objects assoc
                 (select-keys (:s3 (update-in object [:s3 :object] dissoc :content)) [:bucket :object])
                 (get-in object [:s3 :object :content]))
          {:status 200})
   :get (fn [_ctx reference]
          (ByteArrayInputStream. (.getBytes ^String (get @objects (:s3 reference)) "UTF-8")))})

(deftest stores-and-fetches-a-part-test
  (let [objects
        (atom {})

        {:keys [put get]}
        (object-store objects)

        part
        {:service :local-test
         :resp    {:events  [{:id request-id}]
                   :effects [{:service :other-svc :commands [{:cmd-id :do :id request-id}]}]}}]
    (with-redefs [s3/put-object put
                  s3/get-object get]
      (let [reference
            (cache/store! ctx part)

            part-reference
            (cache/store! ctx (assoc part :idx 3))]
        (testing "bucket and key as before the cache became optional"
          (is
           (= {:s3 {:bucket {:name "11111111111-local-sqs"}
                    :object {:key (str "response/" request-id "/0-2/local-test.json")}}}
              reference))

          (is
           (= (str "response/" request-id "/0-2/local-test-part.3.json")
              (get-in part-reference [:s3 :object :key]))))

        (testing "the stored object is what the router reads"
          (is
           (= part
              (util/to-edn (clojure.core/get @objects (:s3 reference)))))

          (is
           (= part
              (cache/resolve-record ctx reference))))

        (testing "a record that is no reference is left alone"
          (is
           (= part
              (cache/resolve-record ctx part))))))))

(deftest failing-store-fails-the-command-test
  (with-redefs [s3/put-object (fn [_ctx _object]
                                {:error {:status 500}})]
    (is
     (thrown-with-msg? clojure.lang.ExceptionInfo #"Error caching response"
                       (cache/store! ctx {:service :local-test :resp {:events [] :effects []}})))))
