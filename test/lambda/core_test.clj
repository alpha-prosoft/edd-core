(ns lambda.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [lambda.core :as core]
            [lambda.util :as util]
            [lambda.filters :as filters]))

(def ctx-filter
  {:init (fn [ctx]
           (assoc ctx :init-value "bla"))
   :condition (fn [{:keys [body]}]
                (contains? body :test))
   :handler   (fn [_ctx _filter-chain]
                {:resp "Bla"})})

(deftest test-apply-filter
  (let [resp (core/apply-filters
              {:body {:test "Yes"}}
              [ctx-filter])]
    (is (=  {:resp "Bla"}
            resp))))

(deftest test-init-filter
  (with-redefs [util/load-config (fn [_name] {})]
    (let [ctx (core/init-filters {}
                                 [ctx-filter
                                  filters/to-api
                                  filters/from-api])]
      (is (= true
             (:filter-initialized ctx)))
      (is (= "bla"
             (:init-value ctx))))))
