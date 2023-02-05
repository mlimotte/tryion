(ns tryion.db.util)

(defn get-id
  ([tx-result]
   (let [tempids (:tempids tx-result)]
     (if (= (count tempids) 1)
       (-> tempids first val)
       (throw (ex-info "get-id did not specify a `temp-id` for a result with more than one result."
                       {:tx-result tx-result})))))
  ([tx-result temp-id]
   (get-in tx-result [:tempids temp-id])))

(defn get-ids
  ([tx-result]
   (let [tempids (:tempids tx-result)]
     tempids)))
