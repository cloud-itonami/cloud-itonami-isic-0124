(ns pomestoneops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  demo page and no generator at all. This namespace drives the REAL
  actor stack (`pomestoneops.advisor` -> `pomestoneops.governor` ->
  `pomestoneops.phase`, composed by `pomestoneops.operation`) and
  renders whatever comes back. No governor/phase logic is reimplemented
  here -- the renderer only formats `run-operation`'s return value.

  WHY NOT langgraph: unlike `cloud-itonami-isic-9522`, this repo does
  NOT wire langgraph-clj. `deps.edn` carries an EMPTY `:deps {}` (the
  `:dev` alias only `:override-deps`, which is a no-op when nothing
  depends on langgraph), and `pomestoneops.operation/build` is
  documented in its own docstring as a stub that returns a plain
  synchronous function over `run-operation` rather than a StateGraph.
  So the entry point used here is this repo's own real one:
  `(operation/build store)` -> `(actor request context)`, exactly the
  shape `pomestoneops.sim/demo` uses.

  INPUT PROVENANCE -- every orchard-id fed to the actor below literally
  exists in this repo's own store seed data (see `seeded-orchards`);
  nothing is invented. The request maps and the HARD-hold-triggering
  proposals are likewise taken verbatim from this repo's own sim driver
  and test suite, cited line by line at each definition.

  DETERMINISM -- no timestamps, no wall-clock, no random, no run
  counters in the page content. Map iteration is explicitly sorted.
  Two consecutive runs are byte-identical; verify by rendering to two
  paths and diffing.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [pomestoneops.facts :as facts]
            [pomestoneops.governor :as governor]
            [pomestoneops.operation :as operation]
            [pomestoneops.store :as store]))

;; ------------------------- seed / provenance -------------------------

(def ^:private seeded-orchards
  "The ONLY orchard-ids this console feeds the actor. Both are copied
  VERBATIM from this repo's own seed data -- grep them yourself:

    \"orchard-001\" -- src/pomestoneops/sim.cljc `:initial-orchards`
                     (`{:id .. :name \"Test Orchard Block\"
                        :fruit-class \"apple\"}`), and the fixture every
                     `test/pomestoneops/governor_test.cljc` case seeds.
    \"orchard-002\" -- test/pomestoneops/store_test.cljc `add-orchard-test`
                     (`{:id \"orchard-002\" :name \"New Orchard Block\"}`).

  `orchard-002` genuinely has no `:fruit-class` on file; that is the
  seed as written, not an omission here. `pomestoneops.store`'s own
  docstring says orchard records are opaque to the Store, so a partial
  record is a real state of this domain and is rendered as such."
  {"orchard-001" {:id "orchard-001"
                  :name "Test Orchard Block"
                  :fruit-class "apple"}
   "orchard-002" {:id "orchard-002"
                  :name "New Orchard Block"}})

(def ^:private orchard-provenance
  "id -> the file this seed record was copied from (build provenance,
  deliberately kept OUT of the orchard table so that table contains
  only real `pomestoneops` domain fields)."
  {"orchard-001" "src/pomestoneops/sim.cljc (:initial-orchards)"
   "orchard-002" "test/pomestoneops/store_test.cljc (add-orchard-test)"})

(def ^:private operator-context
  "Actor identity taken verbatim from `pomestoneops.sim/demo`'s context.
  `:phase` is supplied per scenario."
  {:actor-id "pome-stone-ops-01"
   :role :orchard-operator})

;; ----------------------------- scenarios -----------------------------

(def ^:private scenarios
  "Every scenario below uses a seeded orchard-id and request keys that
  `pomestoneops.advisor`/`pomestoneops.governor` actually read. The
  proposals that trip HARD holds are the same ones
  `test/pomestoneops/governor_test.cljc` asserts on -- they reach the
  Governor through the real MockAdvisor (its fallback branch emits
  `{:op <requested-op> :effect :propose}` for any op outside the
  advisor's `case`, which is exactly how an out-of-allowlist or blocked
  op arrives at the Governor in production).

  NOT exercised here, on purpose:
    - `:orchard-not-registered` -- reaching it REQUIRES an orchard-id
      absent from the seed, which is precisely the thing this console
      refuses to invent. Described in the contract table instead.
    - `:no-execution` -- requires a proposal with `:effect` other than
      `:propose`; the real advisor never emits one, and faking an
      advisor here would mean the page no longer shows this repo's
      actual advisor. Described in the contract table instead."
  [{:label "clean harvest record (sim.cljc's own request, at full autonomy)"
    :phase :phase-3
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 500
              :notes "healthy yield"}}

   {:label "same record at phase-0 -- simulation rollout forbids autonomous commit"
    :phase :phase-0
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 500
              :notes "healthy yield"}}

   {:label "field-operation scheduling (no date supplied by the operator)"
    :phase :phase-3
    :request {:op :schedule-field-operation
              :orchard-id "orchard-001"
              :operation-type "thinning"}}

   {:label "codling-moth concern -- ALWAYS escalates, even at 0.8 confidence"
    :phase :phase-3
    :request {:op :flag-crop-health-concern
              :orchard-id "orchard-001"
              :concern "コドリンガ（codling moth）の疑い"}}

   {:label "fire-blight concern at phase-1 -- escalates via the phase gate too"
    :phase :phase-1
    :request {:op :flag-crop-health-concern
              :orchard-id "orchard-002"
              :concern "火傷病（fire blight）の疑い"}}

   {:label "equipment order under its category threshold (800 <= 1000)"
    :phase :phase-3
    :request {:op :order-supplies
              :orchard-id "orchard-002"
              :category "equipment"
              :cost 800}}

   {:label "equipment order over its category threshold (1200 > 1000)"
    :phase :phase-3
    :request {:op :order-supplies
              :orchard-id "orchard-002"
              :category "equipment"
              :cost 1200}}

   {:label "seedling order at 600 -- same money, lower category bar (600 > 500)"
    :phase :phase-3
    :request {:op :order-supplies
              :orchard-id "orchard-001"
              :category "seedling"
              :cost 600}}

   {:label "HARD: logged quantity of 0 is not an observation"
    :phase :phase-3
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 0
              :notes "sensor returned nothing"}}

   {:label "HARD: direct field-equipment operation is permanently out of authority"
    :phase :phase-3
    :request {:op :operate-field-equipment
              :orchard-id "orchard-001"}}

   {:label "HARD: finalizing a spray-application decision is permanently blocked"
    :phase :phase-3
    :request {:op :finalize-spray-application
              :orchard-id "orchard-002"}}

   {:label "HARD: op outside the closed allowlist"
    :phase :phase-3
    :request {:op :dispatch-robot-arm
              :orchard-id "orchard-001"}}

   {:label "fail-closed: an unrecognised rollout phase holds rather than guesses"
    :phase :unknown-phase
    :request {:op :log-orchard-record
              :orchard-id "orchard-001"
              :record-type "harvest"
              :count 500
              :notes "healthy yield"}}])

(defn run-demo!
  "Drives every scenario through the REAL actor and returns a vector of
  `{:label :phase :request :result}` in scenario order. `:result` is
  `pomestoneops.operation/run-operation`'s untouched return value
  (`:disposition`, `:audit`, `:record`, `:verdict`) -- every value the
  page renders comes from here."
  []
  (let [st (store/mem-store {:initial-orchards seeded-orchards})
        actor (operation/build st)]
    (mapv (fn [{:keys [label phase request]}]
            {:label label
             :phase phase
             :request request
             :result (actor request (assoc operator-context :phase phase))})
          scenarios)))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm [x]
  (if (keyword? x) (name x) (str x)))

(defn- dash [s]
  (if (str/blank? (str s)) "—" (str s)))

(defn- render-map
  "Deterministic one-line rendering of a small domain map (keys sorted
  by name, nil values shown as an em dash)."
  [m]
  (if (seq m)
    (->> (sort-by (comp str key) m)
         (map (fn [[k v]] (str (nm k) "=" (if (nil? v) "—" v))))
         (str/join " · "))
    "—"))

(defn- disposition-cell [disposition rule]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :escalate "<span class=\"warn\">escalate · human sign-off</span>"
    :hold (if rule
            (str "<span class=\"critical\">HARD hold · " (esc (nm rule)) "</span>")
            "<span class=\"critical\">hold · phase gate</span>")
    (str "<span class=\"muted\">" (esc (nm disposition)) "</span>")))

(defn- hold-rule
  "The Governor rule that produced a HARD hold, or nil when the hold came
  from the phase gate rather than a Governor violation."
  [result]
  (-> result :verdict :violations first :rule))

(defn- basis-cell [{:keys [disposition audit]}]
  (let [f (second audit)]
    (case disposition
      :hold (dash (or (some->> (:basis f) seq (map nm) (str/join ", "))
                      (some-> (:phase-reason f) nm)))
      :escalate (dash (some-> (:reason f) nm))
      :commit (dash (str/join ", " (:basis f)))
      "—")))

(defn- run-row [{:keys [label phase request result]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (nm (:op request)))
          (esc (:orchard-id request))
          (esc (nm phase))
          (disposition-cell (:disposition result) (hold-rule result))
          (esc (basis-cell result))
          (esc label)))

(defn- hold-row [{:keys [request result]}]
  (let [v (-> result :verdict :violations first)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (nm (:rule v)))
            (esc (nm (:op request)))
            (esc (:orchard-id request))
            (esc (:detail v)))))

(defn- ledger-row [f]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (nm (:t f)))
          (esc (nm (:op f)))
          (esc (dash (or (:subject f) (:orchard-id f))))
          (esc (dash (case (:t f)
                       :advisor-proposal (:proposal-summary f)
                       :governor-hold (or (some->> (:basis f) seq (map nm) (str/join ", "))
                                          (some-> (:phase-reason f) nm))
                       :approval-requested (some-> (:reason f) nm)
                       :committed (str/join ", " (:basis f))
                       nil)))
          (esc (dash (:confidence f)))))

(defn- orchard-row [{:keys [id name fruit-class]}]
  (let [fc (and fruit-class (facts/fruit-class-by-id fruit-class))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc id)
            (esc (dash name))
            (esc (dash fruit-class))
            (if fc
              (str (esc (:name fc)) " · " (esc (nm (:group fc))))
              "<span class=\"muted\">—</span>"))))

(defn- record-row [{:keys [request result]}]
  (let [r (:record result)]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (nm (:op request)))
            (esc (nm (:effect r)))
            (esc (str/join "/" (:path r)))
            (esc (render-map (:value r))))))

(defn- supply-row [{:keys [id name cost-threshold]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc name) (esc cost-threshold)))

(defn- fruit-row [{:keys [id name group]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc name) (esc (nm group))))

;; --------------------- fixed-contract descriptions ---------------------
;;
;; NOTE (property f): the two tables below are a DESCRIPTION OF FIXED
;; CONTRACT -- what `pomestoneops.governor` and `pomestoneops.phase`
;; always do -- and NOT runtime telemetry from the scenario run above.
;; They are hand-written prose deliberately, because they must also
;; state the rules this console does not exercise. Everything that CAN
;; be derived from the namespaces is derived (the op sets and the
;; confidence floor are read from the vars at build time, so the page
;; cannot silently drift from the code), and every claim here was read
;; back off `governor.cljc` / `phase.cljc` before being written.

(defn- code-list [xs]
  (->> xs (map #(str "<code>" (esc (nm %)) "</code>")) (str/join ", ")))

(defn- governor-contract-rows []
  [(format "        <tr><td><code>:orchard-not-registered</code></td><td><span class=\"critical\">HARD · permanent</span></td><td>Any proposal whose <code>:orchard-id</code> is missing or unknown to the Store. <span class=\"muted\">Not exercised on this page: it requires an orchard-id that is not in the seed.</span></td></tr>")
   (format "        <tr><td><code>:no-execution</code></td><td><span class=\"critical\">HARD · permanent</span></td><td>Proposal <code>:effect</code> is anything other than <code>:propose</code>. <span class=\"muted\">Not exercised on this page: the real advisor never emits one.</span></td></tr>")
   (format "        <tr><td><code>:field-equipment-or-spray-blocked</code></td><td><span class=\"critical\">HARD · permanent</span></td><td>%s — direct field-equipment operation and finalizing a spray application stay exclusively human, regardless of confidence.</td></tr>"
           (code-list (sort (map nm governor/blocked-ops))))
   (format "        <tr><td><code>:op-not-allowed</code></td><td><span class=\"critical\">HARD · permanent</span></td><td>Anything outside the closed allowlist %s.</td></tr>"
           (code-list (sort (map nm governor/known-ops))))
   (format "        <tr><td><code>:orchard-count-invalid</code></td><td><span class=\"critical\">HARD · permanent</span></td><td>A <code>:log-orchard-record</code> whose <code>:count</code> is zero or negative, recomputed by <code>pomestoneops.registry</code> rather than trusted from the proposal.</td></tr>")
   (format "        <tr><td>always-escalate op</td><td><span class=\"warn\">soft · human sign-off</span></td><td>%s — never resolved autonomously at any phase.</td></tr>"
           (code-list (sort (map nm governor/always-escalate-ops))))
   (format "        <tr><td>cost over threshold</td><td><span class=\"warn\">soft · human sign-off</span></td><td>An <code>:order-supplies</code> above its category threshold (default %s when the category is unknown).</td></tr>"
           (esc facts/default-cost-threshold))
   (format "        <tr><td>low confidence</td><td><span class=\"warn\">soft · human sign-off</span></td><td>Stated confidence below the floor of %s. <span class=\"muted\">Not exercised on this page: every proposal the real advisor emits for an allowlisted op is already above the floor.</span></td></tr>"
           (esc governor/confidence-floor))])

(def ^:private phase-contract-rows
  ;; Fixed contract, read off `pomestoneops.phase/gate` -- not telemetry.
  ["        <tr><td><code>:phase-0</code></td><td>simulation only — anything that would commit is forced to escalate (<code>:phase-0-simulation-only</code>)</td></tr>"
   "        <tr><td><code>:phase-1</code></td><td>supervised — always-escalate ops escalate even when the Governor is clean (<code>:phase-1-always-escalate</code>)</td></tr>"
   "        <tr><td><code>:phase-2</code></td><td>reduced supervision — the disposition passes through unchanged</td></tr>"
   "        <tr><td><code>:phase-3</code></td><td>full autonomy — the Governor's verdict is authoritative</td></tr>"
   "        <tr><td>anything else</td><td>fail-closed — an unrecognised phase holds (<code>:unknown-phase</code>) rather than guessing</td></tr>"])

;; ------------------------------- page -------------------------------

(defn render
  "Renders the whole console from the vector `run-demo!` returned."
  [runs]
  (let [holds (filter #(some? (hold-rule (:result %))) runs)
        commits (filter #(= :commit (-> % :result :disposition)) runs)
        ledger (mapcat #(-> % :result :audit) runs)]
    (str
     "<!DOCTYPE html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
     "<title>cloud-itonami-isic-0124 · pome- and stone-fruit orchard operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Growing of pome fruits and stone fruits (ISIC 0124) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · field-equipment operation and spray-application decisions permanently out of authority</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Registered orchard blocks</h2>\n"
     "    <p class=\"muted\">Generated at build time by <code>pomestoneops.render-html</code> (<code>clojure -M:dev:render-html</code>) from this repo's own seed data. "
     "Both ids exist verbatim in the repository: <code>orchard-001</code> in <code>" (esc (get orchard-provenance "orchard-001")) "</code>, "
     "<code>orchard-002</code> in <code>" (esc (get orchard-provenance "orchard-002")) "</code>. No orchard-id on this page was invented; "
     "<code>orchard-002</code> carries no <code>:fruit-class</code> because the seed record does not have one.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>orchard-id</th><th>:name</th><th>:fruit-class</th><th>class (pomestoneops.facts)</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map orchard-row (map seeded-orchards (sort (keys seeded-orchards))))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Operation runs (this build)</h2>\n"
     "    <p class=\"muted\">Each row is one real <code>pomestoneops.operation/run-operation</code> call: advise → govern → phase gate → commit | escalate | hold. "
     "The disposition and basis columns are the actor's own output, not a description of it.</p>\n"
     "    <p class=\"muted\">Read the basis column literally: <code>pomestoneops.operation</code> derives the escalation reason from the Governor's "
     "<code>:high-stakes?</code> flag, and that flag is <code>(or high-cost? always-escalate?)</code> — so a supply order that escalates purely on cost "
     "is also reported as <code>always-escalate</code>. That is the actor's real output and is left uncorrected here; the two cases are distinguishable "
     "by the op (<code>:order-supplies</code> is not an always-escalate op — see the Governor contract below).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Orchard</th><th>Phase</th><th>Disposition</th><th>Basis / reason</th><th>Scenario</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map run-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds reached (this build)</h2>\n"
     "    <p class=\"muted\">A HARD hold is permanent — it never reaches a human for override. The rule and the detail text below are read straight off "
     "<code>(:verdict result)</code>'s <code>:violations</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Op</th><th>Orchard</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Committed records (this build)</h2>\n"
     "    <p class=\"muted\">The <code>:record</code> each committing run handed back to the SSoT seam — <code>:effect</code> is always <code>:propose</code>; "
     "this actor never executes.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>:effect</th><th>:path</th><th>:value</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map record-row commits)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Governor contract (Pome- and Stone-Fruit Operations Governor)</h2>\n"
     "    <p class=\"muted\">Fixed contract, not telemetry: this is what <code>pomestoneops.governor</code> always does. "
     "The op sets and the confidence floor are read from the namespace's vars at build time. Two rules are listed but deliberately not exercised above — "
     "each says why.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule / gate</th><th>Class</th><th>Trigger</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (governor-contract-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Phase gate contract</h2>\n"
     "    <p class=\"muted\">Fixed contract, not telemetry: the rollout stages in <code>pomestoneops.phase/gate</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Effect on the disposition</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" phase-contract-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Reference data (pomestoneops.facts)</h2>\n"
     "    <p class=\"muted\">Supply categories and their escalation thresholds, and the pome/stone fruit classes this actor's records may cover. "
     "Read from the namespace, not retyped.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Supply category</th><th>Name</th><th>Escalation threshold</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map supply-row (sort-by :id (vals facts/supply-categories)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <table>\n"
     "      <thead><tr><th>Fruit class</th><th>Name</th><th>Group</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map fruit-row (sort-by (juxt (comp nm :group) :id) (vals facts/fruit-classes)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this build)</h2>\n"
     "    <p class=\"muted\">Every audit fact the runs above emitted, in order — the advisor's proposal trace followed by the disposition fact, "
     "for each of the " (count runs) " operations.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Orchard</th><th>Detail / basis</th><th>Confidence</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        runs (run-demo!)
        html (render runs)
        by-disp (frequencies (map #(-> % :result :disposition) runs))]
    (spit out html :encoding "UTF-8")
    (println "wrote" out
             (str "(" (count runs) " operations, "
                  (:commit by-disp 0) " commit / "
                  (:escalate by-disp 0) " escalate / "
                  (:hold by-disp 0) " hold, "
                  (count (filter #(some? (hold-rule (:result %))) runs)) " HARD governor holds)"))))
