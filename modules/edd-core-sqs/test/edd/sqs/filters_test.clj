(ns edd.sqs.filters-test
  (:require [clojure.test :refer [deftest is]]
            [edd.sqs.filters :as sut]))

(defn- user-of
  [options record]
  (let [filled
        (atom nil)]
    ((sut/authenticate options)
     {:service-name :own-svc
      :record record}
     {:meta {:realm :test
             :user {:id "ann"}}}
     (fn [_ctx request]
       (reset! filled (get-in request [:meta :user]))))
    @filled))

(deftest own-records-run-as-the-service-test
  (is
   (= {:id "own-svc"
       :role :non-interactive}
      (user-of {} {:eventSource "aws:sqs"}))))

(deftest records-of-other-sources-are-left-alone-test
  (is
   (= {:id "ann"}
      (user-of {} {:eventSource "other:source"}))))

(deftest user-comes-from-the-given-function-test
  (is
   (= {:id "from-record"
       :role :non-interactive}
      (user-of {:resolve-user (fn [_ctx _request]
                                {:id "from-record"
                                 :role :non-interactive})}
               {:eventSource "aws:sqs"}))))
