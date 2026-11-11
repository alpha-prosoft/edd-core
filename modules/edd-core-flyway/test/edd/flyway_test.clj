(ns edd.flyway-test
  (:require [clojure.test :refer [deftest is]]
            [edd.ctx :as edd-ctx]))

(deftest realms-come-from-ctx-test
  (is
   (= [:prod]
      (edd-ctx/realms {})))

  (is
   (= [:main :test]
      (edd-ctx/realms {:realms ["main" :test]}))))

(deftest a-component-declares-its-migration-once-test
  (let [migration
        {:id :my/tables
         :location "classpath:my/migrations"}

        declared
        (edd-ctx/put-migration (edd-ctx/put-migration {} migration)
                               (assoc migration :location "classpath:my/other"))]

    (is
     (= [{:id :my/tables
          :location "classpath:my/other"}]
        (edd-ctx/get-migrations declared)))))
