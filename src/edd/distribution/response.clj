(ns edd.distribution.response
  "Router payloads of a command response. A response travels in router
   messages {\"Records\":[{\"service\" .. \"resp\" {\"effects\" [..] \"events\" [..]}}]},
   split so every message fits the transport, or with a registered response
   cache (edd.response.cache) in references to the cached parts. Built when
   a command is stored; a duplicate request sends the stored references or
   rebuilds the payloads from the stored summary and the command store."
  (:require [clojure.string :as str]
            [edd.dal :as dal]
            [edd.distribution :as distribution]
            [edd.el.ctx :as el-ctx]
            [edd.response.cache :as cache]
            [lambda.request :as request]
            [lambda.util :as util]))

(def ^:private records-envelope
  (count "{\"Records\":[]}"))

(defn- payload-json
  [service-json events effects]
  (str "{\"service\":" service-json
       ",\"resp\":{\"events\":[" (str/join "," events)
       "],\"effects\":[" (str/join "," effects) "]}}"))

(defn- budget
  ^long [ctx service-json]
  (- (distribution/max-message-size ctx)
     (long records-envelope)
     (distribution/byte-size (payload-json service-json [] []))))

(defn- router-event
  [event]
  (select-keys event [:id :meta :request-id :interaction-id]))

(defn payloads
  "Payload JSON strings carrying the events and effects of a response, each
   within the transport message size. Events carry only what the router
   needs to notify their aggregates. Events come first and keep their
   order, as do effects; an item too big for any message gets its own."
  [ctx service {:keys [events effects]}]
  (let [service-json
        (util/to-json service)

        limit
        (budget ctx service-json)

        items
        (concat (map #(vector :events (util/to-json (router-event %))) events)
                (map #(vector :effects (util/to-json %)) effects))

        finish
        (fn [{:keys [events effects]}]
          (payload-json service-json events effects))]
    (loop [[[kind json :as item] & more] items
           current {:events [] :effects [] :size 0}
           done []]
      (cond
        (nil? item)
        (if (pos? (long (:size current)))
          (conj done (finish current))
          done)

        (and (pos? (long (:size current)))
             (> (+ (long (:size current)) 1 (distribution/byte-size json)) limit))
        (recur (cons item more) {:events [] :effects [] :size 0} (conj done (finish current)))

        :else
        (let [added
              (update current kind conj json)

              sized
              (update added :size + 1 (distribution/byte-size json))]
          (recur more sized done))))))

(defn queue!
  "Queues the payloads for distribution after the request is answered."
  [payload-jsons]
  (swap! request/*request* update :router-payloads (fnil into []) payload-jsons))

(defn- inline-payloads
  "The response as one inline payload when it has at most one part of
   effects and fits the transport message size, else nil."
  [ctx service {:keys [effects] :as resp}]
  (let [part-size
        (el-ctx/get-effect-partition-size ctx)]
    (when (<= (bounded-count (inc part-size) effects) part-size)
      (let [inline
            (payloads ctx service resp)

            fits?
            (fn [payload]
              (<= (+ (long records-envelope) (distribution/byte-size payload))
                  (distribution/max-message-size ctx)))]
        (when (and (<= (count inline) 1)
                   (every? fits? inline))
          inline)))))

(defn- cached-references
  "Stores the response in the cache, effect-partition-size effects per part
   and the events with the first one, one part at a time so only that part
   is serialized in memory. Returns the references."
  [ctx service {:keys [events effects]}]
  (let [parts
        (partition-all (el-ctx/get-effect-partition-size ctx) effects)

        parts
        (if (and (empty? parts) (seq events))
          [[]]
          parts)

        split?
        (some? (next parts))]
    (vec (map-indexed (fn [index part]
                        (cache/store! ctx (cond-> {:service service
                                                   :resp {:events (if (zero? (long index))
                                                                    (mapv router-event events)
                                                                    [])
                                                          :effects (vec part)}}
                                            split? (assoc :idx index))))
                      parts))))

(defn prepare
  "Router payloads of a response, built before it is stored:
   {:payloads [json ..]} inline, or with a registered cache
   {:payloads [reference json ..] :cache-result [reference ..]} for a
   response that does not fit one router message: more effects than
   edd.el.ctx effect-partition-size or bigger than the transport
   :max-message-size."
  [ctx resp]
  (let [service
        (:service-name ctx)]
    (if-not (cache/registered? ctx)
      {:payloads (payloads ctx service resp)}
      (if-let [inline (inline-payloads ctx service resp)]
        {:payloads inline}
        (let [references
              (cached-references ctx service resp)]
          {:payloads (mapv util/to-json references)
           :cache-result references})))))

(defn- stored-events
  "What the router needs of the events of a stored response: every event has
   the id of its command, which the summary keeps in :meta."
  [ctx summary]
  (when (pos? (long (:events summary 0)))
    (map (fn [id]
           {:id id
            :meta (:meta ctx)
            :request-id (:request-id ctx)
            :interaction-id (:interaction-id ctx)})
         (distinct (map :id (mapcat vals (:meta summary)))))))

(defn- stored-references
  "Cache references of a stored response; responses stored before 2.340
   keep a single reference as a map."
  [{:keys [cache-result]}]
  (if (map? cache-result)
    [cache-result]
    (seq cache-result)))

(defn stored-response
  "Events and effects of a stored response, rebuilt from its summary and
   the command store."
  [ctx summary]
  {:events (stored-events ctx summary)
   :effects (dal/get-response-effects ctx)})

(defn queue-stored-response!
  "A duplicate request sends its router payloads again, so effects of a
   request whose distribution failed are not lost: the cache references
   kept in the stored summary, else payloads rebuilt from the summary and
   the command store."
  [ctx summary]
  (queue! (if-let [references (stored-references summary)]
            (mapv util/to-json references)
            (payloads ctx (:service-name ctx) (stored-response ctx summary)))))

(defn records-body
  [payload-json]
  (str "{\"Records\":[" payload-json "]}"))
