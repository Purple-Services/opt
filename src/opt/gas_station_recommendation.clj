(ns opt.gas-station-recommendation
  (:require [opt.helpers.gas-station-recommendation-helpers :as helpers]))

(defn update-local-stations-data
  "Update the local stations.json file used to comptute the suggestions."
  []
  (helpers/update-local-stations-file))

;; get rid of all stations that don't have 91.
;; add a opt map to handle options.
(defn compute-suggestions
  "Take the lat/lng of source and destination (optional) and returns a list of
   suggested stations and their total driving time. The output will be sorted
   by their driving time in descending order.

   Sample output: [{:station {...} :total-driving-time 2424},
                   {:station {...} :total-driving-time 4242}]"
  ([src-lat src-lng dst-lat dst-lng opt]
    (helpers/suggest-gas-stations-with-driving-time src-lng src-lat dst-lng dst-lat opt))
  ([src-lat src-lng opt]
    (helpers/suggest-gas-stations-near src-lng src-lat opt)))



