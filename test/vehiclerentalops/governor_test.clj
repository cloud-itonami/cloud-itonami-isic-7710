(ns vehiclerentalops.governor-test
  "Pure unit tests of `vehiclerentalops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [vehiclerentalops.advisor :as advisor]
            [vehiclerentalops.governor :as gov]
            [vehiclerentalops.store :as store]))

(def account-1 {:account-id "account-1" :name "Kanda Motors Rental -- Branch 1" :registered? true :verified? true})
(def account-3 {:account-id "account-3" :name "Ikebukuro Branch -- pending verification" :registered? true :verified? false})

(defn- clean-proposal [op account-id]
  {:op op :account-id account-id :summary "s" :rationale "routine vehicle rental operations coordination"
   :cites [account-id] :effect :propose :value {} :confidence 0.85})

(deftest account-unregistered-is-hard
  (testing "no account record at all -> HARD hold"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "unknown-account") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:account-unverified} (map :rule (:violations verdict)))))))

(deftest account-unverified-is-hard
  (testing "account registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"account-3" account-3})
          verdict (gov/check {} nil (clean-proposal :log-rental-record "account-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:account-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-fleet-operation "account-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"account-1" account-1})
          verdict (gov/check {} nil (clean-proposal :override-driver-eligibility "account-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest driver-eligibility-override-content-is-hard-and-permanent
  (testing "a proposal whose rationale finalizes a driver-eligibility override is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :log-rental-record "account-1")
                          :rationale "decided to finalize the driver-eligibility override and approve the renter despite the flag"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest vehicle-safety-clearance-content-is-hard
  (testing "a proposal touching finalizing a vehicle-safety-clearance decision is HARD-blocked, same as driver-eligibility"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :flag-vehicle-safety-concern "account-1")
                          :rationale "decided to finalize the vehicle-safety clearance and rent out the vehicle despite the known defect"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest vehicle-safety-clearance-finalization-in-summary-is-hard
  (testing "a proposal touching finalizing the vehicle-safety clearance in the summary is HARD-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :coordinate-fleet-restock "account-1")
                          :summary "finalize the vehicle-safety clearance ahead of the supplier handoff")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest driver-eligibility-override-content-in-value-is-hard
  (testing "a proposal whose draft value grants the driver-eligibility exception is HARD-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          poisoned (assoc (clean-proposal :log-rental-record "account-1")
                          :value {:decision "grant the driver-eligibility exception for this customer"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-vehicle-safety-concern-is-not-scope-excluded
  (testing "flagging a possible mechanical-defect/recall/damage concern (not a finalization) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"account-1" account-1})
          concern (assoc (clean-proposal :flag-vehicle-safety-concern "account-1")
                         :value {:concern "possible brake-pad wear and a disputed damage claim on a returned vehicle"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (mechanical-defect/recall/damage doubts) is exactly what this op exists to surface"))))

;; ----------------------------- self-trip regression (mandatory) -----------------------------
;;
;; A known bug class in this exact codebase family: a governor's own
;; scope-exclusion term list phrased as a bare noun can accidentally
;; match inside the mock advisor's own DEFAULT rationale/disclaimer
;; text for a legitimate, allowed proposal -- causing the actor to
;; self-block on its own happy path. This actor's `scope-excluded-terms`
;; are deliberately phrased as the finalization/execution ACTION
;; ('finalize the driver-eligibility override', not bare 'eligibility' or
;; 'driver'; 'finalize the vehicle-safety clearance', not bare
;; 'safety' or 'clearance'). This test asserts the default mock
;; advisor's own proposals for all four allowed ops, for a clean
;; registered+verified account, NEVER trip scope-exclusion -- i.e.
;; the actor never self-blocks on its own happy path.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "none of the four default proposal generators' own rationale/summary/value text self-trips scope-exclusion"
    (let [s (store/mem-store {"account-1" account-1})]
      (doseq [op [:log-rental-record :schedule-fleet-operation
                  :flag-vehicle-safety-concern :coordinate-fleet-restock]]
        (let [proposal (advisor/infer nil {:op op :account-id "account-1"
                                            :patch {:vehicle-id "CAR-00042" :mileage 12480
                                                    :title "Q3 fleet maintenance window" :vehicle-count 8
                                                    :supplier "Kanda Fleet Distributors" :model "compact-sedan-2026"
                                                    :concern "possible brake-pad wear and damage dispute"}})
              verdict (gov/check {:account-id "account-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default proposal for op " op " must never self-trip scope-exclusion; got violations: "
                   (:violations verdict)))
          (is (not (:hard? verdict))
              (str "default proposal for op " op " (clean, registered+verified account) must never HARD hold")))))))
