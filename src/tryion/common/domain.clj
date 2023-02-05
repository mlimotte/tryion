(ns tryion.common.domain
  (:require [clojure.spec.alpha :as s])
  (:import [java.time ZonedDateTime]))

(s/def ::jtime-zdtime (partial instance? ZonedDateTime))
