(ns edd.scaffold-test
  (:require [clojure.data.json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [edd.scaffold :as sut])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir
  []
  (str (Files/createTempDirectory "edd-new" (make-array FileAttribute 0))))

(defn- generate
  [opts]
  (let [dir
        (str (temp-dir) "/" (:name opts))

        resolved
        (merge {:group-id "com.example"
                :version "1.42"
                :dir dir
                :distributor "sqs"
                :deploy "none"}
               opts)]
    {:dir dir
     :files (set (sut/generate! resolved))}))

(deftest lambda-project-test
  (let [{:keys [dir files]}
        (generate {:name "order-svc"
                   :runtime ["lambda"]
                   :event-store "dynamodb"
                   :view-store "s3"
                   :deploy "ansible"})

        service
        (slurp (io/file dir "src/order_svc/service.clj"))

        main
        (slurp (io/file dir "src/order_svc/main.clj"))]

    (testing "namespace paths use underscores, content keeps dashes"
      (is
       (contains? files "src/order_svc/service.clj"))

      (is
       (str/includes? service "(ns order-svc.service"))

      (is
       (str/includes? service ":service-name :order-svc")))

    (testing "selected stores are wired in"
      (is
       (str/includes? service "[edd.dynamodb.event-store :as event-store]"))

      (is
       (str/includes? service "[edd.s3.view-store :as view-store]"))

      (is
       (str/includes? service "[edd.sqs.distributor :as distributor]")))

    (testing "security is wired by default"
      (is
       (str/includes? service "(security/register (module/register ctx))")))

    (testing "lambda runtime only"
      (is
       (str/includes? main "edd.java-lambda-runtime.core"))

      (is
       (not (str/includes? main "undertow")))

      (is
       (not (contains? files "test/order_svc/main_test.clj"))))

    (testing "deploy files only when asked for"
      (is
       (contains? files "ansible/deploy/deploy.yml")))

    (testing "deps point at the released edd-core libs"
      (is
       (= {'org.clojure/clojure {:mvn/version "1.12.6"}
           'com.example/edd-core {:mvn/version "1.42"}
           'com.example/edd-core-aws {:mvn/version "1.42"}
           'com.example/edd-core-client {:mvn/version "1.42"}
           'com.example/edd-core-security {:mvn/version "1.42"}
           'com.example/edd-java-lambda-runtime {:mvn/version "1.42"}
           'com.example/edd-core-sqs {:mvn/version "1.42"}}
          (:deps (read-string (slurp (io/file dir "deps.edn")))))))

    (testing "scripts stay executable"
      (is
       (.canExecute (io/file dir "format.sh"))))))

(deftest lambda-and-undertow-project-test
  (let [{:keys [dir files]}
        (generate {:name "order-svc"
                   :runtime ["lambda" "undertow"]
                   :event-store "postgres"
                   :view-store "postgres"})

        main
        (slurp (io/file dir "src/order_svc/main.clj"))]

    (is
     (str/includes? main "edd.java-lambda-runtime.core"))

    (is
     (str/includes? main "edd.undertow.core"))

    (is
     (contains? files "test/order_svc/main_test.clj"))

    (is
     (not (contains? files "ansible/deploy/deploy.yml")))

    (is
     (= #{'org.clojure/clojure
          'com.example/edd-core
          'com.example/edd-core-aws
          'com.example/edd-core-client
          'com.example/edd-core-security
          'com.example/edd-core-sqs
          'com.example/edd-java-lambda-runtime
          'com.example/edd-core-undertow
          'com.example/edd-core-view-store-postgres}
        (set (keys (:deps (read-string (slurp (io/file dir "deps.edn"))))))))))

(deftest kafka-project-test
  (let [{:keys [dir]}
        (generate {:name "order-svc"
                   :runtime ["undertow"]
                   :distributor "kafka"
                   :event-store "postgres"
                   :view-store "postgres"})

        service
        (slurp (io/file dir "src/order_svc/service.clj"))]

    (is
     (str/includes? service "[edd.kafka.distributor :as distributor]"))

    (is
     (contains? (set (keys (:deps (read-string (slurp (io/file dir "deps.edn"))))))
                'com.example/edd-core-kafka))))

(deftest postgres-distributor-project-test
  (let [{:keys [dir]}
        (generate {:name "order-svc"
                   :runtime ["undertow"]
                   :distributor "postgres"
                   :event-store "postgres"
                   :view-store "postgres"})

        service
        (slurp (io/file dir "src/order_svc/service.clj"))]

    (is
     (str/includes? service "[edd.postgres.distributor :as distributor]"))

    (is
     (str/includes? service "(distributor/register (flyway/register named))"))

    (is
     (= #{'org.clojure/clojure
          'com.example/edd-core
          'com.example/edd-core-aws
          'com.example/edd-core-client
          'com.example/edd-core-security
          'com.example/edd-core-undertow
          'com.example/edd-core-distributor-postgres
          'com.example/edd-core-flyway
          'com.example/edd-core-view-store-postgres}
        (set (keys (:deps (read-string (slurp (io/file dir "deps.edn"))))))))))

(deftest postgres-distributor-questions-test
  (with-out-str
    (with-in-str "2\n3\n\n"
      (is
       (= {:runtime ["undertow"]
           :distributor "postgres"
           :event-store "postgres"
           :view-store "s3"
           :deploy "none"}
          (select-keys (sut/resolve-options ["order-svc" "--version" "1.42"])
                       [:runtime :distributor :event-store :view-store :deploy]))))))

(deftest local-edd-core-root-test
  (let [{:keys [dir]}
        (generate {:name "order-svc"
                   :runtime ["undertow"]
                   :event-store "memory"
                   :view-store "memory"
                   :edd-core-root "../.."})

        deps
        (:deps (read-string (slurp (io/file dir "deps.edn"))))]

    (is
     (= (.getCanonicalPath (io/file "../.."))
        (get-in deps ['com.example/edd-core :local/root])))

    (is
     (= (.getCanonicalPath (io/file "../../modules/edd-core-undertow"))
        (get-in deps ['com.example/edd-core-undertow :local/root])))))

(deftest interactive-prompts-test
  (let [answered
        (atom nil)

        output
        (with-out-str
          (with-in-str "1,2\n2\npostgres\n1\n"
            (reset! answered
                    (sut/resolve-options ["order-svc" "--version" "1.42"]))))]

    (is
     (str/includes? output "Runtime"))

    (is
     (= {:runtime ["lambda" "undertow"]
         :distributor "sqs"
         :event-store "postgres"
         :view-store "postgres"
         :deploy "ansible"}
        (select-keys @answered [:runtime :distributor :event-store :view-store :deploy])))))

(deftest default-answers-test
  (with-out-str
    (with-in-str "\n\n\n\n"
      (is
       (= {:runtime ["lambda"]
           :distributor "sqs"
           :event-store "dynamodb"
           :view-store "s3"
           :deploy "ansible"}
          (select-keys (sut/resolve-options ["order-svc" "--version" "1.42"])
                       [:runtime :distributor :event-store :view-store :deploy]))))))

(deftest undertow-only-questions-test
  (with-out-str
    (with-in-str "2\n2\n\n\n"
      (is
       (= {:runtime ["undertow"]
           :distributor "kafka"
           :deploy "none"}
          (select-keys (sut/resolve-options ["order-svc" "--version" "1.42"])
                       [:runtime :distributor :deploy]))))))

(deftest definition-file-test
  (let [dir
        (temp-dir)

        file
        (str dir "/mono-svc.json")]
    (spit file "{\"name\": \"mono-svc\",
                 \"runtime\": [\"undertow\"],
                 \"distributor\": \"postgres\",
                 \"event-store\": \"postgres\",
                 \"view-store\": \"postgres\",
                 \"deploy\": \"none\"}")

    (testing "a definition answers every question"
      (is
       (= {:name "mono-svc"
           :runtime ["undertow"]
           :distributor "postgres"
           :event-store "postgres"
           :view-store "postgres"
           :deploy "none"}
          (select-keys (sut/resolve-options ["--config" file "--version" "1.42"])
                       [:name :runtime :distributor :event-store :view-store :deploy]))))

    (testing "the command line wins"
      (is
       (= "memory"
          (:view-store (sut/resolve-options ["--config" file "--version" "1.42"
                                             "--view-store" "memory"])))))

    (testing "unknown keys are rejected"
      (spit file "{\"name\": \"mono-svc\", \"runtimes\": [\"undertow\"]}")

      (is
       (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Unknown keys in .*: runtimes"
        (sut/resolve-options ["--config" file "--version" "1.42"]))))))

(deftest prompting-saves-the-definition-it-applies-test
  (let [file
        (str (temp-dir) "/order-svc.json")

        target
        (str (temp-dir) "/order-svc")]
    (with-out-str
      (with-in-str "2\n3\n\n"
        (sut/-main "order-svc" "--version" "1.42" "--save-config" file)))

    (is
     (= {:name "order-svc"
         :version "1.42"
         :runtime ["undertow"]
         :distributor "postgres"
         :event-store "postgres"
         :view-store "s3"
         :deploy "none"}
        (clojure.data.json/read-str (slurp file) :key-fn keyword)))

    (with-out-str
      (sut/-main "--config" file "--dir" target))

    (is
     (str/includes? (slurp (io/file target "src/order_svc/service.clj"))
                    "[edd.postgres.distributor :as distributor]"))))

(deftest validation-test
  (testing "postgres view store needs the postgres event store"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"View store postgres requires event store postgres"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "lambda"
                            "--event-store" "dynamodb"
                            "--view-store" "postgres"
                            "--deploy" "none"]))))

  (testing "kafka cannot feed lambda"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Distributor kafka does not work with runtime lambda"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "lambda"
                            "--distributor" "kafka"
                            "--event-store" "memory"
                            "--view-store" "memory"
                            "--deploy" "none"]))))

  (testing "postgres cannot feed lambda"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Distributor postgres does not work with runtime lambda"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "lambda"
                            "--distributor" "postgres"
                            "--event-store" "postgres"
                            "--view-store" "postgres"
                            "--deploy" "none"]))))

  (testing "postgres needs the postgres event store"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Event store memory does not work with distributor postgres"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "undertow"
                            "--distributor" "postgres"
                            "--event-store" "memory"
                            "--view-store" "memory"
                            "--deploy" "none"]))))

  (testing "ansible deploy is lambda only"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Deploy ansible requires runtime lambda"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "undertow"
                            "--distributor" "sqs"
                            "--event-store" "memory"
                            "--view-store" "memory"
                            "--deploy" "ansible"]))))

  (testing "unknown runtime"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Unknown runtime"
      (sut/resolve-options ["order-svc" "--version" "1.42"
                            "--runtime" "jetty"
                            "--event-store" "memory"
                            "--view-store" "memory"
                            "--deploy" "none"]))))

  (testing "invalid project name"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"Invalid project name"
      (sut/resolve-options ["Order_Svc" "--version" "1.42"
                            "--runtime" "lambda"
                            "--event-store" "memory"
                            "--view-store" "memory"
                            "--deploy" "none"]))))

  (testing "non-interactive run without an answer fails instead of guessing"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"No answer for Runtime"
      (with-out-str
        (with-in-str ""
          (sut/resolve-options ["order-svc" "--version" "1.42"])))))))

(deftest non-empty-target-test
  (let [dir
        (temp-dir)]
    (spit (io/file dir "existing.txt") "keep")

    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"is not empty"
      (sut/generate! {:name "order-svc"
                      :dir dir
                      :group-id "com.example"
                      :version "1.42"
                      :runtime ["lambda"]
                      :distributor "sqs"
                      :deploy "none"
                      :event-store "memory"
                      :view-store "memory"})))))
