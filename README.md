# cloud-itonami-7710

Open Business Blueprint for **ISIC Rev.5 7710**: renting and leasing
of motor vehicles (self-drive rental of cars, vans and light trucks
to consumers and businesses).

This repository designs a forkable OSS business for community
vehicle rental operations: fleet-safety and recall-compliance-scope
management, robotics-assisted vehicle-condition inspection at pickup/
return, and rental/reconciliation records — run by a qualified
operator so a rental company keeps its own safety and recall-
compliance history instead of renting a closed fleet-management
platform.

## Scope note: self-drive rental, not carriage or tool rental

Distinct from the fleet's own road-freight CARRIER
(`cloud-itonami-isic-4920`, already `:implemented`), which moves
goods aboard its own vehicles under its own drivers -- a vehicle
rental company hands the vehicle to the RENTER, who drives it
themselves, and never itself performs carriage. Also distinct from
`cloud-itonami-unspsc-27` ("Independent Tool Fleet Rental &
Maintenance Robotics"), which rents construction TOOLS and equipment,
not motor vehicles. Vehicle rental carries its own distinct
compliance regime: recalled vehicles must be repaired before re-rental
in the US under the Raechel and Jacqueline Houck Safe Rental Car Act
of 2015 (enforced by NHTSA); the US Graves Amendment shields rental
companies from vicarious-liability claims arising solely from vehicle
ownership; and state-specific rental-agreement consumer-protection
and damage-waiver-disclosure statutes apply.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a
**robot performs the physical domain work**. Here robots (vehicle-
condition inspection at pickup/return, damage documentation, fuel/
mileage verification) operate under an actor that proposes actions
and an independent **Vehicle Rental Governor** that gates them. The
governor never releases a vehicle for rental itself;
`:high`/`:safety-critical` actions (a rental release outside verified
recall-compliance scope, a damage-waiver record without a completed
inspection, a reconciliation record without verified evidence)
require human sign-off.

## Core Contract

```text
intake + identity + fleet-safety/recall-compliance scope + rental request
        |
        v
Vehicle Rental Advisor -> Vehicle Rental Governor -> match, rental record, or human approval
        |
        v
robot actions (gated) + inspection record + reconciliation record + audit ledger
```

No automated advice can release a vehicle the governor refuses,
match a renter to a recalled/out-of-scope vehicle, or publish a
reconciliation record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `7710`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/labor`](https://github.com/kotoba-lang/labor) — staff registration, dispatch, timesheet/follow-up contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
