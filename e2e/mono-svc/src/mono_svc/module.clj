(ns mono-svc.module
  (:require [mono-svc.chain :as chain]
            [mono-svc.item :as item]))

(defn register
  [ctx]
  (chain/register (item/register ctx)))
