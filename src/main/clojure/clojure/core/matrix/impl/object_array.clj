(ns clojure.core.matrix.impl.object-array
  "Namespace for core.matrix implementation using nested Java object arrays.

   This implementation is useful if you want fast mutable arrays of arbitrary objects.

   Array format is defined as:
   - Top level object is a Java Object[] array
   - If the array is 1-dimensional each element is a scalar
   - Otherwise each element is an sub-array with identical shape (1 dimensional or more)"
  (:require [clojure.core.matrix.protocols :as mp]
            clojure.core.matrix.impl.persistent-vector
            [clojure.core.matrix.implementations :as imp]
            [clojure.core.matrix.impl.mathsops :as mops]
            [clojure.core.matrix.impl.wrappers :as wrap]
            [clojure.core.matrix.utils :as u]
            [clojure.core.matrix.macros :refer [error TODO is-object-array?]])
  (:import [java.util Arrays]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)
;; (set! *unchecked-math* :warn-on-boxed)

;; clojure.core.matrix implementation for Java Object arrays

;; general arrays are represented as nested arrays wrapped by a Java Object[] array
;; in which case all sub-arrays must have the same shape.
;;
;; Useful as a fast, mutable implementation.

(def ^:const OBJECT-ARRAY-CLASS (Class/forName "[Ljava.lang.Object;"))

(defn construct-object-array ^objects [data]
  (let [dims (long (mp/dimensionality data))]
    (cond
      (== dims 1)
        (object-array (mp/element-seq data))
      (== dims 0)
        (mp/get-0d data)
      :default
        (object-array (map construct-object-array (mp/get-major-slice-seq data))))))

(defn construct-nd ^objects [shape]
  (let [dims (long (count shape))]
        (cond
          (== 1 dims) (object-array (long (first shape)))
          (> dims 1)
            (let [n (long (first shape))
                  m (object-array n)
                  ns (next shape)]
              (dotimes [i n]
                (aset m i (construct-nd ns)))
              m)
          :else (error "Can't make a nested object array of dimensionality: " dims))))

(defn object-array-coerce
  "Coerce to object array format, avoids copying sub-arrays if possible."
  ([m]
  (if (> (long (mp/dimensionality m)) 0)
    (if (is-object-array? m)
      (let [^objects m m
            n (count m)]
        (loop [ret m i 0]
          (if (< i n)
            (let [mv (aget m i)
                  cmv (object-array-coerce mv)]
              (if (and (identical? m ret) (identical? mv cmv))
                (recur ret (inc i))
                (let [mm (u/copy-object-array m)]
                  (aset mm i cmv)
                  (recur mm (inc i)))))
            ret)))
      (object-array (map object-array-coerce (mp/get-major-slice-seq m))))
    (mp/get-0d m))))

(def ^Double ZERO 0.0)

(defmacro construct-object-vector [n]
  `(let [arr# (object-array ~n)]
     (Arrays/fill arr# ZERO)
     arr#))

(extend-protocol mp/PImplementation
  (Class/forName "[Ljava.lang.Object;")
    (implementation-key [m] :object-array)
    (meta-info [m]
      {:doc "Clojure.core.matrix implementation for Java Object arrays"})
    (new-vector [m length] (construct-object-vector (long length)))
    (new-matrix [m rows columns]
      (let [columns (long columns)
            m (object-array rows)]
        (dotimes [i rows]
          (aset m i (construct-object-vector columns)))
        m))
    (new-matrix-nd [m shape]
      (construct-nd shape))
    (construct-matrix [m data]
      (construct-object-array data))
    (supports-dimensionality? [m dims]
      (>= (long dims) 1)))


(extend-protocol mp/PDimensionInfo
  (Class/forName "[Ljava.lang.Object;")
    (dimensionality [m]
      (let [^objects m m]
        (+ 1 (if (empty? m) 0 (long (mp/dimensionality (aget m 0)))))))
    (is-vector? [m]
      (let [^objects m m]
        (or
         (== 0 (alength m))
         (== 0 (long (mp/dimensionality (aget m 0)))))))
    (is-scalar? [m] false)
    (get-shape [m]
      (let [^objects m m]
        (if (== 0 (alength m))
           1
           (cons (alength m) (mp/get-shape (aget m 0))))))
    (dimension-count [m x]
      (let [^objects m m
            x (long x)]
        (cond
          (== x 0)
            (alength m)
          (> x 0)
            (mp/dimension-count (aget m 0) (dec x))
          :else
            (error "Invalid dimension: " x)))))

;; explicitly specify we use the Object type
(extend-protocol mp/PTypeInfo
  (Class/forName "[Ljava.lang.Object;")
    (element-type [m]
      (let [^objects m m]
        (cond
         (== 1 (long (mp/dimensionality m))) Object
         :else (mp/element-type (aget m 0))))))


(extend-protocol mp/PIndexedAccess
  (Class/forName "[Ljava.lang.Object;")
    (get-1d [m x]
      (aget ^objects m (int x)))
    (get-2d [m x y]
      (mp/get-1d (aget ^objects m (int x)) y))
    (get-nd [m indexes]
      (let [^objects m m
            dims (long (count indexes))]
        (cond
          (== 1 dims)
            (aget m (int (first indexes)))
          (> dims 1)
            (mp/get-nd (aget m (int (first indexes))) (next indexes))
          (== 0 dims) m
          :else
            (error "Invalid dimensionality access with index: " (vec indexes))))))

(extend-protocol mp/PIndexedSetting
  (Class/forName "[Ljava.lang.Object;")
    (set-1d [m x v]
      (let [^objects arr (u/copy-object-array m)]
        (aset arr (int x) v)
        arr))
    (set-2d [m x y v]
      (let [^objects arr (u/copy-object-array m)
            x (int x)]
        (aset arr x (mp/set-1d (aget ^objects m x) y v))
        arr))
    (set-nd [m indexes v]
      (let [dims (long (count indexes))]
        (cond
          (== 1 dims)
            (let [^objects arr (u/copy-object-array m)
                  x (int (first indexes))]
              (aset arr (int x) v)
              arr)
          (> dims 1)
            (let [^objects arr (u/copy-object-array m)
                  x (int (first indexes))]
              (aset arr x (mp/set-nd (aget ^objects m x) (next indexes) v))
              arr)
          :else
            (error "Can't set on object array with dimensionality: " (count indexes)))))
    (is-mutable? [m] true))

(extend-protocol mp/PIndexedSettingMutable
  (Class/forName "[Ljava.lang.Object;")
    (set-1d! [m x v]
      (aset ^objects m (int x) v))
    (set-2d! [m x y v]
      (mp/set-1d! (aget ^objects m x) y v))
    (set-nd! [m indexes v]
      (let [^objects m m
            dims (long (count indexes))]
        (cond
          (== 1 dims)
            (aset m (int (first indexes)) v)
          (> dims 1)
            (mp/set-nd! (aget m (int (first indexes))) (next indexes) v)
          :else
            (error "Can't set on object array with dimensionality: " (count indexes))))))

(extend-protocol mp/PBroadcast
  (Class/forName "[Ljava.lang.Object;")
    (broadcast [m target-shape]
      (let [mshape (mp/get-shape m)
            dims (long (count mshape))
            tdims (long (count target-shape))]
        (cond
          (> dims tdims)
            (error "Can't broadcast to a lower dimensional shape")
          (not (every? identity (map == mshape (take-last dims target-shape))))
            (error "Incompatible shapes, cannot broadcast " (vec mshape) " to " (vec target-shape))
          :else
            (reduce
              (fn [m dup] (object-array (repeat dup m)))
              m
              (reverse (drop-last dims target-shape)))))))

(extend-protocol mp/PCoercion
  (Class/forName "[Ljava.lang.Object;")
    (coerce-param [m param]
      (object-array-coerce param)))

(extend-protocol mp/PMutableMatrixConstruction
  (Class/forName "[Ljava.lang.Object;")
    (mutable-matrix [m]
      (if (> (long (mp/dimensionality m)) 1)
        (object-array (map mp/mutable-matrix m))
        (object-array (map mp/get-0d m)))))

(extend-protocol mp/PConversion
  (Class/forName "[Ljava.lang.Object;")
    (convert-to-nested-vectors [m]
      (mapv mp/convert-to-nested-vectors (seq m))))

(extend-protocol mp/PMatrixSlices
  (Class/forName "[Ljava.lang.Object;")
    (get-row [m i]
      (mp/get-major-slice m i))
    (get-column [m i]
      (mp/get-slice m 1 i))
    (get-major-slice [m i]
      (aget ^objects m (long i)))
    (get-slice [m dimension i]
      (let [dimension (long dimension)]
        (if (== dimension 0)
          (mp/get-major-slice m i)
          (let [sd (dec dimension)]
            (mapv #(mp/get-slice % sd i) m))))))

(extend-protocol mp/PSliceView
  (Class/forName "[Ljava.lang.Object;")
    (get-major-slice-view [m i]
      (let [^objects m m
            v (aget m i)]
        (if (mp/is-scalar? v)
          (wrap/wrap-slice m i)
          v))))

(extend-protocol mp/PSliceSeq
  (Class/forName "[Ljava.lang.Object;")
    (get-major-slice-seq [m]
      (let [^objects m m]
        (if (and (> 0 (alength m)) (== 0 (long (mp/dimensionality (aget m 0)))))
          (seq (map mp/get-0d m))
          (seq m)))))

(extend-protocol mp/PElementCount
 (Class/forName "[Ljava.lang.Object;")
   (element-count [m]
     (let [^objects m m
           n (alength m)]
       (if (== n 0)
         0
         (* n (mp/element-count (aget m 0))))))) ;; not possible to remove boxing warning, may be bigger than 2^64

(extend-protocol mp/PValidateShape
  (Class/forName "[Ljava.lang.Object;")
    (validate-shape [m]
      (let [^objects m m
            shapes (map mp/validate-shape (seq m))]
        (if (mp/same-shapes? shapes)
          (cons (alength m) (first shapes))
          (error "Inconsistent shapes for sub arrays in object array")))))

(extend-protocol mp/PFunctionalOperations
  (Class/forName "[Ljava.lang.Object;")
    (element-seq [m]
      (let [^objects m m]
        (cond
          (== 0 (alength m))
            '()
          (> (long (mp/dimensionality (aget m 0))) 0)
            (mapcat mp/element-seq m)
          :else
            m)))
    (element-map
      ([m f]
        (object-array (map #(mp/element-map % f) m)))
      ([m f a]
        (object-array (map #(mp/element-map %1 f %2) m (mp/get-major-slice-seq a))))
      ([m f a more]
        (object-array (apply map #(mp/element-map %1 f %2 %&) m (mp/get-major-slice-seq a) (map mp/get-major-slice-seq more)))))
    (element-map!
      ([m f]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)]
            (if (mp/is-mutable? s)
              (mp/element-map! s f)
              (aset m i (mp/element-map s f)))))
        m)
      ([m f a]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)
                as (mp/get-major-slice a i)]
            (if (mp/is-mutable? s)
              (mp/element-map! s f as)
              (aset m i (mp/element-map s f as)))))
        m)
      ([m f a more]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)
                as (mp/get-major-slice a i)
                ms (map #(mp/get-major-slice % i) more)]
            (if (mp/is-mutable? s)
              (apply mp/element-map! s f as ms)
              (aset m i (apply mp/element-map s f as ms)))))
        m))
    (element-reduce
      ([m f]
        (reduce f (mp/element-seq m)))
      ([m f init]
        (reduce f init (mp/element-seq m)))))

(extend-protocol mp/PMapIndexed
  (Class/forName "[Ljava.lang.Object;")
    (element-map-indexed
      ([ms f]
        (object-array (map (fn [i m]
                             (mp/element-map-indexed m #(apply f (cons i %1) %&)))
                           (range (count ms)) ms)))
      ([ms f as]
        (object-array (map (fn [i m a]
                             (mp/element-map-indexed m #(apply f (cons i %1) %&) a))
                           (range (count ms)) ms (mp/get-major-slice-seq as))))
      ([ms f as more]
        (object-array (apply map (fn [i m a & mr]
                                   (mp/element-map-indexed
                                     m #(apply f (cons i %1) %&) a mr))
                             (range (count ms)) ms
                             (mp/get-major-slice-seq as)
                             (map mp/get-major-slice-seq more)))))
    (element-map-indexed!
      ([m f]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)]
            (if (mp/is-mutable? s)
              (mp/element-map-indexed! s #(apply f (cons i %1) %&))
              (aset m i (mp/element-map-indexed s #(apply f (cons i %1) %&))))))
        m)
      ([m f a]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)
                as (mp/get-major-slice a i)]
            (if (mp/is-mutable? s)
              (mp/element-map-indexed! s #(apply f (cons i %1) %&) as)
              (aset m i (mp/element-map-indexed s #(apply f (cons i %1) %&) as)))))
        m)
      ([m f a more]
        (dotimes [i (count m)]
          (let [^objects m m
                s (aget m i)
                as (mp/get-major-slice a i)
                ms (map #(mp/get-major-slice % i) more)]
            (if (mp/is-mutable? s)
              (apply mp/element-map-indexed! s #(apply f (cons i %1) %&) as ms)
              (aset m i (apply mp/element-map-indexed s #(apply f (cons i %1) %&) as ms)))))
        m)))


(imp/register-implementation (object-array [1]))
