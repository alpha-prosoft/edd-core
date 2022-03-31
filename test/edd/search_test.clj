(ns edd.search-test
  (:require [clojure.test :refer :all]
            [edd.view-store.impl.elastic.main :as view-store]))

(deftest sort-forming
  (is (= [{"a.keyword" {:order "asc"}}
          {"b.keyword" {:order "desc"}}
          {"c.number" {:order "asc"}}
          {"d.number" {:order "desc"}}]
         (view-store/form-sorting
          [:a :asc
           :b :desc
           :c :asc-number
           :d :desc-number]))))
