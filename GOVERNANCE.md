# Governance

`cloud-itonami-7710` is an OSS open-business blueprint for community
vehicle rental operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Vehicle Rental Governor remains independent of the advisor.
- hard policy violations (a rental release outside verified recall-
  compliance scope, an unverified damage-waiver record, an
  unverified reconciliation record) cannot be overridden by human
  approval.
- every dispatch, sign-off and reconciliation path is auditable.
- sensitive renter and payment data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or recall-compliance checks
- mishandling renter or payment data
- misrepresenting certification status
- failing to respond to safety incidents
