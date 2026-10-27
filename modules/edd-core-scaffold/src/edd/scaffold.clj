(ns edd.scaffold
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net JarURLConnection URL]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

(def template-root "edd/scaffold/template/")

(def placeholder "projectname")

(def runtimes
  [{:id "lambda"
    :description "AWS Lambda (Java runtime, SnapStart)"
    :libs ["edd-java-lambda-runtime"]}
   {:id "undertow"
    :description "HTTP server (Undertow)"
    :libs ["edd-core-undertow"]}])

(def event-stores
  [{:id "dynamodb"
    :description "DynamoDB"
    :ns "edd.dynamodb.event-store"}
   {:id "postgres"
    :description "PostgreSQL"
    :ns "edd.postgres.event-store"}
   {:id "memory"
    :description "In-memory (tests and local experiments)"
    :ns "edd.memory.event-store"}])

(def view-stores
  [{:id "s3"
    :description "S3"
    :ns "edd.s3.view-store"}
   {:id "postgres"
    :description "PostgreSQL (requires postgres event store)"
    :ns "edd.view-store.postgres.view-store"
    :libs ["edd-core-view-store-postgres"]
    :requires-event-store "postgres"}
   {:id "elastic"
    :description "OpenSearch / Elasticsearch"
    :ns "edd.elastic.view-store"
    :libs ["edd-core-opensearch"]}
   {:id "memory"
    :description "In-memory (tests and local experiments)"
    :ns "edd.memory.view-store"}])

(def distributors
  [{:id "sqs"
    :description "Amazon SQS"
    :ns "edd.sqs.distributor"
    :libs ["edd-core-sqs"]}
   {:id "kafka"
    :description "Apache Kafka (Undertow only)"
    :ns "edd.kafka.distributor"
    :libs ["edd-core-kafka"]
    :forbids-runtime "lambda"}])

(def deploys
  [{:id "ansible"
    :description "alpha-deploy-lib Lambda deployment (ansible/deploy)"
    :requires-runtime "lambda"}
   {:id "none"
    :description "No deployment files"}])

(def base-libs
  ["edd-core" "edd-core-aws" "edd-core-client" "edd-core-security"])

(def text-extensions
  #{"clj" "cljc" "cljs" "edn" "md" "yml" "yaml" "sh" "json" "xml" "txt"})

(def usage
  "Usage: clojure -M -m edd.scaffold <project-name> [options]

Options:
  --runtime <ids>        lambda, undertow or both: lambda,undertow
  --event-store <id>     dynamodb, postgres or memory
  --view-store <id>      s3, postgres, elastic or memory
  --distributor <id>     sqs or kafka (kafka needs the undertow runtime)
  --deploy <id>          ansible (lambda only) or none
  --dir <path>           target directory (default: ./<project-name>)
  --version <version>    edd-core version for generated deps
  --group-id <group>     maven group of edd-core libs
  --edd-core-root <path> use a local edd-core checkout instead of maven
  --force                write into a non-empty directory

Missing options are asked interactively.")

(defn- by-id
  [options id]
  (first (filter #(= id (:id %)) options)))

(defn- parse-args
  [args]
  (loop [[arg & more] args
         parsed {}]
    (cond
      (nil? arg)
      parsed

      (= "--force" arg)
      (recur more (assoc parsed :force true))

      (#{"--help" "-h"} arg)
      (recur more (assoc parsed :help true))

      (str/starts-with? arg "--")
      (if (seq more)
        (recur (rest more) (assoc parsed (keyword (subs arg 2)) (first more)))
        (throw (ex-info (str "Missing value for " arg) {})))

      (:name parsed)
      (throw (ex-info (str "Unexpected argument " arg) {}))

      :else
      (recur more (assoc parsed :name arg)))))

(defn- split-ids
  [value]
  (vec (remove str/blank? (map str/trim (str/split value #",")))))

(defn- resolve-choice
  [options answer]
  (if-let [index (parse-long answer)]
    (:id (get options (dec index)))
    (:id (by-id options answer))))

(defn- ask!
  [{:keys [title options multiple]}]
  (println)
  (println (str title (when multiple " (comma separated for several)") ":"))
  (doseq [[index {:keys [id description]}] (map-indexed vector options)]
    (println (format "  %d) %-10s %s" (inc index) id description)))
  (print (format "Choose [%s]: " (:id (first options))))
  (flush)
  (let [answer
        (or (read-line)
            (throw (ex-info (str "No answer for " title "; pass it as an option") {})))

        answers
        (if (str/blank? answer)
          [(:id (first options))]
          (split-ids answer))

        resolved
        (mapv #(resolve-choice options %) answers)]
    (if (or (some nil? resolved)
            (and (not multiple) (not= 1 (count resolved))))
      (do
        (println "Invalid choice:" answer)
        (ask! {:title title :options options :multiple multiple}))
      resolved)))

(defn- allowed?
  [{:keys [runtime event-store]} {:keys [requires-runtime forbids-runtime requires-event-store]}]
  (let [runtime-ids
        (set runtime)]
    (and (or (nil? requires-runtime) (contains? runtime-ids requires-runtime))
         (not (contains? runtime-ids forbids-runtime))
         (or (nil? requires-event-store) (= requires-event-store event-store)))))

(defn- restriction
  [{:keys [requires-runtime forbids-runtime requires-event-store]}]
  (cond
    requires-runtime (str "requires runtime " requires-runtime)
    forbids-runtime (str "does not work with runtime " forbids-runtime)
    requires-event-store (str "requires event store " requires-event-store)))

(defn- choice-errors
  [opts label options selected]
  (for [id selected
        :let [option (by-id options id)]
        :when (or (nil? option) (not (allowed? opts option)))]
    (if option
      (str (str/capitalize label) " " id " " (restriction option))
      (str "Unknown " label " " id))))

(defn- choose
  [opts value {:keys [title options] :as prompt}]
  (let [available
        (filterv #(allowed? opts %) options)

        selected
        (cond
          value (split-ids value)
          (= 1 (count available)) [(:id (first available))]
          :else (ask! (assoc prompt :options available)))

        errors
        (choice-errors opts (str/lower-case title) options selected)]
    (when (seq errors)
      (throw (ex-info (str/join "\n" errors) {:errors (vec errors)})))
    selected))

(defn- validate!
  [{:keys [name runtime event-store view-store distributor deploy] :as opts}]
  (let [errors
        (concat
         (when-not (re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" (str name))
           [(str "Invalid project name '" name "': use lowercase letters, digits and dashes")])
         (when (empty? runtime)
           ["Unknown runtime []"])
         (choice-errors opts "runtime" runtimes runtime)
         (choice-errors opts "event store" event-stores [event-store])
         (choice-errors opts "view store" view-stores [view-store])
         (choice-errors opts "distributor" distributors [distributor])
         (choice-errors opts "deploy" deploys [deploy]))]
    (when (seq errors)
      (throw (ex-info (str/join "\n" errors) {:errors (vec errors)})))
    opts))

(defn- build-info
  []
  (let [resource
        (io/resource "edd/scaffold/version.edn")]
    (when resource
      (edn/read-string (slurp resource)))))

(defn- lib-coordinates
  [{:keys [version edd-core-root]} lib]
  (cond
    (and edd-core-root (= "edd-core" lib))
    {:local/root (.getCanonicalPath (io/file edd-core-root))}

    edd-core-root
    {:local/root (.getCanonicalPath (io/file edd-core-root "modules" lib))}

    :else
    {:mvn/version version}))

(defn selected-libs
  [{:keys [runtime view-store distributor]}]
  (let [runtime-libs
        (mapcat #(:libs (by-id runtimes %)) runtime)

        view-store-libs
        (:libs (by-id view-stores view-store))

        distributor-libs
        (:libs (by-id distributors distributor))]
    (distinct (concat base-libs runtime-libs view-store-libs distributor-libs))))

(def clojure-release
  "1.12.6")

(defn render-deps
  [{:keys [group-id] :as opts}]
  (let [edd-lines
        (for [lib (selected-libs opts)]
          (binding [*print-namespace-maps* false]
            (str (symbol group-id lib) " " (pr-str (lib-coordinates opts lib)))))

        lines
        (cons (str "org.clojure/clojure {:mvn/version \"" clojure-release "\"}") edd-lines)]
    (str ":deps {" (str/join "\n        " lines) "}")))

(defn- main-variant
  [{:keys [runtime]}]
  (let [ids
        (set runtime)]
    (cond
      (= ids #{"lambda" "undertow"}) "lambda-undertow"
      (ids "undertow") "undertow"
      :else "lambda")))

(defn- resource-url
  ^URL [path]
  (or (io/resource (str template-root path))
      (throw (ex-info (str "Missing template resource " path) {}))))

(defn- list-template-files
  [dir]
  (let [anchor
        (resource-url "base/deps.edn")]
    (case (.getProtocol anchor)
      "file"
      (let [root
            (io/file (.getParentFile (.getParentFile (io/file (.toURI anchor)))) dir)]
        (for [^File file (file-seq root)
              :when (.isFile file)]
          (str (.relativize (.toPath root) (.toPath file)))))

      "jar"
      (let [jar
            (.getJarFile ^JarURLConnection (.openConnection anchor))

            prefix
            (str template-root dir "/")]
        (for [entry (enumeration-seq (.entries jar))
              :let [entry-name (.getName ^java.util.jar.JarEntry entry)]
              :when (and (str/starts-with? entry-name prefix)
                         (not (str/ends-with? entry-name "/")))]
          (subs entry-name (count prefix)))))))

(defn- text-file?
  [path]
  (let [file-name
        (last (str/split path #"/"))

        extension
        (last (str/split file-name #"\."))]
    (or (str/starts-with? file-name ".")
        (= file-name "Dockerfile")
        (contains? text-extensions extension))))

(defn- namespace-replacements
  [{:keys [event-store view-store distributor]}]
  {"edd.memory.event-store" (:ns (by-id event-stores event-store))
   "edd.memory.view-store" (:ns (by-id view-stores view-store))
   "edd.sqs.distributor" (:ns (by-id distributors distributor))})

(defn- render-text
  [{:keys [name] :as opts} path content]
  (let [with-namespaces
        (reduce-kv str/replace content (namespace-replacements opts))

        with-deps
        (if (= path "deps.edn")
          (str/replace with-namespaces ":deps {}" (render-deps opts))
          with-namespaces)]
    (str/replace with-deps placeholder name)))

(defn- target-path
  [{:keys [name]} path]
  (str/replace path placeholder (str/replace name "-" "_")))

(defn- make-executable!
  [^File file]
  (Files/setPosixFilePermissions
   (.toPath file)
   (PosixFilePermissions/fromString "rwxr-xr-x")))

(defn- write-file!
  [opts ^File target-dir source-path target]
  (let [file
        (io/file target-dir (target-path opts target))

        url
        (resource-url source-path)]
    (io/make-parents file)
    (if (text-file? target)
      (spit file (render-text opts target (slurp url)))
      (with-open [in (io/input-stream url)]
        (io/copy in file)))
    (when (str/ends-with? target ".sh")
      (make-executable! file))
    (str (.relativize (.toPath target-dir) (.toPath file)))))

(defn- template-dir-entries
  [dir]
  (when (io/resource (str template-root dir))
    (for [path (list-template-files dir)]
      [(str dir "/" path) path])))

(defn- template-entries
  [{:keys [runtime deploy] :as opts}]
  (let [extra-dirs
        (concat runtime [(str "deploy-" deploy)])

        main
        [[(str "main/" (main-variant opts) ".clj") "src/projectname/main.clj"]]]
    (concat (template-dir-entries "base")
            (mapcat template-dir-entries extra-dirs)
            main)))

(defn- ensure-target-dir!
  [{:keys [force]} ^File dir]
  (when (and (.exists dir)
             (seq (.list dir))
             (not force))
    (throw (ex-info (str "Directory " dir " is not empty; use --force to write into it") {})))
  (.mkdirs dir)
  dir)

(defn generate!
  "Writes a project for opts into its target directory and returns
   the relative paths of the written files."
  [{:keys [name dir] :as opts}]
  (let [target-dir
        (ensure-target-dir! opts (io/file (or dir name)))]
    (doall
     (for [[source target] (template-entries opts)]
       (write-file! opts target-dir source target)))))

(defn resolve-options
  [args]
  (let [{:keys [runtime event-store view-store distributor deploy] :as parsed}
        (parse-args args)

        info
        (build-info)

        with-build
        (assoc parsed
               :group-id (or (:group-id parsed) (:group-id info) "com.alpha-prosoft")
               :version (or (:version parsed) (:version info)))

        with-runtime
        (assoc with-build
               :runtime (choose with-build runtime {:title "Runtime"
                                                    :options runtimes
                                                    :multiple true}))

        with-distributor
        (assoc with-runtime
               :distributor (first (choose with-runtime distributor {:title "Distributor"
                                                                     :options distributors})))

        with-event-store
        (assoc with-distributor
               :event-store (first (choose with-distributor event-store {:title "Event store"
                                                                         :options event-stores})))

        with-view-store
        (assoc with-event-store
               :view-store (first (choose with-event-store view-store {:title "View store"
                                                                       :options view-stores})))

        with-deploy
        (assoc with-view-store
               :deploy (first (choose with-view-store deploy {:title "Deploy"
                                                              :options deploys})))]
    (when-not (or (:version with-deploy) (:edd-core-root with-deploy))
      (throw (ex-info "Unknown edd-core version; pass --version or --edd-core-root" {})))
    (validate! with-deploy)))

(defn -main
  [& args]
  (try
    (let [{:keys [help name]}
          (parse-args args)]
      (if (or help (nil? name))
        (println usage)
        (let [resolved
              (resolve-options args)

              files
              (generate! resolved)]
          (println)
          (println "Created" (or (:dir resolved) name) "with" (count files) "files:"
                   (select-keys resolved [:runtime :distributor :event-store :view-store :deploy]))
          (println "Next: cd" (or (:dir resolved) name) "&& clojure -M:test:unit"))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      (System/exit 1))))
