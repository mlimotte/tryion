(ns tryion.common.gsheetsx
  (:require
    [clojure.core.memoize :as memo]
    [clojure.data.json :as json]
    [clojure.string :as string]
    [taoensso.timbre :as timbre]
    [java-time.api :as jtime]
    [google-apps-clj.credentials :as gcreds]
    [google-apps-clj.google-sheets-v4 :as google-sheets-v4]
    [camel-snake-kebab.core :as csk]
    [tryion.common.lang :as lang]
    )
  (:import
    java.io.ByteArrayInputStream
    (com.google.api.services.sheets.v4.model CellData)
    (com.google.api.services.sheets.v4 Sheets)
    ))

(def row-idx
  "Unique key used to store the row index in the Record. Index is needed for updates."
  ::row-idx)

;; Google creds
;; See instructions at https://github.com/SparkFund/google-apps-clj
;; Scopes: https://developers.google.com/identity/protocols/oauth2/scopes
;; (google-apps-clj.credentials/get-auth-map creds ["https://www.googleapis.com/auth/spreadsheets"
;;                                                  ;"https://www.googleapis.com/auth/drive"
;;                                                  ;"https://www.googleapis.com/auth/drive.file"
;;                                                  ])

;(defn gcreds-stream-from-aws-secrets-manager
;  "Get the Google api keys from the `secrets` Map, as a Stream. The Google
;  API client library we're using expects the creds to be either a separate file
;  or a Stream. Creating the stream this way, allows us to embed the API creds
;  in the common `secrets` file."
;  [aws secret-id]
;  (-> (secretsmanager/get-secret-value aws secret-id)
;      (or (throw (ex-info "`google` api keys are required." {:type :unauthorized})))
;      json/write-str
;      (.getBytes "utf-8")
;      ByteArrayInputStream.))

(defn login*
  "Login to Google Sheets using the supplied JSON credentials file.
  To get credentials, see documentation at https://github.com/SparkFund/google-apps-clj.
  `creds-file` can be anything that can be handled by `clojure.java.io/input-stream`"
  [creds-file]
  (timbre/info "Logging into Google service.")
  (let [creds   (gcreds/credential-with-scopes
                  (gcreds/credential-from-json-stream creds-file)
                  ;(into google-sheets-v4/scopes [com.google.api.services.drive.DriveScopes/DRIVE])
                  google-sheets-v4/scopes
                  )
        service (google-sheets-v4/build-service creds)]
    service))

;(def login (memo/lru login* {} :lru/threshold 5))
;
;(defn login-with-aws-secret*
;  [aws secret-id]
;  (login (gcreds-stream-from-aws-secrets-manager aws secret-id)))
;
;(def login-with-aws-secret (memo/lru login-with-aws-secret* {} :lru/threshold 5))

(defn get-cells
  "sheet-ranges is a seq of strings, using the A1 syntax, eg [\"Sheet!A1:Z9\"]
   or Named Ranges.
   Returns a vector of tables in corresponding to sheet-ranges.  Only one
   sheet (tab) can be specified per batch, due to a quirk of Google's API as far
   as we can tell."
  [^Sheets service spreadsheet-id sheet-ranges]
  (let [fields "sheets(properties(title),data(rowData(values(effectiveValue,userEnteredFormat))))"
        data   (-> service
                   (.spreadsheets)
                   (.get spreadsheet-id)
                   (.setRanges sheet-ranges)
                   (.setFields fields)
                   (.execute))]
    (->> (get data "sheets")
         (map (fn [table]
                (let [title (get-in table ["properties" "title"])
                      rows  (mapv #(get % "values")
                                  (-> (get table "data")
                                      first
                                      (get "rowData")))]
                  [title rows])))
         (into {}))))

(defn cell->clj
  "Converts cell data with either a userEnteredValue (x)or effectiveValue to a clojure type.
  stringValue -> string
  numberValue -> double
  DATE -> date-time
  boolean -> boolean
  else ~ identity
  NOTE: This is based on google-apps-clj.google-sheets-v4/cell->clj, but also
        supports boolean values."
  [cell-data]
  (let [ev            (get cell-data "effectiveValue")
        uev           (get cell-data "userEnteredValue")
        v             (or ev uev)
        bool-val      (get v "boolValue")
        string-val    (get v "stringValue")
        number-val    (get v "numberValue")
        number-format (get-in cell-data ["userEnteredFormat" "numberFormat" "type"])
        date?         (and (= "DATE" number-format) (some? number-val))
        currency?     (and (= "CURRENCY" number-format) (some? number-val))
        empty-cell?   (and (nil? ev) (nil? uev) (instance? CellData cell-data))]
    (when (and (some? ev)
               (some? uev))
      (throw (ex-info "Ambiguous cell data, contains both string effectiveValue and userEnteredValue"
                      {:cell-data cell-data})))
    (when (and (some? string-val)
               (some? number-val))
      (throw (ex-info "Ambiguous cell data value, contains both stringValue and numberValue"
                      {:cell-data cell-data})))
    (cond
      (not (nil? bool-val))
      bool-val

      string-val
      string-val

      date?
      ;; Google Sheets API datetime_serial_numbers
      (jtime/plus (jtime/with-zone (jtime/zoned-date-time 1899 12 30) "UTC") (jtime/days (long number-val)))

      currency?
      (bigdec number-val)

      number-val
      number-val

      empty-cell?
      nil

      :else
      cell-data)))

(defn read-as-vec-vec
  "`range` can just be a Tab name, for all data."
  [^Sheets service spreadsheet-id range]
  ; Only supporting 1 range, so get the first table:
  (let [table (val (first (get-cells service spreadsheet-id [range])))]
    (mapv (partial mapv cell->clj) table)))

(defn headers-from-row
  "Given a row (vector)"
  [headers-raw]
  (->> headers-raw
       (take-while (complement nil?))
       (map str)
       ; string/blank? would handle nils, but we must make sure everything is a string first
       (take-while (complement lang/nil-or-blank?))
       (map #(string/replace % #"\s*\([^\)]+\)\s*" " "))
       (map string/trim)
       (map csk/->kebab-case-keyword)))

(defn row->record
  [headers row]
  (->> (zipmap headers row)
       (remove (fn [[k v]] (lang/nil-or-blank? v)))
       (into {})))

(defn prune-start-rows
  "Prune leading rows up to and including the \"start\" row, which is the first
  row where the first cell is = \"/START\"."
  [raw]
  (rest (drop-while #(not= (first %) "/START") raw)))

(defn drop-after-stop-row
  "Drop all rows at the end following the \"stop\" row, which is the first
  row where the first cell is = \"/STOP\"."
  [raw]
  (take-while #(not= (first %) "/STOP") raw))

(defn read-single-table
  [service spreadsheet-id sheet-name &
   [{:keys [prune-start? filter-fn row-idx?] :as options}]]
  (let [[headers-raw & data-raw]
        (cond-> (read-as-vec-vec service spreadsheet-id sheet-name)
          prune-start?
          prune-start-rows
          true
          drop-after-stop-row)
        headers (headers-from-row headers-raw)]
    (cond->>
      (map (partial row->record headers) data-raw)
      row-idx? (map-indexed (fn [idx record] (assoc record ::row-idx (inc idx))))
      filter-fn (filter filter-fn))))

(defn raw-data
  "Read the data from a sheet as vec-vec until the `/STOP` line if it exists."
  [service spreadsheet-id sheet-title-or-range]
  (->> (read-as-vec-vec service spreadsheet-id sheet-title-or-range)
       drop-after-stop-row))

(defn blocks
  "Splits the rows into blocks, where each block is identified
  with a \"block-title\" in the first cell of a row.
  Leading rows before the first block are discarded.
  The blocks are returned in the same order as the block-titles.
  the block-title row is included as the first row of the returned block."
  [raw-data block-titles]
  (let [[blocks last-block _]
        (reduce
          (fn [[blocks current-block [t1 :as titles]] row]
            (let [title-match? (and t1 (= (first row) t1))]
              (cond
                (and title-match? current-block)
                [(conj blocks current-block) [row] (rest titles)]

                (and title-match? (not current-block))
                [blocks [row] (rest titles)]

                (and (not title-match?) current-block)
                [blocks (conj current-block row) titles]

                (and (not title-match?) (not current-block))
                [blocks current-block titles]
                )))
          [[] nil block-titles]
          raw-data)]
    (if last-block
      (conj blocks last-block)
      blocks)))
