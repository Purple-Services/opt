(ns opt.helpers.gas-station-recommendation-helpers
  (:gen-class))

(import '(java.io File) '(java.util Date))

(require '[clj-http.client :as client]
         '[clojure.data.json :as json]
         '[clojure-polyline.core :as polyline])

(def station-data-file "stations.json")
(def mapquest-box-step 2)
(def google-api-key "AIzaSyAFGyFvaKvXQUKzRh9jQaUwQnHnkiHDUCE")

(defn gen-grids-helper [br-lat br-lng tl-lat tl-lng og-lng coll]
                       (cond 
                          (>= br-lat tl-lat) coll
                          (>= br-lng tl-lng) (gen-grids-helper (+ mapquest-box-step br-lat) og-lng tl-lat tl-lng og-lng coll)
                          :else (gen-grids-helper br-lat (+ br-lng mapquest-box-step) tl-lat tl-lng og-lng (conj coll [br-lat br-lng]))))

(defn gen-grids [br-lat br-lng tl-lat tl-lng] (gen-grids-helper br-lat br-lng tl-lat tl-lng br-lng []))

(defn get-los-angeles-stations 
  [br-lat br-lng tl-lat tl-lng] 
  (try
    (get 
      (json/read-str 
        (:body 
          (client/get 
            (str "http://gasprices.mapquest.com/services/v1/stations?hits=4000&boundingbox=(" br-lat "%2C" br-lng "%2C" tl-lat "%2C" tl-lng ")&offset=0")))) 
      "results")
    (catch Exception e (get-los-angeles-stations br-lat br-lng tl-lat tl-lng)))
  )

(defn into-unique-id
  [s1, coll]
  (reduce 
   (fn [acc el]
     (if (some (fn [x]
                 (= (get x "id")
                    (get el "id")))
               acc)
       acc
       (conj acc el)))
   s1
   coll))
(defn collect-la-stations-data-from-mapquest [] (reduce (fn [coll br] (into-unique-id coll (get-los-angeles-stations (first br) (second br) (+ mapquest-box-step (first br)) (+ mapquest-box-step (second br))))) [] (gen-grids 33.0 -119.0 35.0 -117.0)))

(defn lnglat-to-block-id [lng lat] (+ (+ 9000 (int (Math/floor (* 100 lat)))) (* 18000 (+ 18000 (int (Math/floor (* 100 lng)))))))

(defn get-station-street [station] (get-in station ["GeoAddress" "Street"]))
(defn get-station-lng [station] (get-in station ["GeoAddress" "LatLng" "Lng"]))
(defn get-station-lat [station] (get-in station ["GeoAddress" "LatLng" "Lat"]))
(defn get-station-brand [station] (get-in station ["name"]))
(defn get-station-side-of-street [station] (get-in station ["GeoAddress" "SideOfStreet"]))
(defn get-station-id [station] (get-in station ["id"]))
(defn get-opisprice-type [opisprice] (get opisprice "type"))
(defn get-opisprice-price [opisprice] (get opisprice "amount"))
(defn get-opisprice-timestamp [opisprice] (get opisprice "timestamp"))
(defn get-station-prices [station] (map (fn [opisprice] {:type (get-opisprice-type opisprice), :price (get-opisprice-price opisprice), :timestamp (get-opisprice-timestamp opisprice)}) (get station "opisGasPrices")))
(defn generate-station-object [station] {
    :id (get-station-id station),
    :block_id (lnglat-to-block-id (get-station-lng station) (get-station-lat station)),
    :brand (get-station-brand station),
    :street (get-station-street station),
    :side_of_street (get-station-side-of-street station),
    :lng (get-station-lng station),
    :lat (get-station-lat station),
    :prices (get-station-prices station)
  })
(defn generate-json-object [stations] (map generate-station-object stations))

(defn update-local-stations-file
  "Update local cache of stations."
  []
  (let [station-data (generate-json-object (get-los-angeles-stations 33.0 -119.0 35.0 -117.0))]
    (do 
      (spit station-data-file (json/write-str station-data))
      station-data)))

(defn gas-stations 
  []
  ; (if (> -604800000 (- (.lastModified (File. station-data-file)) (.getTime (Date.))))
  ;   (update-local-stations-file)
    (json/read-str (slurp station-data-file) :key-fn keyword))
  ; )

(defn goog-request-route [src-lng src-lat dst-lng dst-lat] 
  (json/read-str (:body (client/get (str "https://maps.googleapis.com/maps/api/directions/json?origin=" src-lat "," src-lng "&destination=" dst-lat "," dst-lng "&sensor=false&key=" google-api-key))) :key-fn keyword))
(defn goog-resp->simple-polyline [goog-resp] (polyline/decode (:points (:overview_polyline (first (:routes goog-resp))))))
(defn goog-resp->complex-polyline [goog-resp] (reduce (fn [coll step] (into coll (polyline/decode (:points (:polyline step))))) [] (:steps (first (:legs (first (:routes goog-resp)))))))
(defn get-route-polyline [src-lng src-lat dst-lng dst-lat] (goog-resp->complex-polyline (goog-request-route src-lng src-lat dst-lng dst-lat)))
(defn polyline->blocks [polyline] (into #{} (map (fn [point] (lnglat-to-block-id (:longitude point) (:latitude point))) polyline)))

(defn suggest-gas-stations 
  ([polyline] (filter (fn [station] (contains? (into #{} (map (fn [point] (lnglat-to-block-id (:longitude point) (:latitude point))) polyline)) (:block_id station))) (gas-stations)))
  ([src-lng src-lat dst-lng dst-lat] (suggest-gas-stations (get-route-polyline src-lng src-lat dst-lng dst-lat))))
(defn count-viable-stations 
  ([polyline] (count (suggest-gas-stations polyline)))
  ([src-lng src-lat dst-lng dst-lat] (count-viable-stations (get-route-polyline src-lng src-lat dst-lng dst-lat))))

(defn goog-request-route-with-station [src-lng src-lat dst-lng dst-lat stn-lng stn-lat]
  (json/read-str (:body (client/get (str "https://maps.googleapis.com/maps/api/directions/json?origin=" src-lat "," src-lng "&destination=" dst-lat "," dst-lng "&waypoints=" stn-lat "," stn-lng "&sensor=false&key=" google-api-key))) :key-fn keyword))
(defn goog-resp->driving-time [goog-resp] (reduce (fn [coll leg] (+ coll (get-in leg [:duration :value]))) 0 (get-in goog-resp [:routes 0 :legs])))

(defn suggest-gas-stations-with-driving-time 
  [src-lng src-lat dst-lng dst-lat] 
  (let [suggested-stations (suggest-gas-stations src-lng src-lat dst-lng dst-lat)]
    (map (fn [station] {:station station, :total-driving-time (goog-resp->driving-time (goog-request-route-with-station src-lng src-lat dst-lng dst-lat (:lng station) (:lat station)))}) suggested-stations)))


