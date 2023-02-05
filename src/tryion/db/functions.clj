(ns tryion.db.functions
  (:require [datomic.client.api :as d]))

(defn valid-sku?
  [s]
  (boolean (re-matches #".*-\d{3}" s)))

(defn inc-attr
  ; Copied from Datomic transactions
  "Transaction function that increments the value of entity's
  cardinality-1 attr by amount, treating a missing value as 0."
  [db entity attr amount]
  (let [m (d/pull db {:eid entity :selector [:db/id attr]})]
    [[:db/add (:db/id m) attr (+ (or (attr m) 0) amount)]]))
