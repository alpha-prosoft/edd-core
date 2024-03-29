(ns lambda.aes-test
  (:require [clojure.test :refer [deftest is testing]]
            [lambda.aes :as aes]
            [lambda.util :as util]))

(deftest test-decrypt
  (is (= "a\n"
         (aes/decrypt "U2FsdGVkX19KJlvYRICc9tWdHMAo3tWvPbRFqF9proQ="
                      "BFJN4gx80sZunDT9hDLLLA=="
                      "xEgLZisyV2bTh/QrzuVHMA=="))))

(deftest load-config
  (let [env {"CustomConfig" "{}"
             "ConfigurationContext" "xTKVqrYHvOjR1NeoAZ4z0Q==:2WJe1eIGVT3/SItftl0MuA=="}]
    (with-redefs [util/get-env (fn [% & [_default]]
                                 (get env %))]
      (is (= {:auth {:client-id     "1gof0uh5h7pcqat29r65ht1kmf"
                     :client-secret "ctraumfqr6t2i1uhojppks044t9eskmg7tfsn2almacgc1pebl"
                     :aud "1gof0uh5h7pcqat29r65ht1kmf",
                     :iss "https://cognito-idp..amazonaws.com/eu-central-1_0c2u3Gtmg"
                     :user-pool-id  "eu-central-1_0c2u3Gtmg"}
              :db   {:password "mypostgres"
                     :username "postgres"}
              :svc  {:password "AA33test-svc"
                     :username "test-svc@internal"}}
             (util/load-config "secret-enc.json"))))))
