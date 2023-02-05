(ns tryion.common.lang
  (:require
    [clojure.string :as string]
    [java-time.api :as jtime]
    [java-time.format :as jtformat]))

(defn nil-or-blank?
  "True if `s` is a nil or \"\".
  Note: string/blank on it's own will throw an exception for
  non-string values (e.g. numbers). This fn will just return false instead."
  [s]
  (or (nil? s)
      (and (string? s) (string/blank? s))))

(defn not-nil
  [x]
  (cond
    (map? x) (into (empty x) (remove (comp nil? second) x))
    (sequential? x) (remove nil? x)))

(defn utc-now
  []
  (jtime/with-zone-same-instant (jtime/zoned-date-time) "UTC"))

(defn cnsplit
  "Split on comma or newline."
  [s]
  (when s
    (not-nil (string/split s #"\s*[,\n]\s*"))))

(defn as-long
  [s]
  "Coerce the input as a Long. Return nil if not possible."
  (cond
    (string? s) (try
                  (-> s (string/replace #"\.\d*" "") Long/valueOf)
                  (catch Exception _))
    (nil? s) nil
    :else (long s)))

(defn as-double
  [s]
  "Coerce the input as a double. Return nil if not possible."
  (cond
    (string? s) (try (Double/valueOf s) (catch Exception _))
    (nil? s) nil
    :else (double s)))

(defn has-decimal?
  [x]
  (not (zero? (mod x 1))))

(defn as-long-or-double
  [x]
  (cond
    (nil? x)
    x
    (and (number? x) (has-decimal? x))
    (double x)
    (and (number? x) (not (has-decimal? x)))
    (long x)
    (and (string? x) (.contains x "."))
    (as-double x)
    (string? x)
    (as-long x)))

(defn at-midnight
  "Set the java-time.ZonedDateTime to midnight of the same day"
  [zdt]
  (jtime/truncate-to zdt :days))

(defn at-noon
  "Set the java-time.ZonedDateTime to noon of the same day"
  [zdt]
  (-> zdt (jtime/truncate-to :days) (jtime/plus (jtime/hours 12))))

(defn roll-to-weekday
  "Advance (= forward-or-backward :forward) or regress (= forward-or-backward :backward)
  the java-time.ZonedDateTime to a weekday."
  [zdt forward-or-backward]
  (when zdt
    (let [delta-days (jtime/days (case forward-or-backward
                                   :forward 1
                                   :backward -1))]
      (loop [testzdt zdt]
        (if (jtime/weekday? testzdt) testzdt (recur (jtime/plus testzdt delta-days)))))))

(defn parse-datetime
  [s]
  (when s
    (jtformat/parse (jtformat/formatter :iso-zoned-date-time) s)))
