(ns doc
  (:gen-class)
  (:require [clojure.java.io :as io]))

(defn read-schema
  "reads the schema file, returns list of forms"
  [filename]
  (let [eof    (Object.)
        reader (java.io.PushbackReader.
                (io/reader filename))]
    (take-while #(not= % eof)
                (repeatedly #(read reader false eof)))))

(defn is-doc?
  [s]
  (and (symbol? s)
       (= 'testing
          s)))

(defn eval-schema
  "walks the list of extracted forms and evals them in the global scope.
  Returns a map with evaluated `queries` and `commands` symbols"
  [forms]
  (loop [form (rest forms)
         resp []]
    (let [s (first form)]
      (println s)
      (cond
        (is-doc? s) (recur
                     (rest form)
                     (conj resp (second form)))))))

(defn -main
  [& args]
  (let [file (first args)]
    (clojure.pprint/pprint (-> file
                               read-schema
                               eval-schema))))

(-main "test/doc/edd/command_test.clj")
