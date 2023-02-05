(ns tryion.db.test-data
  (:require [tryion.location :as location]
            [tryion.asset :as asset]
            [tryion.booking :as booking]
            [tryion.db.db :as db]
            [java-time.api :as jtime]
            [taoensso.timbre :as timbre]))

(def test-retool-user-id "tryion.db.test-data")
(def test-customer-external-id "customer1")

(defn create-test-data
  "WARNING: Some portions (identified in the source code) of this fn are not idempotent."
  [conn]
  (location/add-location! conn location/test-location-id "Test 01")
  (asset/add-style! conn "cinderella")

  (with-bindings {#'location/*location-id* location/test-location-id}

    (timbre/warn "Loading asset transactions (Not idempotent)")
    (let [asset-txs (concat
                      (asset/add-asset conn "cinderella" "cinderella-gold-s")
                      (asset/add-asset conn "cinderella" "cinderella-silver-s")
                      (asset/add-asset conn "cinderella" "cinderella-gold-xl" 2))]
      (db/transact conn test-retool-user-id asset-txs))

    (timbre/warn "Loading booking transactions (Not idempotent)")
    (let [occasion-date (jtime/with-zone (jtime/zoned-date-time 2022 1 5 13) "America/New_York")
          assets        [[:asset/sku "cinderella-gold-s-001"]
                         [:asset/sku "cinderella-silver-s-001"]]
          booking-tx    (booking/add-booking test-customer-external-id
                                             occasion-date
                                             assets)]
      (db/transact conn test-retool-user-id booking-tx))))
