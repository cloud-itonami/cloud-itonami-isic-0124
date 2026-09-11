(ns pomestoneops.facts
  "Reference facts for pome- and stone-fruit orchard operations
  coordination: supply category cost policy and pome/stone-fruit species
  classification. This namespace contains pure lookup functions for
  domain reference data -- the Governor and Advisor consult these instead
  of inventing thresholds. Mirrors `citrusops.facts`
  (cloud-itonami-isic-0123) in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (grower/orchard-manager)."
  {"seedling"
   {:id "seedling" :name "苗木" :cost-threshold 500}

   "fertilizer"
   {:id "fertilizer" :name "肥料" :cost-threshold 500}

   "equipment"
   {:id "equipment" :name "設備" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def fruit-classes
  "End-use classes this actor's orchard/block records may cover (ISIC
  0124: growing of pome fruits and stone fruits). Pome fruits: apple,
  pear, quince. Stone fruits: peach, plum, cherry, apricot."
  {"apple"   {:id "apple" :name "リンゴ" :group :pome}
   "pear"    {:id "pear" :name "西洋ナシ" :group :pome}
   "quince"  {:id "quince" :name "マルメロ" :group :pome}
   "peach"   {:id "peach" :name "モモ" :group :stone}
   "plum"    {:id "plum" :name "スモモ" :group :stone}
   "cherry"  {:id "cherry" :name "サクランボ" :group :stone}
   "apricot" {:id "apricot" :name "アンズ" :group :stone}})

(defn fruit-class-by-id [id]
  (get fruit-classes id))
