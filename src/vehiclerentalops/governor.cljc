(ns vehiclerentalops.governor
  "VehicleRentalGovernor -- the independent compliance layer that
  earns the VehicleRentalAdvisor the right to commit. The advisor has
  no notion of whether a rental-account/vehicle record is actually
  registered and verified, whether its own proposed `:effect`
  secretly claims a direct actuation instead of a mere proposal, or
  whether it has silently drifted into a permanently out-of-scope
  decision area, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- MOTOR VEHICLE RENTAL
  OPERATIONS COORDINATION ONLY (checkout/return/mileage/damage-note
  logging, vehicle-availability/maintenance fleet-operation
  scheduling proposals, vehicle-safety-concern flagging, fleet
  procurement/replacement coordination). It NEVER performs or
  authorizes:
    - finalizing a driver-eligibility override (waiving or overriding
      a driver-eligibility check to approve a renter)
    - finalizing a vehicle-safety-clearance decision (renting out a
      vehicle with a known mechanical defect)

  Both of those are ALWAYS either a hard permanent block (this
  governor) or an always-escalate op (`:flag-vehicle-safety-concern`)
  -- NEVER an auto-commit-eligible op in any phase, per this fleet's
  Wave 4 person-facing-service safety guardrail (ADR-2607152500).
  This actor coordinates the back office around those decisions; it
  never makes them.

  Two HARD checks, ALL permanent, un-overridable by any human approval:

    1. Account unverified         -- the target rental-account/
                                      vehicle record must exist AND
                                      be independently confirmed
                                      `:registered?`/`:verified?` in
                                      the store before ANY proposal for
                                      it may commit or even escalate.
                                      Never trusts a proposal's own
                                      claim about the account --
                                      re-derived from the account's own
                                      store record, the same 'ground
                                      truth, not self-report'
                                      discipline every sibling actor's
                                      governor uses.
    2. Effect not :propose        -- every proposal's `:effect` MUST
                                      be `:propose`. Any other effect
                                      value is, by construction, a
                                      claim to directly actuate/commit
                                      outside governance -- HARD block,
                                      not merely low-confidence.
    3. Scope exclusion            -- ANY proposal (regardless of op)
                                      whose op, rationale, summary,
                                      citations or draft value touches
                                      the ACT of finalizing a driver-
                                      eligibility override, or the ACT
                                      of finalizing a vehicle-safety-
                                      clearance decision, is a HARD,
                                      PERMANENT block -- this actor's
                                      charter excludes that territory
                                      structurally, not as a rollout
                                      milestone. Evaluated
                                      UNCONDITIONALLY on every
                                      proposal. An op outside the
                                      closed four-op allowlist is the
                                      SAME failure mode (an advisor
                                      proposing something it was never
                                      authorized to propose) and is
                                      folded into this same check.

  IMPORTANT (self-trip discipline): `scope-excluded-terms` below are
  phrased as the FINALIZATION/EXECUTION ACTION ('finalize the driver-
  eligibility override', 'finalize the vehicle-safety clearance'),
  never as a bare noun ('eligibility', 'driver', 'safety', 'clearance').
  This actor's own legitimate happy-path proposals -- especially
  `:flag-vehicle-safety-concern`, whose entire purpose is to talk
  ABOUT mechanical-defect/recall/damage concerns -- routinely use
  those bare nouns in their default rationale text. A bare-noun term
  list would self-trip the actor on its own default mock-advisor
  proposals; `governor-test` and `governor-contract-test` both assert
  this never happens.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-vehicle-safety-concern` -- ALWAYS escalates to a human,
  regardless of confidence, regardless of how clean the proposal
  otherwise is. `vehiclerentalops.phase` independently agrees:
  `:flag-vehicle-safety-concern` is never a member of any phase's
  `:auto` set either -- two layers, not one."
  (:require [clojure.string :as str]
            [vehiclerentalops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-rental-record :schedule-fleet-operation
    :flag-vehicle-safety-concern :coordinate-fleet-restock})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-vehicle-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as attempting to
  directly FINALIZE a driver-eligibility override or a vehicle-
  safety-clearance decision -- this actor's two permanently out-of-
  scope decision areas. Phrased as the finalization/execution ACTION,
  never as a bare noun, so this list never matches inside this
  actor's own legitimate proposals (which routinely discuss driver-
  eligibility/vehicle-safety as topics without ever finalizing them).
  Scanned across the proposal's op/summary/rationale/cites/value,
  never trusting the advisor's own framing of its intent."
  ["finalize the driver-eligibility override" "finalize driver-eligibility override"
   "finalize the driver eligibility override" "override the driver-eligibility decision"
   "grant the driver-eligibility exception" "grant eligibility despite the flag"
   "authorize the driver-eligibility override" "confirm the driver-eligibility override decision"
   "waive the driver-eligibility requirement" "approve the renter despite the flag"
   "運転資格の可否を確定" "運転資格の除外を確定して承認" "運転者資格の最終判断を下す"
   "finalize the vehicle-safety clearance" "finalize vehicle-safety clearance"
   "finalize the vehicle safety clearance" "clear the vehicle for rental despite the defect"
   "rent out the vehicle despite the known defect" "authorize the vehicle-safety clearance"
   "confirm the vehicle-safety clearance decision" "release the vehicle despite the recall"
   "waive the vehicle-safety clearance requirement" "determine the vehicle is safe to rent despite the defect"
   "車両の貸出安全確認を確定" "リコール対象のまま貸出を確定" "整備不良のまま貸出可否を確定"
   "車両安全性の最終判断を下す"])

;; ----------------------------- checks -----------------------------

(defn- account-unverified-violations
  "The target rental-account/vehicle record must exist AND be
  independently `:registered?`/`:verified?` in the store -- never
  trust the proposal's own `:account-id` claim without a store lookup."
  [{:keys [account-id]} st]
  (let [r (store/account st account-id)]
    (when-not (and r (:registered? r) (:verified? r))
      [{:rule :account-unverified
        :detail (str account-id " は未登録または未検証のレンタルアカウント/車両記録 -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches finalizing a driver-eligibility
  override or finalizing a vehicle-safety-clearance decision,
  regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "運転資格の可否確定判断/車両の貸出安全確認の最終判断に踏み込む提案は永久に禁止"}])))

(defn check
  "Censors a VehicleRentalAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [account-id (or (:account-id proposal) (:account-id request))
        hard (into []
                   (concat (account-unverified-violations {:account-id account-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :account-id (:account-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
