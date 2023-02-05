(ns tryion.system
  "All resources, connections, config used by the system.
  Typically, we'd use Stuart Sierra's Component System library, but I don't think that
  will work well with Ion:
  1. There is no entry point at which we can `start`, so we'd wind up defining the
     system in a global atom.
  2. Lambdas executions may be short-lived. So it would be wasteful (causing lag) to
     start the whole system up front."
  (:require [camel-snake-kebab.core :as csk]
            [taoensso.timbre :as timbre]
            [datomic.ion.starter.edn :as edn]
            [tryion.db.db]
            [tryion.common.log]
            [tryion.common.gsheetsx :as gsx]
            [tryion.common.lang :as lang]
            [clojure.java.io :as io]))

;; We have no initial entry point for Ions, so we have to do this as
;; part of a namespace load.
(tryion.common.log/merge-logging-config!)

(def config-googlesheet {:spreadsheet-id "1c6uD4DlvhB4xiTXQLIEhKq_yUor-f00kN3RXKzotkdM"
                         :sheet-name     "Config"})

(def config-path "resources/production-config.edn")
(def config-resource "production-config.edn")

(defn snapshot-config-to-local
  [args]
  (let [gsheet-service (gsx/login* (io/file "./tmp/google-creds.json"))
        raw            (gsx/raw-data gsheet-service
                                     (:spreadsheet-id config-googlesheet)
                                     (:sheet-name config-googlesheet))
        [cet-block v-block] (gsx/blocks raw ["Calendar Event Types" "Views"])
        data           {:calendar-event-types
                        (let [headers (-> cet-block second gsx/headers-from-row)]
                          (->> cet-block
                               (drop 2)
                               (map (partial gsx/row->record headers))
                               (filter :event-type)
                               (map (fn [record]
                                      (-> record
                                          (update :start-offset lang/as-long)
                                          (update :end-offset lang/as-long)
                                          ;(update :end-offset
                                          ;  #(->> % lang/cnsplit (map lang/as-long) seq))
                                          (update :occurrences lang/as-long))))))
                        :views
                        (let [headers (-> v-block second gsx/headers-from-row)]
                          (->> v-block
                               (drop 2)
                               (map (partial gsx/row->record headers))
                               (filter :view-type)
                               (map (fn [record]
                                      (-> record
                                          (update :start-offset lang/as-long)
                                          (update :end-offset lang/as-long))))))}]
    (timbre/info "Writing config" {:path config-path})
    (spit config-path (edn/write-str data))))

(defonce ^:private system
  (delay
    (let [gsheet-service (delay (gsx/login* (io/file "./tmp/google-creds.json")))
          datomic-conn   (delay (tryion.db.db/get-connection))
          config         (delay (edn/edn-resource config-resource))]
      (timbre/info "Instantiating system")
      ;; !!! IMPORTANT
      ;;   Every value should be a Ref, b/c they are accessed via `from-system` which does a `deref`
      {:gsheet-service gsheet-service
       :auth-token     (atom "MY_TOKEN")
       :datomic-conn   datomic-conn
       :config         config})))

(defn from-system
  ([k]
   (from-system @system k))
  ([system-obj k]
   (deref (get system-obj k))))
