# Operator Guide

## First Deployment
1. Register operator, rental locations/fleet, recall-compliance
   scope, staff and inspection robots.
2. Import existing rental and billing history.
3. Run read-only recall-compliance-scope and vehicle-inspection robot
   mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- recall-compliance-scope validation before any rental release
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (a rental
  release outside verified recall-compliance scope, an unverified
  damage-waiver record, an unverified reconciliation record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual vehicle-rental process

## Certification
Certified operators must prove robot-safety integrity, recall-
compliance discipline, evidence-backed reconciliation records and
human review for dispatch-affecting actions.
