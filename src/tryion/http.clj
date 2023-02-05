(ns tryion.http
  (:require
    [ring.middleware.format]
    [ring.util.response :as response]
    [compojure.core :refer [routes GET POST DELETE]]
    [taoensso.timbre :as timbre]
    [tryion.common.log :as log]
    [tryion.system]
    [tryion.booking :as booking]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [datomic.ion.starter.edn :as edn]
    [tryion.location :as location]))

(defn health-route
  "Ring route for a health check"
  [from-system]
  (GET "/healthcheck" req
    (try
      {:status 200
       :body   {:build     nil
                :n         15
                :resources {}}}
      (catch Throwable e
        ; Not an ERROR level log, b/c the load balancer will raise an alert on failure
        (timbre/warn "Health check failed." {:e (type e) :message (.getMessage e)})
        {:status 500 :body "FAIL"}))))

(defn wrap-location-binding
  [handler]
  (fn [{:keys [headers] :as req}]
    (with-bindings {#'location/*location-id* (or (get headers "Location") (get headers "location"))}
      (handler req))))

(defn wrap-bearer-token-auth
  [handler expected-auth-token]
  (fn [{:keys [headers] :as req}]
    (let [auth    (or (get headers "Authorization") (get headers "authorization"))
          authed? (= auth (str "Bearer " expected-auth-token))]
      (if authed?
        (handler req)
        {:status  401
         :headers {}
         :body    "Unauthorized"}))))

(defn handler
  "Web handler that returns info about items matching type."
  ;; See https://docs.datomic.com/cloud/ions/ions-reference.html#web-ion for a description of the fn arg
  [from-system]
  (let [auth-token (from-system :auth-token)]
    (routes
      (health-route from-system)
      ;; authenticated routes
      (-> (routes (booking/routes from-system))
          (wrap-bearer-token-auth auth-token))
      ;; If no other matches, return a 404:
      (response/not-found {:ok false :error "Not found"}))))

(defn wrap-quicklog-requests
  [handler]
  (fn [req]
    (log/httplog "REQUEST" req)
    (handler req)))

(def app
  (-> (handler tryion.system/from-system)
      wrap-location-binding
      wrap-keyword-params
      wrap-params
      (ring.middleware.format/wrap-restful-format
        :response-options {:json {:pretty true}})
      log/wrap-exception-handling ; "outside" wrap-restful-format
      ;wrap-quicklog-requests
      log/default-log-wrapper))
