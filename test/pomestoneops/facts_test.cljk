(ns pomestoneops.facts-test
  (:require [clojure.test :refer [deftest is are testing]]
            [pomestoneops.facts :as facts]))

(deftest supply-category-lookup
  (testing "Lookup valid supply category"
    (let [c (facts/supply-category-by-id "seedling")]
      (is (= "seedling" (:id c)))
      (is (= "苗木" (:name c)))))

  (testing "Lookup invalid supply category"
    (is (nil? (facts/supply-category-by-id "unknown")))))

(deftest supply-category-cost-thresholds
  (testing "Category-specific cost thresholds"
    (are [id expected] (= expected (:cost-threshold (facts/supply-category-by-id id)))
      "seedling"    500
      "fertilizer"  500
      "equipment"   1000)))

(deftest default-cost-threshold-value
  (testing "Default fallback threshold matches the conservative baseline"
    (is (= 500 facts/default-cost-threshold))))

(deftest fruit-class-lookup
  (testing "Lookup valid fruit class"
    (are [id expected-name] (= expected-name (:name (facts/fruit-class-by-id id)))
      "apple"   "リンゴ"
      "pear"    "西洋ナシ"
      "quince"  "マルメロ"
      "peach"   "モモ"
      "plum"    "スモモ"
      "cherry"  "サクランボ"
      "apricot" "アンズ"))

  (testing "Pome fruits are grouped :pome, stone fruits :stone"
    (are [id expected-group] (= expected-group (:group (facts/fruit-class-by-id id)))
      "apple"   :pome
      "pear"    :pome
      "quince"  :pome
      "peach"   :stone
      "plum"    :stone
      "cherry"  :stone
      "apricot" :stone))

  (testing "Lookup invalid fruit class"
    (is (nil? (facts/fruit-class-by-id "unknown")))))
