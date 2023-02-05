(ns tryion.common.log
  (:require [clojure.stacktrace]
            [clojure.data.json :as json]
            [ring.logger :refer [wrap-with-logger]]
            [ring.util.response]
            [taoensso.timbre :as timbre]
            [org.httpkit.client :as httpkit]
            [datomic.ion.starter.edn :as edn]
    ;[taoensso.timbre.appenders.core :as timbre-appenders]
            [timbre-json-appender.core :refer [handle-vargs]]
            [jsonista.core]
            [datomic.ion.cast :as cast]
            ))

(defn httplog
  ([msg]
   (httplog msg nil))
  ([msg data]
   (let [logendpoint "https://marc.limotte.ngrok.io/log"
         {:keys [status]} (if data
                            @(httpkit/post logendpoint {:query-params {:msg msg}
                                                        :headers      {"Content-Type" "application/edn"}
                                                        :body         (edn/write-str data)})
                            @(httpkit/get logendpoint {:query-params {:msg msg}}))]
     status)))

(defn ion-cast-appender
  "Creates Timbre configuration map for datomic.io.cast appender."
  [options]
  {:enabled?  true
   :async?    false
   :min-level nil
   :fn        (fn [{:keys [instant level ?ns-str ?file ?line ?err vargs ?msg-fmt context] :as data}]
                (let [c       (and context
                                   (if (timbre/level>= level :warn)
                                     context
                                     (dissoc context :skipp.util.log/warn+)))
                      log-map (handle-vargs {:timestamp instant
                                             :level     level
                                             :thread    (.getName (Thread/currentThread))}
                                            ?msg-fmt
                                            vargs
                                            false)
                      log-map (cond-> log-map
                                ?err (->
                                       ;(assoc :err (Throwable->map ?err))
                                       (assoc :ex ?err)
                                       (assoc :ns ?ns-str)
                                       (assoc :file ?file)
                                       (assoc :line ?line))
                                (not ?err)
                                (assoc :ns ?ns-str)
                                (seq c)
                                (update :args merge c))]
                  ;; We can also do metrics: `(cast/metric {:name :CodeDeployEvent :value 1 :units :count})`
                  (cond
                    (timbre/level>= level :warn)
                    (cast/alert log-map)
                    (timbre/level>= level :info)
                    (cast/event log-map)
                    :else
                    (cast/dev log-map))))})

(defn merge-logging-config!
  []
  (timbre/info "Setting timbre logging config")
  (timbre/merge-config!
    {:min-level [["datomic.*" :warn]
                 ["tryion.*" :debug]
                 ["*" :info]]
     :appenders [(ion-cast-appender {})]}))

(defonce healthcheck-count (atom 0))
(defn custom-logging-transform
  [log-item]
  (let [m (:message log-item)]
    (cond
      ; We log :params as part of the :starting / :finish messages, so no need to create
      ; its own log message.
      (#{:params} (:ring.logger/type m))
      nil
      (and (#{:starting :finish} (:ring.logger/type m)) (= (:uri m) "/healthcheck"))
      (do
        (swap! healthcheck-count inc)
        ; Limiting to 20, will print the first 10 starting/finish pairs.
        (if (<= @healthcheck-count 20) log-item))
      ;(#{} (:uri m))
      ;(update log-item :message dissoc :body)
      (= (:ring.logger/type m) :finish)
      (update log-item :message dissoc :body :query-string)
      :else
      log-item)))

(defn default-log-wrapper
  "A wrapper that will log a subset of ring requests and responses."
  [handler]
  (wrap-with-logger
    handler
    {:request-keys    (concat ring.logger/default-request-keys
                              [:query-string :body :params])
     :transform-fn    custom-logging-transform
     :log-exceptions? false
     :log-fn          (fn mylog [{:keys [level throwable message]}]
                        (let [msg (format "DLW %s %s" (:ring.logger/type message) (:uri message))]
                          (if (nil? throwable)
                            (timbre/log level msg message)
                            (timbre/log level throwable msg message))))}))

;; Exception creation and handling

(defn error-response
  "Helper to create a response object with an error message."
  [status msg]
  (ring.util.response/content-type
    {:status status
     ;; This wrapper is "outside" wrap-restful-format, so it needs to do its own encoding.
     :body   (json/write-str {:error msg})}
    "application/json; charset=utf-8"))

(defn get-type
  "Get the type if set, otherwise nil."
  [e]
  (-> e ex-data ::type))

(defn stack-trace-str
  [e]
  (with-out-str
    (clojure.stacktrace/print-stack-trace e)))

(defn wrap-exception-handling
  "A Ring wrapper that captures and logs exceptions."
  [handler]
  (fn [request]
    ; Note: This handler happens "outside" of wrap-json-response, so we
    ; need to json encode the body if necessary.
    (try
      (handler request)
      (catch Exception e

        ;; TODO *** Disable this for production:
        (let [trace  (stack-trace-str e)]
          (httplog (format "Exception: %s" (.getMessage e))
                   (assoc (or (ex-data e) {}) :trace trace)))

        (let [etype (get-type e)]
          (cond

            (= etype :usererr)
            (error-response (-> e ex-data :http-status (or 400)) (.getMessage e))

            (= etype :internalerr)
            (error-response (-> e ex-data :http-status (or 500)) (.getMessage e))

            :else
            (let [status (-> e ex-data :http-status (or 500))
                  trace  (stack-trace-str e)]
              (timbre/error (format "Uncaught exception: %s" (.getMessage e))
                            (assoc (or (ex-data e) {})
                              :trace trace))
              (error-response status (str "Internal Error `" e "`: `" (.getMessage e) "`")))
            ))))))

(defn err*
  [err-type msg msg-data additional-data]
  (let [msg2     (if (empty? msg-data)
                   msg
                   (format "%s: %s" msg msg-data))
        data-map (assoc additional-data ::type err-type)]
    (timbre/info msg2 data-map)
    (throw (ex-info msg2 data-map))))

(defn usererr
  [msg msg-data additional-data]
  (err* :usererr msg msg-data additional-data))

(defn internalerr
  [msg msg-data additional-data]
  (err* :internalerr msg msg-data additional-data))
