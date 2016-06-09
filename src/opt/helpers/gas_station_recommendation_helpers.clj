(ns opt.helpers.gas-station-recommendation-helpers
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure-polyline.core :as polyline]
            [org.martinklepsch.cc-set :refer [set-by]]
            [common.config :as config])
  (:import  [java.io File]
            [java.util Date]))

(def station-data-file "resources/gas-stations/stations.json") ;; comment: put "LA" and "gas" somewhere; next consider making a key-value pair like: LA: "parameters to get LA gas stations from mapquest" so it will be easier for us to turn on another city
(def mapquest-box-step 2)
(def google-api-key config/dashboard-google-browser-api-key) ;; comment: does it exist in Chris profile? If you are using mine, you might forget updating it to the company one when putting into production
(def gas-stations-atom (atom {}))
(def avg-gasprice-reg-atom (atom 0))
(defn gen-grids-helper [br-lat br-lng tl-lat tl-lng og-lng coll] ;; please define the abbr and add some English to explain what you do here
  (cond 
    (>= br-lat tl-lat) coll
    (>= br-lng tl-lng) (gen-grids-helper (+ mapquest-box-step br-lat) og-lng tl-lat tl-lng og-lng coll)
    :else (gen-grids-helper br-lat (+ br-lng mapquest-box-step) tl-lat tl-lng og-lng (conj coll [br-lat br-lng]))))

(defn gen-grids [br-lat br-lng tl-lat tl-lng] (gen-grids-helper br-lat br-lng tl-lat tl-lng br-lng []))

;;(defn remove-stations-wo-91 [stations]
;; TODO: implement me.
;;  )

;;(defn remove-stations-non-toptier [stations]
;; TODO: implement me.
;;  )

(defn is-top-tier?  ;; comment: doing (.contains (.toLowerCase (:brand station)) to each brand repeatedly; low efficiency?
  [station]
  (or 
   (.contains (.toLowerCase (:brand station)) "76")
   (.contains (.toLowerCase (:brand station)) "aloha")
   (.contains (.toLowerCase (:brand station)) "arco")
   (.contains (.toLowerCase (:brand station)) "beacon")
   (.contains (.toLowerCase (:brand station)) "bp")
   (.contains (.toLowerCase (:brand station)) "chevron")
   (.contains (.toLowerCase (:brand station)) "cenex")
   (.contains (.toLowerCase (:brand station)) "conoco")
   (.contains (.toLowerCase (:brand station)) "costco")
   (.contains (.toLowerCase (:brand station)) "countrymark")
   (.contains (.toLowerCase (:brand station)) "shamrock")
   (.contains (.toLowerCase (:brand station)) "entec")
   (.contains (.toLowerCase (:brand station)) "exxon")
   (.contains (.toLowerCase (:brand station)) "mobil")
   (.contains (.toLowerCase (:brand station)) "ohana")
   (.contains (.toLowerCase (:brand station)) "66")
   (.contains (.toLowerCase (:brand station)) "quiktrip")
   (.contains (.toLowerCase (:brand station)) "shell")
   (.contains (.toLowerCase (:brand station)) "sinclair")
   (.contains (.toLowerCase (:brand station)) "texaco")
   (.contains (.toLowerCase (:brand station)) "valero")
   (.contains (.toLowerCase (:brand station)) "kwik")))

(defn get-los-angeles-stations-with-price    ;; comment: make this for a generic city, not just for los angeles
  [br-lat br-lng tl-lat tl-lng]
  (try
    (-> (str "http://gasprices.mapquest.com/services/v1/stations?hits=4000&"
             "boundingbox=(" br-lat "%2C" br-lng "%2C" tl-lat "%2C" tl-lng ")&offset=0")
        client/get
        :body
        json/read-str
        (get "results"))
    (catch Exception e (get-los-angeles-stations-with-price br-lat br-lng tl-lat tl-lng))))

(defn get-los-angeles-stations-all   ;; comment: maybe make 8000 an input or a constant that's easier to change?
  [br-lat br-lng tl-lat tl-lng]
  (try
    (-> (str "http://services.aws.mapquest.com/v1/search?"
             "boundingBox=" br-lat "," br-lng "," tl-lat "," tl-lng
             "&clip=none&client=yogi&count=8000&prefetch=0&query=sic:554101")
        client/get
        :body
        json/read-str
        (get "results"))
    (catch Exception e (get-los-angeles-stations-all br-lat br-lng tl-lat tl-lng))))

(defn into-unique-id    ;; comment: add some English please
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

                                        ; (defn collect-la-stations-data-from-mapquest
                                        ;   []
                                        ;   (reduce (fn [coll br]
                                        ;             (into-unique-id
                                        ;              coll
                                        ;              (get-los-angeles-stations (first br)
                                        ;                                        (second br)
                                        ;                                        (+ mapquest-box-step (first br))
                                        ;                                        (+ mapquest-box-step (second br)))))
                                        ;           []
                                        ;           (gen-grids 33.0 -119.0 35.0 -117.0)))

(defn lnglat-to-block-id   ;; comment: will this work for all cities uniformly? Also, lat and lng are easily mistaken, so let's always put lat before lng everywhere including in names
  [lng lat]
  (+ (+ 9000 (int (Math/floor (* 100 lat))))
     (* 18000 (+ 18000 (int (Math/floor (* 100 lng)))))))

(defn get-station-street
  [station]
  (str 
   (get-in station ["GeoAddress" "Street"]) ", "
   (get-in station ["GeoAddress" "AdminArea5"]) ", "
   (get-in station ["GeoAddress" "AdminArea3"]) " "
   (get-in station ["GeoAddress" "PostalCode"])))
(defn get-station-lng [station] (get-in station ["GeoAddress" "LatLng" "Lng"]))  ;; comment: put next line before this line
(defn get-station-lat [station] (get-in station ["GeoAddress" "LatLng" "Lat"]))
(defn get-station-brand [station] (get-in station ["name"]))
(defn get-station-side-of-street [station] (get-in station ["GeoAddress" "SideOfStreet"]))
(defn get-station-id [station] (get-in station ["id"]))
(defn get-opisprice-type [opisprice] (get opisprice "type"))
(defn get-opisprice-price [opisprice] (get opisprice "amount"))
(defn get-opisprice-timestamp [opisprice] (get opisprice "timestamp"))

(defn get-station-street-no-price [station] (get-in station ["address" "address1"]))
(defn get-station-lng-no-price [station] (get-in station ["latLng" "lng"]))
(defn get-station-lat-no-price [station] (get-in station ["latLng" "lat"]))
(defn get-station-brand-no-price [station] (get-in station ["name"]))
(defn get-station-id-no-price [station] (str (get-in station ["mqId"])))

(defn get-station-prices
  [station]
  (map (fn [opisprice]
         {:type (get-opisprice-type opisprice)
          :price (get-opisprice-price opisprice)
          :timestamp (get-opisprice-timestamp opisprice)})
       (get station "opisGasPrices")))

(defn generate-station-object-with-price
  [station]
  {:id (get-station-id station)
   :block_id (lnglat-to-block-id (get-station-lng station) (get-station-lat station))
   :brand (get-station-brand station)
   :street (get-station-street station)
   :lng (get-station-lng station)
   :lat (get-station-lat station)
   :prices (get-station-prices station)})

(defn generate-station-object-no-price
  [station]
  {:id (get-station-id-no-price station)
   :block_id (lnglat-to-block-id (get-station-lng-no-price station) (get-station-lat-no-price station))
   :brand (get-station-brand-no-price station)
   :street (get-station-street-no-price station)
   :lng (get-station-lng-no-price station)
   :lat (get-station-lat-no-price station)
   :prices nil})

(defn stations-merge-helper
  [coll station-no-price]
  (let [id (get-station-id-no-price station-no-price)]
    (if-not 
        (some (fn [station] (= (:id station) id)) coll)
      (conj coll (generate-station-object-no-price station-no-price))
      coll)))

(defn generate-json-object
  [stations-with-price] 
  (let [stations (map generate-station-object-with-price stations-with-price)]
    stations))

(defn update-local-stations-file
  "Update local cache of stations."
  [opt]
  (let [station-data (generate-json-object (get-los-angeles-stations-with-price 33.0 -119.0 35.0 -117.0))]
    (if (:extra_stations_file opt)
      (let [extra-stations-from-file (json/read-str (slurp (:extra_stations_file opt)) :key-fn keyword)]
        (spit 
         station-data-file
         (json/write-str
          (into
           station-data
           (map
            (fn [station]
              (assoc 
               station 
               :block_id
               (lnglat-to-block-id
                (:lng station)
                (:lat station))))
            extra-stations-from-file)))))
      (spit 
       station-data-file
       (json/write-str
        station-data)))))

(defn remove-blacklisted-stations   ;; comment: does filter parallelize like in map-reduce? I am concerned about the efficiency once black list becomes long
  [stations blacklist]
  (filter 
   (fn [station]
     (not-any? 
      (fn [bl-item]
        (= bl-item (:id station)))
      blacklist))
   stations))

(defn remove-non-toptier-stations
  [stations]
  (filter is-top-tier? stations))

(defn gas-stations-helper
  [_ opt]
  ;; (if (> -604800000 (- (.lastModified (File. station-data-file)) (.getTime (Date.))))
  ;;   (update-local-stations-file)
  (->
   (json/read-str (slurp station-data-file) :key-fn keyword)
   remove-non-toptier-stations
   (remove-blacklisted-stations (:blacklist opt))   ;; comment: I expect blacklist to be stable. so, create a file with the blacklisted stations excluded. when the blacklist is updated, update the file. this will reduce the call to remove
   (#(filter :prices %))))

(defn gas-stations
  [opt]
  (do
    ;; (if (empty? @gas-stations-atom)
      (swap! gas-stations-atom gas-stations-helper opt)
    ;; )
    @gas-stations-atom))

(defn goog-request-route [src-lng src-lat dst-lng dst-lat]   ;; comment: I haven't checked, but make sure to use "duration-in-traffic"
  (json/read-str (:body (client/get (str "https://maps.googleapis.com/maps/api/directions/json?origin=" src-lat "," src-lng "&destination=" dst-lat "," dst-lng "&sensor=false&key=" google-api-key))) :key-fn keyword))

(defn goog-resp->simple-polyline
  [goog-resp]
  (polyline/decode (:points (:overview_polyline (first (:routes goog-resp))))))

(defn goog-resp->complex-polyline
  [goog-resp]
  (reduce (fn [coll step] (into coll (polyline/decode (:points (:polyline step)))))
          []
          (:steps (first (:legs (first (:routes goog-resp)))))))

(defn get-route-polyline
  [src-lng src-lat dst-lng dst-lat]
  (->> (goog-request-route src-lng src-lat dst-lng dst-lat)
       goog-resp->complex-polyline))

(defn polyline->blocks
  [polyline]
  (into #{}
        (map (fn [point] (lnglat-to-block-id (:longitude point)
                                             (:latitude point)))
             polyline)))

;; comment: what is the input "opt"? the blacklist?
(defn suggest-gas-stations 
  ([polyline opt]
   (filter
    (fn [station]
      (contains?
       (reduce
        (fn [coll point]
          (let [waypoint-bid 
                (lnglat-to-block-id
                 (:longitude point)
                 (:latitude point))]
            (into coll [
                        waypoint-bid])))
                                        ; (inc waypoint-bid)
                                        ; (dec waypoint-bid)
                                        ; (+ waypoint-bid 18000)
                                        ; (- waypoint-bid 18000)
                                        ; (+ (inc waypoint-bid) 18000)
                                        ; (- (dec waypoint-bid) 18000)
                                        ; (+ (dec waypoint-bid) 18000)
                                        ; (- (inc waypoint-bid) 18000)])))
        #{}
        polyline)
       (:block_id station)))
    (gas-stations opt)))
  ([src-lng src-lat dst-lng dst-lat opt] (suggest-gas-stations (get-route-polyline src-lng src-lat dst-lng dst-lat) opt)))

(defn count-viable-stations 
  ([polyline] (count (suggest-gas-stations polyline)))
  ([src-lng src-lat dst-lng dst-lat] (count-viable-stations (get-route-polyline src-lng src-lat dst-lng dst-lat))))

(defn goog-request-route-with-station
  [src-lng src-lat dst-lng dst-lat stn-lng stn-lat]
  (json/read-str
   (:body
    (client/get
     (str "https://maps.googleapis.com/maps/api/directions/json?origin=" src-lat
          "," src-lng "&destination=" dst-lat "," dst-lng "&waypoints=" stn-lat
          "," stn-lng "&sensor=false&key=" google-api-key))) :key-fn keyword))

(defn driving-time-between
  "Get real-time driving time between two points or that with 
   a intermediate station using Google's Distance Matrix."
  ([src-lat src-lng dst-lat dst-lng opt]
   (-> 
    (str "https://maps.googleapis.com/maps/api/distancematrix/json?origins=" src-lat
         "," src-lng "&destinations=" dst-lat 
         "," dst-lng "&sensor=false&departure_time=now&key=" google-api-key)
    (client/get)
    (:body)
    (json/read-str :key-fn keyword)
    (get-in [:rows 0 :elements 0 :duration_in_traffic :value])))
  ([src-lat src-lng dst-lat dst-lng stn-lat stn-lng opt]
   (+
    (driving-time-between src-lat src-lng stn-lat stn-lng opt)
    (driving-time-between stn-lat stn-lng dst-lat dst-lng opt))))

(defn goog-resp->driving-time
  [goog-resp]
  (reduce (fn [coll leg] (+ coll (get-in leg [:duration :value])))
          0
          (get-in goog-resp [:routes 0 :legs])))

(defn suggest-gas-stations-with-driving-time 
  [src-lng src-lat dst-lng dst-lat opt] 
  (let [suggested-stations (suggest-gas-stations src-lng src-lat dst-lng dst-lat opt)]
    (pmap (fn [station]
            {:station station
             :total-driving-time (driving-time-between src-lat src-lng dst-lat dst-lng (:lat station) (:lng station) {})})
          suggested-stations)))

(defn cumulative-avg
  [_ numbers]
  (:avg
   (reduce
    (fn
      [coll elem]
      (let [i (:i coll) avg (:avg coll)]
        {:i (inc i) 
         :avg (+ (* (/ i (inc i)) avg) (/ elem (inc i)))}))
    {:i 1 :avg (first numbers)}
    (drop 1 numbers))))

(defn get-station-reg-price
  [station]
  (let [price (first (filter (fn [elem] (= (:type elem) "Regular")) (:prices station)))]
    (if
        price
      (read-string (:price price))
      nil)))

(defn avg-gasprice-reg
  [stations]
  (do
    (if
        (= @avg-gasprice-reg-atom 0)
      (swap! 
       avg-gasprice-reg-atom 
       cumulative-avg
       (reduce
        (fn 
          [coll elem]
          (if
              (get-station-reg-price elem)
            (conj
             coll
             (get-station-reg-price elem))
            coll))
        []
        stations)))
    @avg-gasprice-reg-atom
    ))

(defn suggest-gas-stations-with-score 
  [src-lng src-lat dst-lng dst-lat opt]
  (let [results (suggest-gas-stations-with-driving-time src-lng src-lat dst-lng dst-lat opt) avg (avg-gasprice-reg (gas-stations opt))]
    (->>
     (map
      (fn [elem]
        (assoc elem :price-modifier
               (if (get-station-reg-price (:station elem))
                 (/ (get-station-reg-price (:station elem)) avg)
                 1.0)))
      results)
     (map 
      (fn [elem]
        (assoc elem :arco-modifier
               (if (.contains (.toLowerCase (:brand (:station elem))) "arco")
                 0.8
                 1.0))))
     (map
      (fn [elem]
                                        ; (println elem)
        (assoc elem
               :score
               (* (:total-driving-time elem)
                  (:price-modifier elem)
                  (:arco-modifier elem))))))))

(defn compute-distance
  [station1 station2]
  (Math/sqrt
   (+ 
    (* (- (:lat station1) (:lat station2)) (- (:lat station1) (:lat station2)))
    (* (- (:lng station1) (:lng station2)) (- (:lng station1) (:lng station2))))))

(defn neighbor-blocks-filter
  "Return true if two blocks are adjacent to each other" 
  [block1 block2]
  (or
   (= block1 block2)
   (= block1 (inc block2))
   (= block1 (dec block2))
   (= block1 (+ block2 18000))
   (= block1 (- block2 18000))
   (= (inc block1) (+ block2 18000))
   (= (inc block1) (- block2 18000))
   (= (dec block1) (+ block2 18000))
   (= (dec block1) (- block2 18000))))

(defn suggest-gas-stations-near-with-score-price-based
  [src-lng src-lat opt]
  (->>
   (take 5
         (sort-by 
          :distance
          <
          (map
           (fn [station]
             {
              :station station
              :distance (compute-distance station {:lat src-lat :lng src-lng})
              })
           (gas-stations opt))))
   (pmap
    (fn [elem]
      (assoc 
       elem 
       :driving-time 
       (driving-time-between 
        src-lat 
        src-lng 
        (:lat (:station elem)) 
        (:lng (:station elem)) 
        {}))))
   (map
    (fn [elem]
      (assoc elem :price-modifier
             (if (get-station-reg-price (:station elem))
               (/ (get-station-reg-price (:station elem)) (avg-gasprice-reg (gas-stations opt)))
               1.0))))
                                        ; (map 
                                        ;   (fn [elem]
                                        ;     (assoc elem :arco-modifier
                                        ;       (if (.contains (.toLowerCase (:brand (:station elem))) "arco")
                                        ;         0.8
                                        ;         1.0))))
   (filter
    (fn [elem]
      (< (:driving-time elem)
         1000
         )))
   (sort-by
    :price-modifier
    <)
   (map
    (fn [elem]
                                        ; (println elem)
      (assoc elem :score (* (:price-modifier elem) 1000))))
   ))

(defn suggest-gas-stations-with-score-price-based 
  [src-lng src-lat dst-lng dst-lat opt]
  (let [results (suggest-gas-stations-with-driving-time src-lng src-lat dst-lng dst-lat opt)
        avg (avg-gasprice-reg (gas-stations opt))
        straight-driving-time (driving-time-between src-lat src-lng dst-lat dst-lng nil)]
    (->>
     (map
      (fn [elem]
        (assoc elem :price-modifier
               (if (get-station-reg-price (:station elem))
                 (/ (get-station-reg-price (:station elem)) avg)
                 1.0)))
      results)
                                        ; (map 
                                        ;   (fn [elem]
                                        ;     (assoc elem :arco-modifier
                                        ;       (if (.contains (.toLowerCase (:brand (:station elem))) "arco")
                                        ;         0.8
                                        ;         1.0))))
                                        ; (map 
                                        ;   (fn [elem]
                                        ;     (assoc elem :additional-driving-time
                                        ;       (- (:total-driving-time elem) straight-driving-time))))
                                        ; (filter
                                        ;   (fn [elem]
                                        ;     (<
                                        ;       (:additional-driving-time elem)
                                        ;       300)))
                                        ; (map
                                        ;   (fn [elem]
                                        ;     ; (println elem)
                                        ;     (assoc elem :score
                                        ;       (*
                                        ;         (:total-driving-time elem)
                                        ;         (:price-modifier elem)
                                        ; ;         (:arco-modifier elem)))))
     (into
      (suggest-gas-stations-near-with-score-price-based dst-lng dst-lat opt))
     (into
      (set-by :station))
     (into
      [])
     (sort-by
      :price-modifier
      <)
     (take 5)
     (map
      (fn [elem]
                                        ; (println elem)
        (assoc elem :score (* (:price-modifier elem) 1000))))
     )))

(defn suggest-gas-stations-near-with-score
  [src-lng src-lat opt]
  (->>
   (take 5
         (sort-by 
          :distance
          <
          (map
           (fn [station]
             {
              :station station
              :distance (compute-distance station {:lat src-lat :lng src-lng})
              })
           (gas-stations opt))))
   (pmap
    (fn [elem]
      (assoc 
       elem 
       :total-driving-time 
       (driving-time-between 
        src-lat 
        src-lng 
        (:lat (:station elem)) 
        (:lng (:station elem)) 
        {}))))
   (map
    (fn [elem]
      (assoc elem :price-modifier
             (if (get-station-reg-price (:station elem))
               (/ (get-station-reg-price (:station elem)) (avg-gasprice-reg (gas-stations opt)))
               1.0))))
   (map 
    (fn [elem]
      (assoc elem :arco-modifier
             (if (.contains (.toLowerCase (:brand (:station elem))) "arco")
               0.8
               1.0))))
   (map
    (fn [elem]
                                        ; (println elem)
      (assoc elem :score
             (*
              (:total-driving-time elem)
              (:price-modifier elem)
              (:arco-modifier elem)))))))
