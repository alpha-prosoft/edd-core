(ns projectname.item
  (:require [edd.common :as common]
            [edd.core :as edd]
            [projectname.schema :as schema]))

(def roles
  [:admin :non-interactive])

(defn create-item
  [_ctx {:keys [id attrs]}]
  {:event-id :item-created
   :id id
   :attrs attrs})

(defn item-created
  [agg {:keys [attrs]}]
  (assoc agg
         :name (:name attrs)
         :status :created))

(defn get-item
  [ctx query]
  (common/get-by-id ctx query))

(defn register
  [ctx]
  (let [with-commands
        (edd/reg-cmd ctx :create-item create-item
                     :consumes schema/CreateItemCmd
                     :auth {:role roles})

        with-events
        (edd/reg-event with-commands :item-created item-created)]
    (edd/reg-query with-events :get-item get-item
                   :consumes schema/GetItemQuery
                   :auth {:role roles})))
