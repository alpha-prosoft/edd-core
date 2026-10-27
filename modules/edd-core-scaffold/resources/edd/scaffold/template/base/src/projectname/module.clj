(ns projectname.module
  (:require [projectname.item :as item]))

(defn register
  [ctx]
  (item/register ctx))
