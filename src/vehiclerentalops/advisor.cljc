(ns vehiclerentalops.advisor
  "VehicleRentalAdvisor -- the *contained intelligence node* for the
  ISIC-7710 RENTING AND LEASING OF MOTOR VEHICLES operations-
  coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: rental-record (checkout/return/mileage/damage-note)
  logging, vehicle-availability/maintenance fleet-operation
  scheduling, vehicle-safety-concern flagging, and fleet procurement/
  replacement coordination. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a direct actuation --
  every proposal's `:effect` is always `:propose`. Every output is
  censored downstream by `vehiclerentalops.governor` before anything
  touches the SSoT.

  This advisor NEVER finalizes a driver-eligibility override (waiving
  or overriding a driver-eligibility check to approve a renter) and
  NEVER finalizes a vehicle-safety-clearance decision (renting out a
  vehicle with a known mechanical defect) -- those are permanently out
  of scope for this actor, not merely un-implemented, per this
  fleet's Wave 4 person-facing-service safety guardrail
  (ADR-2607152500). `vehiclerentalops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :account-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-rental-record
  "Draft a checkout/return/mileage/damage-note rental-record log
  entry. Pure transaction/condition metadata logging -- never a
  driver-eligibility decision or a vehicle-safety-clearance
  determination."
  [_db {:keys [account-id patch]}]
  {:op         :log-rental-record
   :account-id account-id
   :summary    (str account-id " のレンタル記録(チェックアウト/返却/走行距離/損傷メモ)を記録: " (pr-str (keys patch)))
   :rationale  "チェックアウト・返却・走行距離・損傷メモのメタデータ記録のみ。運転資格の可否や車両の貸出安全確認とは無関係。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.93})

(defn- propose-fleet-operation
  "Draft a vehicle-availability/maintenance fleet-operation scheduling
  proposal (an internal ops calendar entry, never a binding
  maintenance commitment or a vehicle-safety-clearance decision)."
  [_db {:keys [account-id patch]}]
  {:op         :schedule-fleet-operation
   :account-id account-id
   :summary    (str account-id " の車両稼働/整備スケジュール調整を提案: " (pr-str (keys patch)))
   :rationale  "車両の稼働可否・整備の社内スケジュール調整提案のみ。整備完了後の貸出可否を確定させるものではない。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.88})

(defn- propose-vehicle-safety-concern
  "Surface a mechanical-defect, recall, or damage concern for HUMAN
  triage. This op ALWAYS escalates in `vehiclerentalops.governor` --
  never auto-committed at any phase -- regardless of how confident
  the advisor is that the concern is real."
  [_db {:keys [account-id patch]}]
  {:op         :flag-vehicle-safety-concern
   :account-id account-id
   :summary    (str account-id " の車両安全性懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "整備不良の疑い・リコール対象・損傷に関する観察事実の報告。走行可否や貸出可否の最終判断は常に人間が行う。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence (or (:confidence patch) 0.85)})

(defn- propose-fleet-restock
  "Draft a fleet procurement/replacement coordination proposal
  (logistics/ordering coordination only, never a binding purchase
  commitment or payment approval)."
  [_db {:keys [account-id patch]}]
  {:op         :coordinate-fleet-restock
   :account-id account-id
   :summary    (str account-id " の車両調達/入替コーディネートを提案: " (pr-str (keys patch)))
   :rationale  "フリート車両の調達・入替コーディネート提案のみ。契約締結や支払承認の権限は持たない。"
   :cites      [account-id]
   :effect     :propose
   :value      (merge {:account-id account-id} patch)
   :confidence 0.90})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-rental-record (propose-rental-record _db request)
                   :schedule-fleet-operation (propose-fleet-operation _db request)
                   :flag-vehicle-safety-concern (propose-vehicle-safety-concern _db request)
                   :coordinate-fleet-restock (propose-fleet-restock _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually decided to finalize the driver-eligibility override and approve the renter despite the flag")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t          :advisor-proposal
   :op         (:op proposal)
   :account-id (:account-id proposal)
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
