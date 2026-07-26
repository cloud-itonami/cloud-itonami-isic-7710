(ns vehiclerentalops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`vehiclerentalops.operation` -> `vehiclerentalops.governor` ->
  `vehiclerentalops.store`) through a scenario built from this actor's
  OWN seeded demo data (`vehiclerentalops.store/demo-data`, accounts
  account-1/account-2/account-3) and renders the result deterministically
  -- no invented numbers, no timestamps in the page content,
  byte-identical across reruns against the same seed (verified by
  diffing two consecutive runs).

  NOTE for future porters of this template: this repo's own
  `vehiclerentalops.sim` demo driver (`clojure -M:dev:run`) was checked
  BEFORE writing this file and, unlike `cloud-itonami-isic-851`'s
  `schoolops.sim`, drives requests against ids (account-1/account-2/
  account-3/account-99) that correctly match/miss
  `vehiclerentalops.store/demo-data` on purpose -- it is not the latent
  copy-paste bug found there. This renderer keeps its own `run-demo!`
  scenario below (adapted from `vehiclerentalops.sim`'s proven scenario,
  trimmed to the representative subset the console needs) so this
  build-time generator has no runtime dependency on the demo driver
  either way -- every field read by `render` below is real
  governor/store output, not a hand-typed copy.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [vehiclerentalops.store :as store]
            [vehiclerentalops.advisor :as advisor]
            [vehiclerentalops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :rental-desk-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: account-1 clears a checkout log, a return log,
  and a fleet-restock coordination (all auto-commit clean at phase 3);
  account-2 clears a fleet-operation scheduling request (auto-commit
  clean at phase 3); account-1's vehicle-safety-concern flag ALWAYS
  escalates (per `always-escalate-ops`, regardless of confidence or
  phase) and is approved by a human rental-desk coordinator;
  account-3 (registered but NOT `:verified?` in the seed data)
  HARD-holds a checkout log on `:account-unverified` -- never reaches a
  human; account-2's fleet-operation scheduling attempt, drafted by an
  advisor that claims a direct `:effect :commit` instead of
  `:propose`, HARD-holds on `:effect-not-propose`; account-1's checkout
  log attempt, whose advisor has drifted into the permanently-excluded
  driver-eligibility-override/vehicle-safety-clearance-finalization
  territory (`:out-of-scope? true`), HARD-holds on `:scope-excluded`.
  Returns the resulting store -- every field read by `render` below is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        direct-actuation-actor (op/build db
                                 {:advisor (reify advisor/Advisor
                                             (-advise [_ _ req]
                                               (assoc (advisor/infer nil req) :effect :commit)))})]
    (exec! actor "a1-checkout" {:op :log-rental-record :account-id "account-1"
                                 :patch {:vehicle-id "CAR-00042" :checkout-date "2026-07-16" :mileage 12480}})

    (exec! actor "a1-return" {:op :log-rental-record :account-id "account-1"
                               :patch {:vehicle-id "CAR-00099" :return-date "2026-07-18" :mileage 12730 :damage-note "none"}})

    (exec! actor "a2-fleet-op" {:op :schedule-fleet-operation :account-id "account-2"
                                 :patch {:title "Q3 fleet maintenance window" :vehicle-count 8}})

    (exec! actor "a1-restock" {:op :coordinate-fleet-restock :account-id "account-1"
                                :patch {:supplier "Kanda Fleet Distributors" :model "compact-sedan-2026" :due "2026-08-01"}})

    (exec! actor "a1-safety" {:op :flag-vehicle-safety-concern :account-id "account-1"
                               :patch {:concern "brake-pad wear on CAR-00042 reported by returning renter" :confidence 0.9}})
    (approve! actor "a1-safety")

    (exec! actor "a3-checkout" {:op :log-rental-record :account-id "account-3"
                                 :patch {:vehicle-id "CAR-00012"}})

    (exec! direct-actuation-actor "a2-direct" {:op :schedule-fleet-operation :account-id "account-2"
                                                :patch {:title "Q3 fleet maintenance window take 2"}})

    (exec! actor "a1-scope" {:op :log-rental-record :account-id "account-1"
                              :out-of-scope? true :patch {}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger account-id]
  (last (filter #(= (:account-id %) account-id) ledger)))

(defn- status-cell [ledger account-id]
  (let [f (last-fact-for ledger account-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- account-row [ledger {:keys [account-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc account-id) (esc name)
          (if (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
              "<span class=\"warn\">registered, unverified</span>")
          (status-cell ledger account-id)))

(defn- ledger-row [{:keys [t op account-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc account-id)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; core contract, `vehiclerentalops.governor`/`vehiclerentalops.phase`)
  ;; -- documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-rental-record</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-fleet-operation</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:coordinate-fleet-restock</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:flag-vehicle-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        accounts (store/all-accounts db)
        account-rows (str/join "\n" (map (partial account-row ledger) accounts))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-7710 &middot; vehicle-rental operations</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Vehicle rental operations (ISIC 7710) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · driver-eligibility/vehicle-safety-clearance decisions permanently out of scope</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Rental accounts</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>vehiclerentalops.store</code> via <code>vehiclerentalops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Account</th><th>Name</th><th>Roster status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     account-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Vehicle Rental Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Finalizing a driver-eligibility override or a vehicle-safety-clearance decision is permanently out of scope -- see governor scope-exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Account</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/rental-log db)) "committed rental records )")))
