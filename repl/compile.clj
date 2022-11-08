(ns compil
  (:require [clojure.java.io :as io]))

(let [build-id "'501'"
      new-lib (symbol "'com.alpha-prosoft/edd-core'")
      lib (symbol "local/'edd-core'")
      deps (read-string
            (slurp (io/file "deps.edn")))
      deps (if (get-in deps [:deps lib])
             (-> deps
                 (update-in [:deps]
                            (fn [%] (dissoc % lib)))
                 (assoc-in [:deps new-lib]
                           {:mvn/version (str "1." build-id)}))
             deps)
      aliases [:test]
      deps (reduce
            (fn [p alias]
              (if (get-in p [:aliases alias :extra-deps lib])
                (-> p
                    (update-in [:aliases alias :extra-deps]
                               (fn [%] (dissoc % lib)))
                    (assoc-in [:aliases alias :extra-deps new-lib]
                              {:mvn/version (str "1." build-id)}))
 		p))
            deps
            aliases)]
  (spit "deps.edn"
        (with-out-str (clojure.pprint/pprint deps))))
