(ns edd.view-store.impl.elastic.common)

(def default-size 50)

(defn parse
  ; TODO parse => build-filter
  [op->filter-builder filter-spec]
  (let [[fst & rst] filter-spec]
    (if (vector? fst)
      (recur op->filter-builder fst)
      (let [builder-fn (get op->filter-builder fst)]
        (apply builder-fn op->filter-builder rst)))))
