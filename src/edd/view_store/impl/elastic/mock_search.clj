(ns edd.view-store.impl.elastic.mock-search
  (:require
   [clojure.string :as str]
   [edd.view-store.impl.elastic.common :as common]
   [edd.view-store.common :as view-store-common]

   [clojure.tools.logging :as log]))

(def default-elastic-store {:aggregate-store []})

(defn to-keywords
  [a]
  (cond
    (keyword? a) (to-keywords (name a))
    (vector? a) (vec
                 (reduce
                  (fn [v p]
                    (concat v (to-keywords p)))
                  []
                  a))
    :else (map
           keyword
           (remove
            empty?
            (str/split a #"\.")))))

(defn and-fn
  [ctx & r]
  (fn [%]
    (let [result (every?
                  (fn [p]
                    (let [rest-fn (common/parse ctx p)]
                      (rest-fn %)))
                  r)]
      result)))

(defn or-fn
  [mock & r]
  (fn [%]
    (let [result (some
                  (fn [p]
                    (let [rest-fn (common/parse mock p)]
                      (rest-fn %)))
                  r)]
      (if result
        result
        false))))

(defn eq-fn
  [_ & [a b]]
  (fn [%]
    (let [keys (to-keywords a)
          response (= (get-in % keys)
                      (case b
                        string? (str/trim b)
                        b))]

      response)))

(defn not-fn
  [mock & [rest]]
  (fn [%]
    (not
     (apply
      (common/parse mock rest) [%]))))

(defn in-fn
  [_ key & [values]]
  (fn [p]
    (let [keys (to-keywords key)
          value (get-in p keys)]
      (if (some
           #(= % value)
           values)
        true
        false))))
(defn exists-fn
  [_ key & [_values]]
  (fn [p]
    (let [keys (to-keywords key)]
      (not= (get-in p keys :nil) :nil))))

(def mock
  {:and    and-fn
   :or     or-fn
   :exists exists-fn
   :eq     eq-fn
   :not    not-fn
   :in     in-fn})

(defn search-fn
  [q p]
  (let [[_fields-key fields _value-key value] (:search q)]
    (if (some
         #(let [v (get-in p (to-keywords %) "")]
            (.contains v value))
         fields)
      true
      false)))

(defn field-to-kw-list
  [p]
  (cond
    (string? p) (map
                 keyword
                 (str/split p #"\."))
    (keyword? p) (map
                  keyword
                  (str/split (name p) #"\."))))

(defn select-fn
  [q %]
  (reduce
   (fn [v p]
     (assoc-in v p
               (get-in % p)))
   {}
   (map
    field-to-kw-list
    (get q :select []))))

(defn compare-as-number
  [a b]
  (let [num_a (if (number? a)
                a
                (Integer/parseInt a))
        num_b (if (number? b)
                b
                (Integer/parseInt b))]
    (compare num_a num_b)))

(defn compare-item
  [attrs a b]
  (log/info attrs)
  (let [sort (first attrs)
        attribute (first sort)
        order (second sort)
        value_a (get-in a attribute)
        value_b (get-in b attribute)]
    (log/info attribute)
    (log/info order)
    (log/info value_a)
    (log/info value_b)
    (cond
      (empty? attrs) 0
      (= value_a value_b) (compare-item
                           (rest attrs) a b)
      (= order :asc) (compare value_a value_b)
      (= order :desc) (- (compare value_a value_b))
      (= order :desc-number) (- (compare-as-number value_a value_b))
      (= order :asc-number) (compare-as-number value_a value_b))))

(defn sort-fn
  [q items]
  (sort
   (fn [a b]
     (let [attrs (mapv
                  (fn [[k v]]
                    [(to-keywords k) (keyword v)])
                  (partition 2 (:sort q)))]
       (compare-item attrs a b)))

   items))

(defn advanced-search-impl
  [_ctx query]
  {:pre [query]}
  (let [state (->> @(view-store-common/get-store)
                   (:aggregate-store))
        apply-filter (if (:filter query)
                       (common/parse mock (:filter query))
                       (fn [_%] true))
        apply-search (if (:search query)
                       (partial search-fn query)
                       (fn [_%] true))
        apply-select (if (:select query)
                       (partial select-fn query)
                       (fn [%] %))
        apply-sort (if (:sort query)
                     (partial sort-fn query)
                     (fn [%] %))
        hits (->> state
                  (filter apply-filter)
                  (filter apply-search)
                  (map apply-select)
                  (apply-sort)
                  (into []))
        to (+ (get query :from 0)
              (get query :size (count hits)))]
    {:total (count hits)
     :from  (get query :from 0)
     :size  (get query :size common/default-size)
     :hits  (subvec hits
                    (get query :from 0)
                    (if (> to (count hits))
                      (count hits)
                      to))}))
