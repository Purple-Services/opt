(ns opt.gas-station-recommendation
  (:require [opt.helpers.gas-station-recommendation-helpers :as helpers]))

(defn update-local-stations-data
  "Update the local stations.json file used to compute the suggestions.

  The argument 'opt' is an optional dictionary that accepts the following keys:
  <:extra_stations_file>: a file of extra stations (in json format). (optional)

  "
  [opt]
  (helpers/update-local-stations-file opt))

(defn compute-suggestions
  "Take the lat/lng of source and destination (optional) and returns a list of
   suggested stations and their total driving time. The output will be sorted
   by their score in descending order. The LOWER the score the better.

   The argument 'opt' is an optional dictionary that accepts the following keys:
   <:blacklist>: a list of IDs to be filtered. (optional)

   Sample output: [{:station {...} :total-driving-time 2424},
                   {:station {...} :total-driving-time 4242}]"
  ([src-lat src-lng opt]
   (helpers/suggest-gas-stations-near-with-score-price-based src-lng src-lat opt))
  ([src-lat src-lng dst-lat dst-lng opt]
   (helpers/suggest-gas-stations-near-with-score-price-based src-lng src-lat dst-lng dst-lat opt)))

;; Call every Sunday
;; (update-local-stations-data {:extra_stations_file "extra.json"})
;; (update-local-stations-data {})
;; (compute-suggestions 33.997034 -118.428448 {})
