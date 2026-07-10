# Business Model: Community Vehicle Rental Operations

## Classification
- Repository: `cloud-itonami-7710`
- ISIC Rev.5: `7710` — renting and leasing of motor vehicles
- Social impact: road safety, consumer protection, shared-asset
  access

## Customer
- independent/community vehicle rental companies needing an
  auditable recall-compliance and fleet-safety platform
- renters needing verifiable inspection and reconciliation records
- regulators needing verifiable recall-compliance and consumer-
  protection records
- programs that cannot accept closed, unauditable fleet-management
  platforms

## Offer
- fleet-safety and recall-compliance-scope management
- robotics-assisted vehicle-condition inspection at pickup/return
- rental, inspection and reconciliation records
- renter billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per rental location/fleet
- support retainer with SLA
- vehicle-inspection robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (a rental release outside verified recall-
  compliance scope, a damage-waiver record without a completed
  inspection, an unverified reconciliation record) require human
  sign-off
- vehicles cannot be released outside verified recall-compliance
  scope
- reconciliation records require verified evidence
- sensitive renter and payment data stays outside Git
