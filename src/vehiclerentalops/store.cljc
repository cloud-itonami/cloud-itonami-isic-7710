(ns vehiclerentalops.store
  "SSoT for the ISIC-7710 RENTING AND LEASING OF MOTOR VEHICLES
  operations-coordination actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of a motor
  vehicle rental desk (checkout/return/mileage/damage-note logging,
  vehicle-availability/maintenance fleet-operation scheduling
  proposals, vehicle-safety-concern flagging [mechanical-defect
  doubts, recall status, damage disputes], and fleet procurement/
  replacement coordination). It NEVER directly finalizes a driver-
  eligibility override or a vehicle-safety-clearance decision -- see
  `vehiclerentalops.governor`'s `scope-exclusion-violations`, a HARD,
  permanent, un-overridable block, per this fleet's Wave 4 person-
  facing-service safety guardrail (ADR-2607152500).

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). An `accounts` directory keyed by `:account-id`
  STRING (never a keyword -- consistent keying from the start,
  avoiding the silent-miss bug that plagued an earlier shepherd
  attempt). Each account represents a rental-desk/branch operating
  account (or an equivalent fleet account) under which checkout,
  fleet-operation, safety-concern, and restock proposals are raised.

  A registered/verified rental-account (or the vehicle/fleet record
  it operates under) must exist before ANY proposal for it may ever
  commit or escalate -- `vehiclerentalops.governor`'s
  `account-unverified-violations` re-derives this from the account's
  own `:registered?`/`:verified?` fields, never from proposal
  self-report, the SAME 'ground truth, not self-report' discipline
  every sibling actor's own governor uses.

  The ledger stays append-only: which account a proposal targeted,
  which operation, on what basis, committed/held/escalated and
  approved by whom is always a query over an immutable log.")

(defprotocol Store
  (account [s account-id] "Registered rental account/vehicle record, or nil.
    Account map: {:account-id .. :name .. :registered? bool :verified? bool}.")
  (all-accounts [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (rental-log [s] "the append-only committed rental-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-accounts [s accounts] "replace/seed the account directory (map account-id->account)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained account directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:accounts
   {"account-1" {:account-id "account-1" :name "Kanda Motors Rental -- Branch 1 (fleet desk account)"
                 :registered? true :verified? true}
    "account-2" {:account-id "account-2" :name "Shibuya Branch -- Branch 2 (fleet desk account)"
                 :registered? true :verified? true}
    "account-3" {:account-id "account-3" :name "Ikebukuro Branch -- pending ID/insurance verification"
                 :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (account [_ account-id] (get-in @a [:accounts account-id]))
  (all-accounts [_] (sort-by :account-id (vals (:accounts @a))))
  (ledger [_] (:ledger @a))
  (rental-log [_] (:rental-log @a))
  (commit-record! [_ record]
    (swap! a update :rental-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-accounts [s accounts] (when (seq accounts) (swap! a assoc :accounts accounts)) s))

(defn seed-db
  "A MemStore seeded with the demo account directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :rental-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `accounts` map (account-id string
  -> account map) -- the primary test/dev entry point. `accounts` may
  be empty (an unregistered-everywhere store)."
  [accounts]
  (->MemStore (atom {:accounts (or accounts {}) :ledger [] :rental-log []})))
