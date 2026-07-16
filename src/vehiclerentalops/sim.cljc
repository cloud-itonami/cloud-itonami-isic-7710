(ns vehiclerentalops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean rental-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a fleet-operation scheduling request, a fleet-
  restock coordination request (both auto-commit clean at phase 3),
  then a vehicle-safety-concern flag (ALWAYS escalates, at any phase --
  approve, then commit), then HARD-hold scenarios: an unregistered
  account, an account registered but not yet verified, a proposal
  whose own `:effect` is not `:propose`, and a proposal that has
  drifted into the permanently-excluded driver-eligibility-override-
  finalization/vehicle-safety-clearance-finalization scope."
  (:require [langgraph.graph :as g]
            [vehiclerentalops.advisor :as advisor]
            [vehiclerentalops.store :as store]
            [vehiclerentalops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "rental-desk-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :rental-desk-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :rental-desk-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-rental-record account-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-rental-record :account-id "account-1"
                                  :patch {:vehicle-id "CAR-00042" :checkout-date "2026-07-16" :mileage 12480}} coordinator-phase-1)]
      (println r)
      (println "-- human rental-desk coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-rental-record account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-rental-record :account-id "account-1"
                                  :patch {:vehicle-id "CAR-00099" :return-date "2026-07-18" :mileage 12730 :damage-note "none"}} coordinator-phase-3))

    (println "\n== schedule-fleet-operation account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-fleet-operation :account-id "account-1"
                                  :patch {:title "Q3 fleet maintenance window" :vehicle-count 8}} coordinator-phase-3))

    (println "\n== coordinate-fleet-restock account-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-fleet-restock :account-id "account-1"
                                  :patch {:supplier "Kanda Fleet Distributors" :model "compact-sedan-2026" :due "2026-08-01"}} coordinator-phase-3))

    (println "\n== flag-vehicle-safety-concern account-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-vehicle-safety-concern :account-id "account-1"
                                 :patch {:concern "brake-pad wear on CAR-00042 reported by returning renter" :confidence 0.9}} coordinator-phase-3)]
      (println r)
      (println "-- human rental-desk coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-rental-record account-99 (unregistered account -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-rental-record :account-id "account-99"
                                  :patch {:vehicle-id "CAR-00007"}} coordinator-phase-3))

    (println "\n== log-rental-record account-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-rental-record :account-id "account-3"
                                  :patch {:vehicle-id "CAR-00012"}} coordinator-phase-3))

    (println "\n== schedule-fleet-operation account-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-fleet-operation :account-id "account-1"
                                           :patch {:title "Q3 fleet maintenance window take 2"}} coordinator-phase-3)))

    (println "\n== log-rental-record account-1, advisor drifts into driver-eligibility-override/vehicle-safety-clearance-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-rental-record :account-id "account-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed rental log ==")
    (doseq [r (store/rental-log db)] (println r))))
