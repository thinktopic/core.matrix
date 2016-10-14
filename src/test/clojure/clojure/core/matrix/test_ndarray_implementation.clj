(ns clojure.core.matrix.test-ndarray-implementation
  (:require [clojure.core.matrix.operators :as op]
            [clojure.core.matrix.compliance-tester :as ct]
            [clojure.core.matrix.protocols :as mp]
            [clojure.core.matrix.generic :as gen]
            clojure.core.matrix.impl.persistent-vector
            [clojure.core.matrix.impl.ndarray]
            [clojure.core.matrix.impl.ndarray-magic :as magic]
            [clojure.core.matrix.impl.ndarray-macro :as macro]
            [clojure.test :refer :all]
            [clojure.core.matrix :refer :all]
            [clojure.core.matrix.impl.ndarray :refer :all]
            [clojure.core.matrix.impl.ndarray-double :refer :all]
            [clojure.core.matrix.impl.ndarray-object :refer :all]))

;; Tests for the NDArray implementation

(defn magic1 [a b]
  (let [c (mp/clone a)]
    (magic/specialize :double
      (macro/loop-over [b c]
        (let [x (aget c-data c-idx)
              y (aget b-data b-idx)]
          (aset c-data c-idx
                (Math/sqrt
                 (+ 1 (+ (* 0.5 (Math/sin x))
                         (* 0.5 (Math/cos y)))))))))
    c))

(deftest magic-specialize-test
  (let [n 3
        t (->> (* n n)
               range
               (partition n)
               (map vec)
               vec)
        a (ndarray-double t)
        b (ndarray-double t)
        m (magic1 a b)
        x (mget m 2 2)
        diff (Math/abs (- x 1.19))]
    (is (< diff 0.01))))

(deftest add-product-test
  (testing "vector add-product"
    (is (equals [7] (add-product (array :ndarray [1]) (array :ndarray [2]) (array :ndarray [3]))))
    (is (equals [7] (add-product! (array :ndarray [1]) (array :ndarray [2]) (array :ndarray [3]))))))

(deftest c-strides-test
  (are [strides shape] (= strides (vec (c-strides shape)))
       [1]        [3]
       [2 1]      [3 2]
       [6 2 1]    [4 3 2]
       [24 6 2 1] [5 4 3 2]))

(deftest empty-ndarray-test
  (let [a (empty-ndarray [3 2])]
    (is (= [nil nil nil nil nil nil] (vec (.data a))))
    (is (= 2 (.ndims a)))
    (is (= [3 2] (vec (.shape a))))
    (is (= [2 1] (vec (.strides a))))))

(deftest regressions
  (let [m (empty-ndarray [2 2])
        vm [[1 2] [3 4]]]
    (is (thrown? Throwable (esum m)))
    (assign! m vm)
    (is (= vm (coerce [] m)))
    (is (== 10 (esum m))))
  (testing "esq and ereduce"
    (let [m (matrix :ndarray [3])
          es (eseq m)]
      (is (== 1 (count es)))
      (is (== 3 (mget m 0)))
      (is (== 3 (ereduce + 0 m)))
      (is (== 3 (reduce + 0 es))))
    (let [m (matrix :ndarray [[1 2] [3 4]])
          es (eseq m)]
      (is (== 10 (ereduce + m)))
      (is (== 4 (ereduce (fn [acc _] (inc acc)) 0 m)))
      (is (== 4 (ereduce (fn [acc _] (inc acc)) 0 (eseq m))))))

  (testing "Elementwise divide"
    (is (equals [2 2] (div (array :ndarray [6 4]) [3 2])))))

(deftest test-ndarray-base
    (testing "construction"
      (is (= [3 3] (seq (shape (empty-ndarray [3 3]))))))
    (testing "getters"
      (is (= nil (mget (empty-ndarray [3 3]) 2 2)))
      (is (= nil (mget (empty-ndarray [3 3 3]) 1 1 1))))
    (testing "setters"
      (let [m (empty-ndarray [2 2])]
        (mset! m 0 0 1)
        (is (== 1.0 (mget m 0 0)))))
    (testing "slices"
      (is (= [[nil nil] [nil nil]]
             (map #(coerce [] %)
                  (slices (empty-ndarray [2 2])))))))

(deftest test-add-sub
  (let [a (array :ndarray [1 2])]
    (add! a 10)
    (sub! a [3 4])
    (is (equals a [8 8]))))

(deftest test-fill
  (let [a (array :ndarray [[1 2] [3 4]])]
    (is (equals [[9 9] [9 9]] (fill a 9)))
    (is (equals [[9 9] [9 9]] (fill a [9 9])))
    (is (equals [[1 2] [3 4]] a))))

(deftest test-mmul-scalar ;; for issue #287
  (let [a (array :ndarray [[1 2] [3 4]])]
    (is (equals a (mmul a 1)))))

(deftest test-contained-vectors
  (let [a (array :ndarray :foo)]
    (mset! a [1 2 3])
    (is (== 1 (ecount a)))
    (is (= [1 2 3] (mget a)))
    (is (= [] (shape a)))
    (is (equals 3 (emap count a)))))

(deftest test-seq
  (is (= (-> (array :ndarray [[1 2] [3 4]])
             seq
             second
             mp/persistent-vector-coerce)
         [3 4]))
  (is (= [1 2] (seq (array :ndarray [1 2]))))
  )

(deftest test-slice-on-1d
  (is (scalar? (slice (array :ndarray [1 2 3]) 0))))

(deftest test-assign
  (let [m (empty-ndarray [2 2 2])
        vm [[[0 1] [2 3]] [[4 5] [6 7]]]]
    (assign! m vm)
    (is (= (eseq m) (range 8)))))

(deftest test-3d-emap-indexed
  (let [m (empty-ndarray [2 2 2])
        vm [[[0 1] [2 3]] [[4 5] [6 7]]]]
    (assign! m vm)
    (is (equals [[[0 1] [2 3]] [[4 5] [6 7]]] (emap-indexed (fn [_     x] x) m)))
    (is (equals [[[0 0] [0 0]] [[1 1] [1 1]]] (emap-indexed (fn [[i _ _] _] i) m)))
    (is (equals [[[0 0] [1 1]] [[0 0] [1 1]]] (emap-indexed (fn [[_ j _] _] j) m)))
    (is (equals [[[0 1] [0 1]] [[0 1] [0 1]]] (emap-indexed (fn [[_ _ k] _] k) m)))))

(deftest test-object-emap
  (let [m (new-array :ndarray [2 2])
        vecs (for [i (range 4)] [i (inc i)])]
    (assign-array! m (object-array vecs))
    (is (equals [[2 2] [2 2]] (emap count m)))))

(deftest test-object-emap-indexed
  (let [m (new-array :ndarray [2 2])
        vecs (for [i (range 4)] [i (inc i)])]
    (assign-array! m (object-array vecs))
    (is (equals [[2 2] [2 2]] (emap-indexed #(count %2) m)))
    (is (equals [[0 0] [1 1]] (emap-indexed (fn [[i _] _] i) m)))
    (is (equals [[0 1] [0 1]] (emap-indexed (fn [[_ j] _] j) m)))))

#_(deftest test-helper-functions
  (is (== 35 (calc-index [1 5] (long-array [100 30]))))
  (is (== 10101 (calc-index [1 1 1] (long-array [200 100 100])))))

(deftest test-transpose
  (let [m (new-array :ndarray [5 6 7])]
    (is (= [5 6 7] (seq (shape m))))
    (is (= [7 6 5] (seq (shape (transpose m))))))
  (let [m (matrix :ndarray [[1 2] [3 4]])]
    (is (equals [[1 3] [2 4]] (transpose m)))))

(defn get-primitive-ndarrays []
  [(empty-ndarray-double [3 3])
   ;(empty-ndarray-long [3 3])
   ;(empty-ndarray-float [3 3])  ;; TODO add back when NDArray loading is fixed
   ])

(deftest default-values
  (is (nil? (gen/default-value :ndarray)))
  (is (= 0.0 (gen/default-value :ndarray-double)))
  ;(is (= 0 (gen/default-value :ndarray-long)))   ;; TODO add back when NDArray loading is fixed
  )

(deftest regressions-2
  (is (= 3 (-> [[1 2] [3 4]]
               array
               transpose
               slices
               first
               (mget 1))))
  (is (equals [10] (add-product! (array :ndarray [4]) [2] [3])))
  (is (equals [30 70 110] (mmul (array :ndarray-double [[1 2 3 4] [5 6 7 8] [9 10 11 12]]) [1 2 3 4])))
  (is (equals [10] (add-product (array :ndarray [4]) [2] [3]))))

(deftest test-regression-239
  (let [m (array :ndarray [[1 2] [3 4]])]
    (is (equals m (coerce m (array :vectorz [[1 2] [3 4]]))))))

(deftest test-regression-245
  ;; problem with equality testing
  (is (e== (array :ndarray [[1 0 0] [0 0 2] ]) (array :ndarray [[1 0 0] [0 0 2]])))
  (is (not (e== (array :ndarray [[1 0 0] [0 0 0] ]) (array :ndarray [[1 0 0] [0 9 0]])))))

(deftest test-slicing-views
  (let [a (array :ndarray [[1 2 3 4] [5 6 7 8] [9 10 11 12]])]
     (is (equals [2 6 10] (clojure.core.matrix/slice-view a 1 1)))))

(deftest test-select-views
  (let [a (array :ndarray [[1 2 3 4] [5 6 7 8] [9 10 11 12]])]
     (is (equals [2 6 10] (select-view a :all 1)))))

(deftest test-inverse
  (is (e== (invert-double (ndarray-double [[0 1 0] [1 1 0] [1 -1 -1]])) 
           (ndarray-double [[-1.0,1.0,0.0],[1.0,-0.0,0.0],[-2.0,1.0,-1.0]]))))

(deftest ndarray-test
  (ct/test-ndarray-implementation (empty-ndarray [3 3])))

;; run complicance tests
(deftest compliance-test
  (ct/compliance-test (empty-ndarray [3 3])))

(deftest compliance-test-primitives
  (doseq [m (get-primitive-ndarrays)]
    (ct/compliance-test m)))


