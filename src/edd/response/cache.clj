(ns edd.response.cache
  "Optional cache of command responses on their way to the router, e.g. S3
   (edd.response.s3/register). With a cache a response that does not fit one
   router message (more effects than edd.el.ctx effect-partition-size, or
   bigger than the transport :max-message-size) is stored in parts and the
   router messages carry only references to them, so a big response never
   has to fit into memory or the transport as a whole; the references are
   kept with the stored response and sent again for a duplicate request.
   Smaller responses, and all responses without a cache, travel inline.")

(defn register
  "cache is {:store (fn [ctx {:keys [idx service resp]}]) reference
             :reference? (fn [record])
             :fetch (fn [ctx reference]) {:service .. :resp ..}}.
   :store throws when the part cannot be stored. The router registers the
   same cache to read the references."
  [ctx cache]
  (assoc ctx :edd/response-cache cache))

(defn registered?
  [ctx]
  (some? (:edd/response-cache ctx)))

(defn store!
  [ctx part]
  ((get-in ctx [:edd/response-cache :store]) ctx part))

(defn resolve-record
  "The cached response a router record refers to, else the record."
  [ctx record]
  (let [{:keys [reference? fetch]}
        (:edd/response-cache ctx)]
    (if (and reference? (reference? record))
      (fetch ctx record)
      record)))
