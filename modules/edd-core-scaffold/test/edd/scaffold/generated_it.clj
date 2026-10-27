(ns edd.scaffold.generated-it
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [edd.scaffold :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def combinations
  [{:runtime ["lambda"] :distributor "sqs" :event-store "dynamodb" :view-store "s3" :deploy "ansible"}
   {:runtime ["undertow"] :distributor "kafka" :event-store "memory" :view-store "elastic" :deploy "none"}
   {:runtime ["lambda" "undertow"] :distributor "sqs" :event-store "postgres" :view-store "postgres" :deploy "none"}])

(defn- sh!
  [dir & command]
  (let [{:keys [exit out err]}
        (apply shell/sh (concat command [:dir dir]))]
    (when-not (zero? exit)
      (println out err))
    exit))

(defn- generate
  [combination]
  (let [dir
        (str (Files/createTempDirectory "edd-new-it" (make-array FileAttribute 0)) "/sample-svc")]
    (sut/generate! (merge {:name "sample-svc"
                           :dir dir
                           :group-id "com.alpha-prosoft"
                           :edd-core-root "../.."}
                          combination))
    dir))

(deftest generated-projects-work-test
  (doseq [combination combinations]
    (testing (pr-str combination)
      (let [dir
            (generate combination)]

        (is
         (zero?
          (sh! dir "clojure" "-M:test:unit")))

        (is
         (zero?
          (sh! dir "clojure" "-M" "-e"
               "(binding [*compile-path* (doto \"target/classes\" (-> java.io.File. .mkdirs))] (compile 'sample-svc.main))")))

        (is
         (= (contains? (set (:runtime combination)) "lambda")
            (.exists (io/file dir "target/classes/lambda/Handler.class"))))))))
