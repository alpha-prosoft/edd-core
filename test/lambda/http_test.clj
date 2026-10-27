(ns lambda.http-test
  (:require [clojure.test :refer [deftest is]]
            [lambda.codec :as codec]
            [lambda.http :as http]
            [lambda.util :as util]))

(deftest test-parse-query
  (let [query-string "address[street]=Main&address[city]=New York"]
    (is (= {:address {:street "Main"
                      :city "New York"}}
           (http/parse-query-string query-string))))
  (let [query-string "address[street]=:main&address[city]=New York&address[zip][code]=4210"]
    (is (= {:address {:street :main
                      :city "New York"
                      :zip {:code "4210"}}}
           (http/parse-query-string query-string)))))

(defn truncate-response [res]
  (update res :headers select-keys ["Content-Type" "Content-Encoding"]))

(deftest test-to-api-no-gzip
  (let [res
        (http/response {} {} {:message ["hello" :world]})]
    (is (= {:statusCode 200
            :headers
            {"Content-Type" "application/json"}
            :isBase64Encoded false
            :body "{\"message\":[\"hello\",\":world\"]}"}
           (truncate-response res)))))

(deftest test-to-api-gzip
  (let [res
        (http/response {}
                       {:headers {:Accept-Encoding "Foo; Gzip; Bar"}}
                       {:message ["hello" :world]})

        expected-body
        "H4sIAAAAAAAA/6tWyk0tLk5MT1WyilbKSM3JyVfSUbIqzy/KSVGKrQUA7MSEOh4AAAA="

        decoded
        (-> expected-body
            codec/string->bytes
            codec/base64->bytes
            codec/gzip->bytes
            codec/bytes->string
            util/to-edn)]

    (is (= {:statusCode 200
            :headers
            {"Content-Type" "application/json"
             "Content-Encoding" "gzip"}
            :isBase64Encoded true
            :body expected-body}
           (truncate-response res)))

    (is (= {:message ["hello" :world]} decoded))))
