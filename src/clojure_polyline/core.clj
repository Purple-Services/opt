(ns clojure-polyline.core)

;; -------------------------------------------------------
;; Utility functions
;; -------------------------------------------------------

(defn partition-by-inclusive
  "like partition-by, but also puts the first non-matching element
  in the split, and only groups results that return true in the pred f"
  [f coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (let [run (take-while #(f %) s)
           rem (seq (drop (count run) s))
           included (first rem)
           run-inc (concat run (vector included))]
       (cons run-inc (partition-by-inclusive f (rest rem)))))))

(defn combiner
  "takes a function and a sequence, and then returns a sequence of applying the
  function to the first element in the sequence, and then the result of that and
  the second element, and so on"
  ([f xs] (lazy-seq
           (when-let [s (seq xs)]
             (let [run (map f (first s))]
               (cons run (combiner f run (rest s)))))))
  ([f x y] (lazy-seq
            (when-let [s ( seq y)]
              (let [run (map f x (first s))]
                (cons run (combiner f run (rest s))))))))

(defn split [ints]
  (partition-by-inclusive #(> % 31) ints))

(defn vec->coords [coord-vec]
  (map (fn [[lat long]] (hash-map :latitude lat :longitude long))
       coord-vec))

(defn coords->vec [coords]
  (map (fn [{:keys [latitude longitude]}] [latitude longitude]) coords))

(defn- coord->int [coord]
  (-> coord
      (* 1e5)
      Math/rint
      int))

(defn ints->str [ints]
  (->> ints
       (map char)
       (apply str)))

;; -------------------------------------------------------
;; Decode functions
;; -------------------------------------------------------

(defn decode-chunk [c]
  (let [pc (reduce #(+ (bit-shift-left %1 5) %2) (reverse (map #(bit-and-not % 32) c)))
        neg (= 1 (mod pc 2))]
    (/ (bit-shift-right (if neg (bit-not pc) pc) 1) 100000)))

(defn decode [polystring]
  (let [poly-ints (map #(- % 63) (map int polystring))
        poly-chunks (split poly-ints)
        decoded-chunks (map decode-chunk poly-chunks)]
    (->> decoded-chunks (map double) (partition 2) (combiner +)
         (vec->coords))))

;; -------------------------------------------------------
;; Encode functions
;; -------------------------------------------------------

(defn- invert-negative [int]
  (if (neg? int)
    (bit-not int)
    int))

(defn- partition-bits
  "takes an integer, and the number of bits in segments, and returns a seq of
   integers which correspond to the integer broken down into segments with the
   given number of bits"
  [n bits]
  (when (> n 0)
    (let [bits-int (reduce * (repeat bits 2))
          cur (mod n bits-int)
          rem (/ (- n cur) bits-int)]
      (cons cur (partition-bits rem bits)))))

(defn- pad-ints [ints]
  (let [end (last ints)]
    (->> ints
         drop-last
         (map (partial + 32))
         reverse
         (cons end)
         (map (partial + 63))
         reverse)))

(defn encode-coord [coord]
  (-> coord
      coord->int
      (bit-shift-left 1)
      invert-negative
      (partition-bits 5)
      pad-ints
      ints->str))

(defn compact-coords 
  "Takes a vector of coord vectors, and returns a vector of vectors of
   the difference from the previous coord. The format that polyline wants"
  ([coords]
     (let [c (reverse coords)]
       (when-let [x (first c)]
         (compact-coords x (rest c)))))
  ([x rem]
     (if-let [y (first rem)]
       (conj (compact-coords y (rest rem)) (vec (map - x y)))
       [x])))

(defn encode [coords] "Main encoding interface"
  (let [c (flatten (compact-coords coords))]
    (apply str (map encode-coord c))))