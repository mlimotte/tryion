(ns tryion.asset
  (:require [clojure.spec.alpha :as s]
            [datomic.client.api :as d]
            [tryion.db.functions]
            [tryion.db.util :as db-util]
            [tryion.common.lang :as lang]
            [tryion.location :as location]))

(def sku-re #".*-(\d+)$")

;(s/def ::vendor-item-code tryion.db.functions/valid-sku?)
(s/def ::vendor-item-code string?)
(s/def ::sku-id int?)

(defn add-style!
  [conn style-id]
  ; Style has no attributes other than a Vendor specific style id.
  (db-util/get-id
    (d/transact conn {:tx-data [{:db/id    style-id
                                 :style/id style-id}]})))

(defn get-by-sku
  [db sku pull-expr]
  (->> (d/q '[:find (pull ?e pull-expr)
              :in $ ?sku pull-expr
              :where [?e :asset/sku ?sku]]
            db sku pull-expr)
       (map first)))

(defn get-vendor-items
  [db vendor-item-code pull-expr]
  (->> (d/q '[:find (pull ?e pull-expr)
              :in $ ?vendor-item-code pull-expr
              :where [?e :asset/vendor-item-code ?vendor-item-code]]
            db vendor-item-code pull-expr)
       (map first)
       (sort-by #(or (:asset/sku %) (:db/id %)))))

(defn extract-uniq-id-from-sku
  [s]
  (some->> s (re-find sku-re) last))

(s/fdef mk-sku
  :args (s/cat :vendor-item-code ::vendor-item-code :sku-id ::sku-id))
(defn mk-sku
  [vendor-item-code sku-id]
  (format "%s-%03d" vendor-item-code sku-id))

(defn add-asset
  ([conn style-id vendor-item-code]
   (add-asset conn style-id vendor-item-code 1))
  ([conn style-id vendor-item-code qty]
   ;; asset/sku has "value" uniqueness, so the txn may fail in the case of a race
   ;; condition while checking last-sku-id.  The caller should RETRY in this case.
   (let [last-sku-id (or (some-> (get-vendor-items (d/db conn) vendor-item-code [:asset/sku])
                                 last
                                 :asset/sku
                                 extract-uniq-id-from-sku
                                 lang/as-long)
                         0)]
     (for [i (range 1 (inc qty))]
       {:asset/style            [:style/id style-id]
        :asset/store            [:store/location-id (location/location)]
        :asset/vendor-item-code vendor-item-code
        :asset/sku              (mk-sku vendor-item-code (+ last-sku-id i))
        :asset/status           :asset-status/ready
        :asset/booking-count    0}))))
