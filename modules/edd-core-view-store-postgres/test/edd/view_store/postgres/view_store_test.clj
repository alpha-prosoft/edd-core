(ns edd.view-store.postgres.view-store-test
  "
  Mostly to ensure the target namespace compiles.
  "
  (:require
   [clojure.test :refer [deftest is]]
   [edd.view-store.postgres.view-store :as view-store]))

(deftest test-register
  (is (= {:view-store :postgres}
         (dissoc (view-store/register {}) :edd-core)))

  (is (= [:edd/postgres-view-store]
         (mapv :id (get-in (view-store/register {}) [:edd-core :migrations])))))
