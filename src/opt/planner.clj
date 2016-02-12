(ns opt.planner
  (:use common.util)
  (:import [purpleOpt PurpleOpt]))

(defn compute-suggestion
  "Return order-assignment suggestions based on the current state of the
  system (orders & couriers)"
  [state]
  (into {} (PurpleOpt/computeSuggestion (map->java-hash-map state))))

(defn compute-distance
  "Return ETAs based on the current state of the system (orders & couriers)."
  [state]
  (into {} (PurpleOpt/computeDistance (map->java-hash-map state))))
