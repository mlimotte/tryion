(ns tryion.db.db
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [datomic.client.api :as d]
            [taoensso.timbre :as timbre]
            [datomic.ion.starter.utils :as dis-utils]
            [datomic.ion.starter.edn :as edn]
            [flatland.ordered.map :refer [ordered-map]]
            [tryion.common.lang :as lang]
            [tryion.common.gsheetsx :as gsx]
            ))

(def database-name "tryion")
(def schema-path "resources/datomic/schema.edn")
(def ion-config-resource-path "datomic/ion/config.edn")

(def schema-googlesheet {:spreadsheet-id "1c6uD4DlvhB4xiTXQLIEhKq_yUor-f00kN3RXKzotkdM"
                         :sheet-name     "datamodel"})

(defn transact
  "Synchronous transaction function that tags the tx with the retool-user-id"
  [conn retool-user-id tx-data]
  (d/transact conn {:tx-data (cons {:db/id                   "datomic.tx"
                                    :employee/retool-user-id retool-user-id}
                                   tx-data)}))

(defn config-data
  []
  (edn/edn-resource ion-config-resource-path))

(def get-client
  "Return a shared client based on values in `datomic/ion/config.edn`."
  (memoize
    (fn []
      (if-let [config (config-data)]
        (d/client config)
        (throw (RuntimeException. (format "Could not find resource file `%s`." ion-config-resource-path)))))))

(defn get-connection
  "Get shared connection."
  []
  (dis-utils/with-retry #(d/connect (get-client) {:db-name database-name})))

(defn mk-enums
  [record]
  (map keyword (string/split (:enum-values record) #"\n")))

(defn snapshot-schema
  "Load the google sheet and create the schema"
  [gsheet-service]
  (let [spreadsheet-id (:spreadsheet-id schema-googlesheet)
        _              (timbre/info "Loading schema from gsheet" {:spreadsheet-id spreadsheet-id})
        records        (gsx/read-single-table gsheet-service
                                              spreadsheet-id
                                              (:sheet-name schema-googlesheet)
                                              {:filter-fn :ident})
        eavs           (doall
                         (mapcat
                           (fn [record]
                             (let [[_ valtype star] (re-matches #":?([^\*]+)(\*?)" (:value-type record))
                                   enum?       (= valtype "ENUM")
                                   enum-values (if enum? (mk-enums record))]
                               (conj
                                 (map #(hash-map :db/ident %) enum-values)
                                 (lang/not-nil
                                   (ordered-map
                                     :db/ident (-> record :ident string/trim keyword)
                                     :db/valueType (if enum? :db.type/ref (keyword valtype))
                                     :db/cardinality (if (string/blank? star)
                                                       :db.cardinality/one :db.cardinality/many)
                                     :db.attr/preds (some->> record :attr-preds lang/cnsplit (mapv symbol))
                                     :db/doc (some-> record :doc string/trim)
                                     :db/unique (if-let [u (:unique record)] (keyword "db.unique" u))
                                     :db/isComponent (or (:is-component record) nil)
                                     )))))
                           records))
        unique-keys    (doall
                         (for [[unique-key attrs] (->> records
                                                       (mapcat
                                                         (fn [m]
                                                           (for [kn (some-> m
                                                                            :key
                                                                            (string/split #"\s+")
                                                                            (->> (filter seq)))]
                                                             (assoc m :key-name kn))))
                                                       (group-by :key-name))]
                           {:db/ident       (keyword unique-key)
                            :db/valueType   :db.type/tuple
                            :db/tupleAttrs  (mapv (comp keyword :ident) attrs)
                            :db/cardinality :db.cardinality/one
                            :db/unique      :db.unique/identity}))]
    {:model (concat eavs unique-keys)}))

(defn snapshot-schema-to-local
  [args]
  (let [gsheet-service (gsx/login* (io/file "./tmp/google-creds.json"))
        schema         (snapshot-schema gsheet-service)
        schema-str     (edn/write-str schema)]
    (timbre/info "Writing schema" {:schema-path schema-path})
    (spit schema-path schema-str)))

(defn deploy-schema
  [args]
  (let [client            (get-client)
        database-created? (d/create-database client {:db-name database-name})
        conn              (get-connection)
        ;schema            (edn/read (PushbackReader. (io/reader schema-path)))
        schema            (-> schema-path io/input-stream edn/read)
        model             (vec (:model schema))]
    (timbre/info "Loading model..."
                 {:config            (config-data)
                  :database-created? database-created?})
    (d/transact conn {:tx-data model})
    (timbre/info "Loaded")))
