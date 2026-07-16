(ns vehiclerentalops.advisor-test
  "Unit tests of `vehiclerentalops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [vehiclerentalops.advisor :as adv]
            [vehiclerentalops.store :as store]))

(def db (store/seed-db))

(deftest propose-rental-record-shape
  (testing "rental-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-rental-record
                           :account-id "account-1"
                           :patch {:vehicle-id "CAR-00042" :mileage 12480}})]
      (is (= :log-rental-record (:op p)))
      (is (= "account-1" (:account-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :account-id)))))

(deftest propose-fleet-operation-shape
  (testing "fleet-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-fleet-operation
                           :account-id "account-2"
                           :patch {:title "Q3 fleet maintenance window" :vehicle-count 8}})]
      (is (= :schedule-fleet-operation (:op p)))
      (is (= "account-2" (:account-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-vehicle-safety-concern-shape
  (testing "vehicle-safety-concern proposal has correct shape"
    (let [p (adv/infer db {:op :flag-vehicle-safety-concern
                           :account-id "account-1"
                           :patch {:concern "possible brake-pad wear"}})]
      (is (= :flag-vehicle-safety-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-fleet-restock-shape
  (testing "fleet-restock proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-fleet-restock
                           :account-id "account-1"
                           :patch {:supplier "Kanda Fleet Distributors" :model "compact-sedan-2026"}})]
      (is (= :coordinate-fleet-restock (:op p)))
      (is (= :propose (:effect p)))
      (is (>= (:confidence p) 0.85)))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-rental-record :schedule-fleet-operation
                :flag-vehicle-safety-concern :coordinate-fleet-restock]]
      (let [p (adv/infer db {:op op :account-id "account-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-rental-record :schedule-fleet-operation
                :flag-vehicle-safety-concern :coordinate-fleet-restock]]
      (let [p (adv/infer db {:op op :account-id "account-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
