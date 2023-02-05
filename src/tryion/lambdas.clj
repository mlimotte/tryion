(ns tryion.lambdas
  "This is a demonstration of a lambda"
  (:require [clojure.string :as string]
            [datomic.client.api :as d]
            [datomic.ion.starter.edn :as edn]
            [taoensso.timbre :as timbre]
            [tryion.system :refer [from-system]]
            [tryion.common.log :as log]))

(defn get-schema*
  ;; Copied from Ion Starter sample app
  "Returns a data representation of db schema."
  [db]
  (->> (d/pull db '{:eid 0 :selector [{:db.install/attribute [*]}]})
       :db.install/attribute
       (remove (fn [m] (string/starts-with? (namespace (:db/ident m)) "db")))
       (map #(update % :db/valueType :db/ident))
       (map #(update % :db/cardinality :db/ident))))

(defn get-schema
  ;; From the Cognitect ion-starter application
  "Lambda ion that returns database schema."
  [{:keys [input]}]
  (let [db (d/db (from-system :datomic-conn))]
    (-> (get-schema* db)
        edn/write-str)))

(defn write-log
  "Lambda ion that writes to the log"
  [payload]
  (timbre/warn "write log lambda" {:type-of-payload (type payload)})
  (log/httplog "write log lambda" {:payload payload})
  ;; Payloads are parsed from JSON, so we get an actual Map here.
  (let [db (d/db (from-system :datomic-conn))]
    (-> payload edn/write-str)))
