(ns lambda.http-client-test
  (:require [clojure.test :refer :all]
            [lambda.http-client :as http-client]
            [lambda.util :as util]))

(deftest test-http-client
  (is (= {:connect-timeout 300
          :idle-timeout    5000
          :keepalive -1}
         (http-client/request->with-timeouts 5 {})))
  (is (= {:connect-timeout 400
          :idle-timeout    9000
          :keepalive -1}
         (http-client/request->with-timeouts 4 {})))
  (is (= {:connect-timeout 700
          :idle-timeout    21000
          :keepalive -1}
         (http-client/request->with-timeouts 3 {})))
  (is (= {:connect-timeout 1200
          :idle-timeout    41000
          :keepalive -1}
         (http-client/request->with-timeouts 2 {})))
  (is (= {:connect-timeout 1900
          :idle-timeout    69000
          :keepalive -1}
         (http-client/request->with-timeouts 1 {})))
  (is (= {:connect-timeout 2800
          :idle-timeout    105000
          :keepalive -1}
         (http-client/request->with-timeouts 0 {}))))

(deftest exception-test
  (let [ex (RuntimeException. "t1")]
    (is (= {:exception "t1"}
           (util/exception->response ex))))
  (let [ex (ex-info "t1" {:some "error"})]
    (is (= {:exception  {:some "error"}}
           (util/exception->response ex)))))
