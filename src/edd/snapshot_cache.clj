(ns edd.snapshot-cache
  "Optional cache of aggregate snapshots in front of a view store, e.g. S3
   (edd.s3.view-store/register-snapshot-cache). View stores write every
   aggregate to it and read snapshots from it before their own storage.
   Without a registered cache every fn here is a no-op returning nil.")

(defn register
  "cache is {:store (fn [ctx aggregate])
             :fetch (fn [ctx id])
             :fetch-version (fn [ctx id version])}."
  [ctx cache]
  (assoc ctx :edd/snapshot-cache cache))

(defn registered?
  [ctx]
  (some? (:edd/snapshot-cache ctx)))

(defn store!
  [ctx aggregate]
  (when-let [store (get-in ctx [:edd/snapshot-cache :store])]
    (store ctx aggregate)))

(defn fetch
  [ctx id]
  (when-let [fetch-snapshot (get-in ctx [:edd/snapshot-cache :fetch])]
    (fetch-snapshot ctx id)))

(defn fetch-version
  [ctx id version]
  (when-let [fetch-snapshot-version (get-in ctx [:edd/snapshot-cache :fetch-version])]
    (fetch-snapshot-version ctx id version)))
