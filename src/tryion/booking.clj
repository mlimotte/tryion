(ns tryion.booking
  (:require [datomic.client.api :as d]
            [java-time.api :as jtime]
            [clojure.spec.alpha :as s]
            [compojure.core :refer [GET POST DELETE]]
            [tryion.db.util :as db-util]
            [tryion.common.lang :as lang]
            [tryion.system :refer [from-system]]
            [tryion.common.domain :as domain]
            [taoensso.timbre :as timbre]
            [tryion.db.db :as db]
            [tryion.location :as location]
            [tryion.common.log :as log]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as str])
  (:import [clojure.lang ExceptionInfo]))

(defn get-booking-by-id
  "Returns pull map describing the entity id"
  [db pull-expr eid]
  (d/pull db pull-expr eid))

(defn get-active-bookings-by
  [db pull-expr q]
  (let [{:keys [customer-external-id]} q]
    (try (map first
              (d/q '[:find (pull ?b pull-expr)
                     :in $ ?external-id ?location-id pull-expr
                     :where [?c :customer/external-id ?external-id]
                     [?c :customer/bookings ?b]
                     [?b :booking/store ?location-id]
                     [?b :booking/status :booking-status/active]]
                   db
                   customer-external-id
                   [:store/location-id (location/location)]
                   pull-expr))
         (catch ExceptionInfo e
           (if (->> e ex-data :cognitect.anomalies/message
                    (re-find #"Cannot resolve key.*:store/location-id"))
             (log/usererr "Unrecognized location" {:location (location/location)} {})
             (throw e))))))

(comment
  ;; Convert calendar-events (java.util.Date) to java-time ZonedDateTime
  (-> r :result first
      :booking/calendar-events (nth 5) :calendar-event/start
      jtime/instant
      (jtime/zoned-date-time "UTC")
      (->> (jtime/format (jtformat/formatter "YYYY/MM/DD")))))

;(defn add-calendar-event
;  [conn event-type start-instant end-instant optional-seq]
;  (db-util/get-id
;    (d/transact conn {:tx-data [(lang/not-nil {:db/id                "new"
;                                               :calendar-event/type  event-type
;                                               :calendar-event/start start-instant
;                                               ;:calendar-event/actual-start
;                                               :calendar-event/end   end-instant
;                                               ;:calendar-event/actual-end
;                                               :calendar-event/seq   optional-seq
;                                               })]})))

(defn calendar-event
  [event-type start-instant end-instant optional-seq]
  (lang/not-nil {:calendar-event/type  event-type
                 :calendar-event/start start-instant
                 ;:calendar-event/actual-start
                 :calendar-event/end   end-instant
                 ;:calendar-event/actual-end
                 :calendar-event/seq   optional-seq
                 }))

(s/fdef default-booking-events
  :args (s/cat :booking-id string? :occasion-date ::domain/jtime-zdtime))
(defn default-booking-events
  [booking-id occasion-dtime]
  (let [{:keys [calendar-event-types]} (from-system :config)
        plus-days #(jtime/plus (jtime/with-zone occasion-dtime "America/New_York")
                               (jtime/days (long %)))]
    (for [{:keys [event-type
                  date-or-datetime
                  occurrences
                  start-offset
                  end-offset]} calendar-event-types
          occurrence (range 1 (inc occurrences))
          :let [roll (fn [zdt offset]
                       (cond (nil? offset) nil
                             (zero? offset) zdt
                             (pos? offset) (lang/roll-to-weekday zdt :forward)
                             (neg? offset) (lang/roll-to-weekday zdt :backward)))]]
      (lang/not-nil
        {:calendar-event/type      (keyword "calendar-event-type" event-type)
         :calendar-event/start     (-> start-offset plus-days lang/at-noon (roll start-offset) jtime/java-date)
         :calendar-event/end       (some-> end-offset plus-days lang/at-noon (roll end-offset) jtime/java-date)
         :calendar-event/seq       occurrence
         :booking/_calendar-events booking-id}))))

(defn add-booking
  [customer-external-id occasion-date asset-entity-lookups]
  (let [location-id (location/location)
        cal-events  (default-booking-events "booking" occasion-date)
        asset-booking-count-txns
                    (for [a asset-entity-lookups]
                      ['tryion.db.functions/inc-attr a :asset/booking-count 1])]
    (concat [{:db/id          "booking"
              :booking/assets asset-entity-lookups
              :booking/store  [:store/location-id location-id]
              :booking/status :booking-status/active}
             {:db/id                "customer"
              :customer/external-id customer-external-id
              :customer/bookings    "booking"}]
            cal-events
            asset-booking-count-txns)))

(defn event-name
  [{ev-seq :calendar-event/seq ev-type :calendar-event/type}]
  (str (-> ev-type :db/ident name str/capitalize)
       (if (>= ev-seq 2) (str " " ev-seq) "")))

(defn routes
  [from-system]
  (compojure.core/routes

    (POST "/tryion/v1/booking" req
      (let [conn                 (from-system :datomic-conn)
            body                 (:body req)
            username             (-> body :user :username (or "unknown"))
            customer-external-id (:customer-external-id body)
            occasion-date        (-> body :occasion-date lang/parse-datetime (or (lang/utc-now)))
            asset-entity-lookups (map (fn [sku] [:asset/sku sku]) (:asset-skus body))]
        (let [result (db/transact
                       conn username
                       (add-booking customer-external-id occasion-date asset-entity-lookups))]
          (:status 200
            :body (db-util/get-ids result)))))

    (GET "/tryion/v1/booking/query" req
      (let [q         (dissoc (:params req) :pull-expr)
            conn      (from-system :datomic-conn)
            pull-expr (or (-> req :params :pull-expr) '[*])
            result    (get-active-bookings-by (d/db conn) pull-expr q)]
        {:status 200
         :body   {:result result}}))

    (GET "/tryion/v1/booking/:eid/calendar-events" req
      (let [eid     (-> req :params :eid lang/as-long)
            conn    (from-system :datomic-conn)
            records (->> eid
                         (get-booking-by-id (d/db conn) [:booking/calendar-events])
                         :booking/calendar-events
                         (sort-by :calendar-event/start))
            events  (map (fn [ev]
                           (println ev)
                           {:id    (-> ev :db/id)
                            :title (event-name ev)
                            :start (-> ev :calendar-event/start)
                            :end   (-> ev :calendar-event/end)})
                         records)]
        {:status 200
         :body   {:result events}}))

    (GET "/tryion/v1/booking/:eid" req
      (let [eid    (-> req :params :eid lang/as-long)
            conn   (from-system :datomic-conn)
            result (get-booking-by-id (d/db conn) '[*] eid)]
        {:status 200
         :body   {:result result}}))

    ))
