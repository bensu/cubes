(ns cubes.squares-test
  (:require-macros [facilier.helper :as helper])
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures]]
            [cljs.reader :as reader]
            [cubes.squares :as sq]))

;; ======================================================================
;; Logic

(def db (reader/read-string (helper/load-edn "test/resources/sample-db.edn")))

(deftest plan-moves
  (let [goal [11 9]
        plan [{:type :move, :move 11, :to 9}]]
    (is (= plan (sq/plan-moves goal db)))))
