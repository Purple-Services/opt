(ns opt.gas-station-recommendation
  (:require [opt.helpers.gas-station-recommendation-helpers :as helpers]))

(defn update-local-stations-data
  "Update the local stations.json file used to comptute the suggestions."
  []
  (helpers/update-local-stations-file))

(defn compute-suggestions
  "Take the lat/lng of source and destination and returns a list of
   suggested stations and their total driving time.

   Sample output: [{:station {...} :total-driving-time 4242},
                   {:station {...} :total-driving-time 2424}]"
  [src-lat src-lng dst-lat dst-lng]
  (helpers/suggest-gas-stations-with-driving-time src-lng src-lat dst-lng dst-lat))


