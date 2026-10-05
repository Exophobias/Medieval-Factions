# Native siege policy and actuator authority

The Patriam implementation 5.9.0-patriam.2-SNAPSHOT separately registers `NativeSiegeAuthority`
with Bukkit's ServicesManager. Its API artifact retains the established
`5.9.0-patriam.1-SNAPSHOT` coordinate. Providers and consumers must be rebuilt together; a coordinate
alone does not prove the required methods are present. Missing or unhealthy authority denies effects.
Campaigns, published battle windows and frozen attendance belong to MFAddon, while mercenary
pledges belong to Nomads. MF owns actual relationships, claims, membership, native combat and gates.

World/policy queries and effect leases run on Paper's server thread against bounded cached state.
`SiegeNativeExpectation` binds the provider epoch, exact durable opposing war identities, exact
claim owners and current actor-to-realm membership. Empty war pairs are permitted for logistics;
battle consumers must separately require every scheduled opposing pair. Claims cannot be empty.
Admission uses nonblocking acquisition and denies pending native mutations. Native repository
commits, faction changes, relationships, claims, gate edits, redstone and animation intersect the
same mutation fence. A lease is closed on its acquiring thread and never encloses JDBC or waiting.
An unacknowledged repository commit poisons effect admission until a new verified runtime.

`permitsDestruction` rechecks current hostile ownership, native war, admin-owned realm policy,
explosion protection and embassy state for every exact block. `permitsPlayerDamage` uses the
same native duel/faction/war/friendly-fire/mercenary and protected-territory decision as the damage
listener. Native Bukkit damage remains authoritative and cancellable; `DamageSource.causingEntity`
provides player attribution, with direct-player/projectile-shooter fallback for ordinary events.
Consumers must retain their own region, actor, world and battle checks around the admitted effect.

Gate disruption has three distinct phases:

1. A worker calls `prepareGateDisruption(operation, gateId, originalRevision)`. A native transaction
   records the exact original owner, world, area, trigger, material and revision as PREPARED.
   The acknowledged runtime reservation blocks edits, faction deletion and automatic animations.
   Preparation changes no actuator or world block.
2. The server thread rechecks the complete consumer policy, obtains a fresh combined effect lease
   and calls `activateGateDisruption` under the native lease. This performs only a bounded cached
   actuator transition to DISRUPTED at revision+1. No database work occurs inside the lease.
3. After releasing leases, a worker calls `commitGateDisruption`. The native transaction records
   the new gate and COMMITTED receipt together. An activated ambiguous failure stays quarantined;
   it is never automatically cancelled, resumed or replayed.

The original one-call `disruptGate` provider entry point denies execution. Worker cancellation is
allowed only for this runtime's unactivated PREPARED intent and releases the reservation after
durable cancellation. Startup PREPARED receipts quarantine the native actuator until explicit
observed-before/after review. `reviewGateDisruption` requires a real online server-thread player
with `mf.gate.disruption.review`; its future completes from a worker. The caller must independently
review exact world/source observations and marshal completion before settling its own journal.

`gatesAt` publishes disruption operation UUID strings and phase. Exact matching live DISRUPTED
gates retain COMMITTED proof after runtime reservation release and restart. The proof is bounded
by live gates; a changed owner, footprint, trigger, material or revision invalidates it. It proves
the provider's actuator transition, not the consumer's terrain write or accounting completion.
Migration 908 adds native receipts without creating a backup; unsupported repository capabilities
deny the protocol instead of pretending to persist it.

The shared `MercenaryRosterSource` API carries immutable company pledges, fighters and a
nonblocking roster lease. Bridge's former interface extends this contract for compatibility.
Nomads registers both interface identities with the same authoritative projection and fences
durable pledge changes. Missing roster fencing is unavailable for battle admission.
