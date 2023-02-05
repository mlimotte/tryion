(ns user
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as stest]
    [cemerick.pomegranate]
    [datomic.client.api :as d]
    [tryion.system :refer [from-system]]
    [tryion.db.db :as db]
    [tryion.common.gsheetsx :as gsx]
    [tryion.location :as location]
    [tryion.asset :as asset]
    [tryion.booking :as booking]
    [tryion.db.functions :as functions]
    [tryion.http]
    [tryion.common.log]
    [datomic.ion.cast :as cast]
    [ring.mock.request :as mock]
    ))

(defn setup!
  []
  ;; Spec: Enable checking
  ;;   Warning: This is inefficient at scale (if used on production system)
  (s/check-asserts true)
  ;; Spec: triggers validation on fn with :args fn-spec
  (stest/instrument)
  ;; Redirect `cast` alert, event, and dev messages to STDOUT
  (cast/initialize-redirect :stdout))

(setup!)

(defonce tapped
  (let [target (atom [])]
    (add-tap #(swap! target conj %))
    target))

(defn clear-tapped
  []
  (reset! tapped []))

(defn add-dep
  [sym ver-string]
  (comment
    (add-dependencies :coordinates '[[camel-snake-kebab/camel-snake-kebab "0.4.3"]]
                      :repositories (merge cemerick.pomegranate.aether/maven-central
                                           {"clojars" "https://clojars.org/repo"})))
  (cemerick.pomegranate/add-dependencies
    :coordinates [[sym ver-string]]
    :repositories (merge cemerick.pomegranate.aether/maven-central
                         {"clojars" "https://clojars.org/repo"})))

(def conn (delay (from-system :datomic-conn)))

(defn touch
  [entity]
  (d/pull (d/db @conn) '[*] entity))
