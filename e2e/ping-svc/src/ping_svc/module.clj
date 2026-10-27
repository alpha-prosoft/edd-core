(ns ping-svc.module
  (:require [ping-svc.item :as item]
            [ping-svc.ping :as ping]))

(defn register
  [ctx]
  (ping/register (item/register ctx)))
