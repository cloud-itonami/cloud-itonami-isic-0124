(ns pomestoneops.render-html
  "Build-time renderer for the PomeStoneOps **operator console**
  (`docs/samples/operator-console.html`).

  ## What is real here

  Every disposition, every audit fact, every ledger row and every gate row
  on the generated page is produced by RUNNING this repo's actual actor at
  build time. Nothing on the page is hand-typed domain data.

  - The store is a real `pomestoneops.store/mem-store`, seeded with the
    orchard ids that ALREADY exist in this repo: `\"orchard-001\"` is the
    exact record `pomestoneops.sim/demo` seeds, and `\"orchard-002\"` /
    \"New Orchard Block\" is the record `test/pomestoneops/store_test.cljc`
    registers via `store/add-orchard`. `:fruit-class` values are keys of
    `pomestoneops.facts/fruit-classes`. No invented ids: `check-provenance!`
    throws if any scenario names a subject the store does not already have
    registered.
  - The actor is built by `pomestoneops.operation/build` and invoked with
    `(actor request context)` -- byte-for-byte the calling convention
    `pomestoneops.sim/demo` uses. Each call runs the real chain
    advisor -> governor -> phase gate.
  - The ledger table is the concatenation of the `:audit` vectors the runs
    returned. Each row prints the fact's OWN keys; no key is added.
  - The action-gate, phase-matrix, cost-threshold and fruit-class tables are
    derived from live vars/functions (`governor/known-ops`,
    `governor/blocked-ops`, `governor/always-escalate-ops`,
    `governor/all-recognized-ops`, `governor/confidence-floor`,
    `phase/gate` actually invoked for every phase x op, and
    `facts/supply-categories` / `facts/fruit-classes`), not from prose.
  - After every run finishes, each seeded orchard row is re-read out of the
    store with `store/registered-orchard` and rendered from that read-back.
    That read-back is the ground truth the runs left behind: it shows the
    rows UNCHANGED, because `operation/run-operation` returns a proposed
    commit `:record` and this repo has no store write path -- the actor
    proposes, it does not persist. The commit records are rendered from the
    run results, labelled as proposed writes.

  ## Not exercised, and why (stated rather than faked)

  - `langgraph.graph/run*` is NOT used: this repo's `:deps` map is empty, so
    langgraph is not on the classpath at all, and `operation/build` returns a
    plain invoke function rather than a StateGraph (see its docstring: the
    langgraph StateGraph / `interrupt-before` / checkpoint-resume wiring is
    explicitly deferred). There is therefore no thread-id or resume? to pass.
    An escalated run here ends at `:approval-requested`; the console shows
    that fact, and does not pretend an approval round-trip happened.
  - Hard rule `:orchard-not-registered` is not exercised: triggering it
    requires passing an orchard-id that is absent from the seed, which the
    provenance rule above forbids.
  - Hard rule `:no-execution` is not exercised: `advisor/MockAdvisor` sets
    `:effect :propose` on every branch, so no proposal it can emit reaches
    that check.

  ## Determinism

  No timestamps, no randomness, no wall-clock or environment content. Sets
  are sorted before rendering and maps are canonicalised into sorted-maps
  before `pr-str`. Two consecutive runs are byte-identical; verified with
  `clojure -M:render-html /tmp/a.html && clojure -M:render-html /tmp/b.html
  && diff /tmp/a.html /tmp/b.html`.

  ## How this was verified

  `clojure -M:dev:run` was run first to read the real demo output, and the
  dispositions this renderer records were checked against it: the phase-0
  `:log-orchard-record` scenario below reproduces the demo's `:escalate`.
  `clojure -M:test` (31 tests / 105 assertions, 0 failures) passes unchanged."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [pomestoneops.facts :as facts]
            [pomestoneops.governor :as governor]
            [pomestoneops.operation :as operation]
            [pomestoneops.phase :as phase]
            [pomestoneops.store :as store]))

;; --------------------------------------------------------------------------
;; seed -- ids already present in this repo (sim.cljc / store_test.cljc)
;; --------------------------------------------------------------------------

(def orchard-seed
  "Store seed. Provenance of every value:
     orchard-001 -> `pomestoneops.sim/demo`'s `:initial-orchards`
     orchard-002 -> `store_test.cljc` add-orchard-registers-new-orchard
   `:fruit-class` values are ids from `facts/fruit-classes`."
  [["orchard-001" {:id "orchard-001" :name "Test Orchard Block" :fruit-class "apple"}]
   ["orchard-002" {:id "orchard-002" :name "New Orchard Block" :fruit-class "peach"}]])

(def seeded-ids (mapv first orchard-seed))

(def actor-context
  "Base context, taken verbatim from `pomestoneops.sim/demo`."
  {:actor-id "pome-stone-ops-01" :role :orchard-operator})

(defn- ctx [phase] (assoc actor-context :phase phase))

;; --------------------------------------------------------------------------
;; scenarios -- every :orchard-id is a seeded id (enforced below)
;; --------------------------------------------------------------------------

(def scenarios
  "Each scenario is one full actor run. `:why` documents the intent; the
  outcome column on the page comes from the run, never from `:why`."
  [{:label "Clean record, simulation phase"
    :why "Reproduces `clojure -M:dev:run` exactly: governor-clean, but phase-0 refuses to let anything commit autonomously."
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 500
              :notes "healthy yield"}
    :context (ctx :phase-0)}

   {:label "Clean record, reduced supervision"
    :why "Same request, phase-2: the full clean lifecycle -- advisor proposes, governor clears it, phase gate lets it commit, a commit record is produced."
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 500
              :notes "healthy yield"}
    :context (ctx :phase-2)}

   {:label "Field-operation scheduling, full autonomy"
    :why "Routine coordination op at phase-3."
    :request {:op :schedule-field-operation
              :orchard-id "orchard-002"
              :requested-date "2026-03-15"
              :operation-type "pruning"}
    :context (ctx :phase-3)}

   {:label "Supply order at category threshold"
    :why "cost is exactly the seedling threshold; `registry/cost-exceeds-threshold?` is exclusive at the boundary, so it must NOT escalate."
    :request {:op :order-supplies
              :orchard-id "orchard-002"
              :category "seedling"
              :cost 500}
    :context (ctx :phase-2)}

   {:label "Supply order above category threshold"
    :why "equipment threshold is 1000; 1500 exceeds it, so the governor marks it high-stakes and it escalates for human sign-off."
    :request {:op :order-supplies
              :orchard-id "orchard-001"
              :category "equipment"
              :cost 1500}
    :context (ctx :phase-2)}

   {:label "Crop-health concern, full autonomy"
    :why "`:flag-crop-health-concern` is in `governor/always-escalate-ops`: it escalates even at phase-3 with high confidence."
    :request {:op :flag-crop-health-concern
              :orchard-id "orchard-002"
              :concern "fire blight suspected on rows 4-7"}
    :context (ctx :phase-3)}

   {:label "HARD hold -- direct field-equipment operation"
    :why "Exercised at phase-3 (the most permissive phase) to show the block is permanent, not a supervision setting."
    :request {:op :operate-field-equipment
              :orchard-id "orchard-001"}
    :context (ctx :phase-3)}

   {:label "HARD hold -- finalize spray application"
    :why "Also exercised at phase-3. Agronomic authority stays human, unconditionally."
    :request {:op :finalize-spray-application
              :orchard-id "orchard-002"}
    :context (ctx :phase-3)}

   {:label "HARD hold -- non-positive logged quantity"
    :why "`registry/orchard-count-non-positive?` rejects a zero count as bad data rather than accepting it."
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 0
              :notes "sensor returned zero"}
    :context (ctx :phase-2)}

   {:label "HARD hold -- op outside the closed allowlist"
    :why "An op the advisor does not recognise still gets a hard rejection from the governor's closed allowlist, not a silent pass-through."
    :request {:op :sell-harvest-futures
              :orchard-id "orchard-002"}
    :context (ctx :phase-3)}])

;; --------------------------------------------------------------------------
;; run
;; --------------------------------------------------------------------------

(defn- check-provenance!
  "Input provenance gate: refuse to render if any scenario names a subject
  that is not already registered in the store. This is what makes \"no
  invented ids\" a property of the build rather than a promise."
  [st]
  (doseq [{:keys [label request]} scenarios]
    (when-not (store/registered-orchard st (:orchard-id request))
      (throw (ex-info "scenario subject is not registered in the store seed"
                      {:scenario label :orchard-id (:orchard-id request)
                       :seeded seeded-ids})))))

(defn run-all
  "Seed the store, run every scenario through the real actor, then read the
  orchard rows back out of the store. Returns
  {:store .. :runs [..] :readback [..]}."
  []
  (let [st (store/mem-store {:initial-orchards (into {} orchard-seed)})
        _  (check-provenance! st)
        ;; sim.cljc's convention: build once, then (actor request context)
        actor (operation/build st)
        runs (mapv (fn [sc] (assoc sc :result (actor (:request sc) (:context sc))))
                   scenarios)]
    {:store st
     :runs runs
     ;; ground truth AFTER the runs, read back through the Store protocol
     :readback (mapv (fn [id] [id (store/registered-orchard st id)]) seeded-ids)}))

;; --------------------------------------------------------------------------
;; rendering helpers
;; --------------------------------------------------------------------------

(defn- canonical
  "Recursively replace maps/sets with sorted variants so `pr-str` output is
  stable across runs regardless of hash ordering."
  [x]
  (cond
    (map? x)    (into (sorted-map) (map (fn [[k v]] [k (canonical v)])) x)
    (set? x)    (into (sorted-set) (map canonical) x)
    (vector? x) (mapv canonical x)
    (seq? x)    (mapv canonical x)
    :else       x))

(defn- ->s [x]
  (cond
    (nil? x)     "—"
    (string? x)  x
    (keyword? x) (str x)
    (number? x)  (str x)
    :else        (pr-str (canonical x))))

(defn- esc [x]
  (-> (->s x)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- cell
  "A cell is either a plain value or {:v value :class \"ok\"} / {:v v :code true}."
  [tag c]
  (let [{:keys [v class code]} (if (and (map? c) (contains? c :v)) c {:v c})
        inner (if code (str "<code>" (esc v) "</code>") (esc v))]
    (str "<" tag (when class (str " class=\"" (esc class) "\"")) ">" inner "</" tag ">")))

(defn- table [headers rows]
  (str "<table>\n<thead><tr>"
       (str/join (map #(cell "th" %) headers))
       "</tr></thead>\n<tbody>\n"
       (str/join "\n" (for [r rows] (str "<tr>" (str/join (map #(cell "td" %) r)) "</tr>")))
       "\n</tbody>\n</table>\n"))

(defn- disposition-class [d]
  (case d :commit "ok" :escalate "warn" :hold "err" "muted"))

;; --------------------------------------------------------------------------
;; sections -- all derived from live values / live runs
;; --------------------------------------------------------------------------

(defn- run-rows [runs]
  (for [{:keys [label request context result]} runs
        :let [d (:disposition result)
              v (:verdict result)
              hold (first (filter #(= :governor-hold (:t %)) (:audit result)))
              esc-fact (first (filter #(= :approval-requested (:t %)) (:audit result)))]]
    [label
     {:v (:op request) :code true}
     {:v (:orchard-id request) :code true}
     {:v (:phase context) :code true}
     {:v d :class (disposition-class d)}
     ;; why: the governor/phase machinery's own words, never prose
     (cond
       (seq (:basis hold)) {:v (:basis hold) :code true}
       (:reason esc-fact)  {:v (:reason esc-fact) :code true}
       :else               nil)
     {:v (:confidence v) :class "num"}
     {:v (if (:hard? v) "hard" "—") :class (when (:hard? v) "critical")}
     {:v (if (:high-stakes? v) "yes" "—") :class (when (:high-stakes? v) "warn")}]))

(defn- ledger-rows
  "Flattened audit ledger. Each row prints the fact's own keys -- the detail
  column is the fact itself minus the columns already shown."
  [runs]
  (let [facts (for [{:keys [label result]} runs, f (:audit result)] [label f])]
    (map-indexed
     (fn [i [label f]]
       [{:v (inc i) :class "num"}
        label
        {:v (:t f) :code true}
        {:v (or (:op f)) :code true}
        {:v (or (:subject f) (:orchard-id f)) :code true}
        {:v (dissoc f :t :op :subject :orchard-id) :code true}])
     facts)))

(defn- ledger-count [runs] (reduce + 0 (map (comp count :audit :result) runs)))

(defn- commit-record-rows [runs]
  (for [{:keys [label result]} runs
        :when (:record result)
        :let [r (:record result)]]
    [label
     {:v (:effect r) :code true}
     {:v (:path r) :code true}
     {:v (:value r) :code true}]))

(defn- action-gate-rows
  "Derived by asking the live governor vars about every recognised op."
  []
  (for [op (sort-by str governor/all-recognized-ops)]
    (let [blocked?  (contains? governor/blocked-ops op)
          allowed?  (contains? governor/known-ops op)
          always?   (contains? governor/always-escalate-ops op)]
      [{:v op :code true}
       (cond blocked? {:v "blocked (hard, permanent)" :class "critical"}
             allowed? {:v "allowlisted" :class "ok"}
             :else    {:v "unrecognised" :class "err"})
       (if always? {:v "always escalates" :class "warn"} {:v "—" :class "muted"})
       {:v (if blocked? "—" ":propose") :code true}])))

(defn- phase-matrix-rows
  "Derived by actually invoking `phase/gate` for every phase x allowlisted op
  with a governor-clean `:commit` as the pre-gate disposition."
  [phases]
  (for [op (sort-by str governor/known-ops)]
    (into [{:v op :code true}]
          (for [ph phases]
            (let [{:keys [disposition reason]} (phase/gate ph {:op op} :commit)]
              {:v (str (->s disposition)
                       (when reason (str " · " (->s reason))))
               :class (disposition-class disposition)})))))

(defn- cost-threshold-rows []
  (for [[id c] (sort-by key facts/supply-categories)]
    [{:v id :code true} (:name c) {:v (:cost-threshold c) :class "amt"}]))

(defn- fruit-class-rows []
  (for [[id c] (sort-by key facts/fruit-classes)]
    [{:v id :code true} (:name c) {:v (:group c) :code true}]))

(defn- readback-rows [readback]
  (for [[id row] readback]
    [{:v id :code true}
     (:name row)
     {:v (:fruit-class row) :code true}
     {:v (get-in facts/fruit-classes [(:fruit-class row) :name]) }
     {:v row :code true}]))

;; --------------------------------------------------------------------------
;; page
;; --------------------------------------------------------------------------

(def ^:private phases [:phase-0 :phase-1 :phase-2 :phase-3])

(defn render
  "Render the whole console to an HTML string."
  []
  (let [{:keys [runs readback]} (run-all)
        n-facts (ledger-count runs)
        hold-rules (sort-by str (distinct (mapcat (fn [{:keys [result]}]
                                                    (mapcat :basis (:audit result)))
                                                  runs)))
        tally (frequencies (map (comp :disposition :result) runs))]
    (str
     "<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n"
     "<meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
     "<meta name=\"color-scheme\" content=\"light\">\n"
     "<title>Operator console | cloud-itonami-isic-0124</title>\n"
     "<meta name=\"description\" content=\"PomeStoneOps operator console — rendered at build time from real actor runs (ISIC 0124).\">\n"
     "<style>\n" (skin/dds+skin) "\n</style>\n"
     "</head>\n<body>\n"

     "<div class=\"bar\">"
     "<span class=\"badge\">ISIC 0124</span>"
     "<span class=\"muted\">pome- &amp; stone-fruit orchard operations</span>"
     "</div>\n"

     "<h1>Operator console</h1>\n"
     "<p class=\"subtitle\">Generated at build time by <code>pomestoneops.render-html</code>. "
     "Every row below is the output of a real <code>pomestoneops.operation/build</code> run "
     "over a real <code>pomestoneops.store/mem-store</code>. No mock rows.</p>\n"

     "<div class=\"banner\">\n"
     "<p><strong>How to read this page.</strong> "
     (esc (count runs)) " operations were run through the actor "
     "(advisor → governor → phase gate). They produced " (esc n-facts)
     " audit facts. Dispositions: "
     (str/join " · " (for [[d n] (sort-by (comp str key) tally)]
                       (str "<span class=\"" (disposition-class d) "\">" (esc d)
                            "</span> " (esc n))))
     ".</p>\n"
     "<p>The actor <em>proposes</em>; it never executes. A <code>:commit</code> disposition "
     "yields a proposed write record, which this build does not persist — the store read-back "
     "at the bottom of the page shows the registry exactly as the runs left it.</p>\n"
     "</div>\n"

     "<h2>Operations run</h2>\n"
     (table ["Scenario" "Op" "Subject" "Phase" "Disposition" "Governor / phase basis"
             "Confidence" "Hard?" "High stakes?"]
            (run-rows runs))

     "<h2>Hard holds observed</h2>\n"
     "<p>Rule names collected from the <code>:basis</code> of the "
     "<code>:governor-hold</code> facts the runs actually emitted — not from a written list.</p>\n"
     "<ul>\n"
     (str/join "\n" (for [r hold-rules]
                      (str "<li><code class=\"critical\">" (esc r) "</code></li>")))
     "\n</ul>\n"
     "<p class=\"muted\">Two further hard rules exist but are not exercised here. "
     "<code>:orchard-not-registered</code> would require passing an orchard-id absent from "
     "the seed, which this build forbids. <code>:no-execution</code> cannot be reached by "
     "<code>advisor/MockAdvisor</code>, which sets <code>:effect :propose</code> on every branch.</p>\n"

     "<h2>Audit ledger</h2>\n"
     "<p>" (esc n-facts) " facts, in emission order. The detail column is each fact's own "
     "remaining keys.</p>\n"
     (table ["#" "Scenario" "Fact" "Op" "Subject" "Detail"] (ledger-rows runs))

     "<h2>Proposed commit records</h2>\n"
     (let [rows (commit-record-rows runs)]
       (if (seq rows)
         (table ["Scenario" "Effect" "Path" "Value"] rows)
         "<p class=\"muted\">No run reached <code>:commit</code>.</p>\n"))

     "<h2>Action gate</h2>\n"
     "<p>Derived from <code>governor/all-recognized-ops</code>, "
     "<code>governor/known-ops</code>, <code>governor/blocked-ops</code> and "
     "<code>governor/always-escalate-ops</code> at render time.</p>\n"
     (table ["Op" "Allowlist status" "Escalation" "Permitted effect"] (action-gate-rows))
     "<p>Confidence floor: <code class=\"num\">" (esc governor/confidence-floor)
     "</code> — a proposal below it escalates.</p>\n"

     "<h2>Phase matrix</h2>\n"
     "<p>Each cell is a live call to <code>phase/gate</code> with a governor-clean "
     "<code>:commit</code> as the pre-gate disposition. Default phase: "
     "<code>" (esc phase/default-phase) "</code>.</p>\n"
     (table (into ["Op"] (map ->s phases)) (phase-matrix-rows phases))

     "<h2>Supply-order cost thresholds</h2>\n"
     "<p>From <code>facts/supply-categories</code>. Unknown categories fall back to "
     "<code class=\"amt\">" (esc facts/default-cost-threshold) "</code>. The comparison is "
     "exclusive, so a cost exactly at the threshold does not escalate.</p>\n"
     (table ["Category" "名称" "Escalation threshold"] (cost-threshold-rows))

     "<h2>Fruit classes on file</h2>\n"
     "<p>From <code>facts/fruit-classes</code>.</p>\n"
     (table ["Id" "名称" "Group"] (fruit-class-rows))

     "<h2>Store read-back after the runs</h2>\n"
     "<p>Each row re-read through <code>store/registered-orchard</code> after every run "
     "finished. This is the ground truth the runs left behind.</p>\n"
     (table ["Orchard id" "Name" "Fruit class" "名称" "Row as stored"]
            (readback-rows readback))

     "<footer>\n"
     "<p>Rendered by <code>clojure -M:render-html</code> from "
     "<code>src/pomestoneops/render_html.clj</code>. Deterministic: no timestamps, no "
     "randomness, sets sorted before rendering. Styling: "
     "<code>jp-go-dds.skin/dds+skin</code> (デジタル庁デザインシステム).</p>\n"
     "<p>langgraph StateGraph wiring is deferred in this repo (see "
     "<code>pomestoneops.operation</code>); the actor is invoked exactly as "
     "<code>pomestoneops.sim/demo</code> invokes it, so escalated runs end at "
     "<code>:approval-requested</code> rather than round-tripping an approval.</p>\n"
     "</footer>\n"
     "</body>\n</html>\n")))

(defn -main
  "clojure -M:render-html [out-path]  (default docs/samples/operator-console.html)"
  [& [out-path]]
  (let [out (or out-path "docs/samples/operator-console.html")
        f (io/file out)
        html (render)]
    (when-let [parent (.getParentFile (.getAbsoluteFile f))]
      (.mkdirs parent))
    (spit f html)
    (println "wrote" (str f) (str "(" (count (.getBytes html "UTF-8")) " bytes)"))))
