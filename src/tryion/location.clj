(ns tryion.location
  (:require [datomic.client.api :as d]
            [tryion.db.util :as db-util]))

(def test-location-id "test-01")
(def proto-location-id "atlanta-01")

(def ^:dynamic *location-id* nil)
(defn location
  []
  (or *location-id* test-location-id))

(defn add-location!
  [conn location-id name]
  (db-util/get-id
    (d/transact conn {:tx-data [{:db/id location-id
                                 :store/location-id location-id
                                 :store/name        name}]})))

(comment
  (add-location! conn test-location-id "Test 01")
  (add-location! conn proto-location-id "Atlanta 01"))
