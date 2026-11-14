<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-host-conformance

The host conformance kit (M-144) and the cross-host equivalence scenarios (M-150). Design:
`sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md` section 10.2.

A host is supported only when it passes this kit. The kit is plain Java and names no host framework.

## How a host runs it

1. **Write a harness.** Implement `HostHarness` in the host's own test sources. It must:
   - start a fresh host with the tenants `conformance-a` and `conformance-b` provisioned;
   - hand out the host's own port instances;
   - give an administrator context per tenant;
   - take away the required capabilities it can, with the text that names each refusal;
   - list every other required capability as a gap, with the reason. Only the baseline host may have gaps.
2. **Extend the two kits:**
   - `HostConformanceKit`: the equivalence scenarios against the golden results, coverage of the capability catalogue,
     the baseline run without optional hosts, and the thinness report;
   - `HostFailClosedKit`: for every removable required capability, a start without it must be refused, for that
     reason.

The Spring baseline host does this in `sangam-server/src/test/java/org/sangam/server/host/`.

## Equivalence (M-150)

`EquivalenceScenarios` runs fixed operations through the host's ports, using the fixed models of `ConformanceModels`
(a DMN fee rule in minor units, and a process that evaluates it). The scenarios cover:

- **Money:** the rule at its pinned version, and inside a process step.
- **Authorization:** another tenant gets not-found, and changes nothing.
- **Audit:** the evidence chain of the business key verifies.
- **Duplicate prevention:** an idempotent replay, a key reused with another body, an invalid key.

Each `BusinessResult` is canonical text: no ids, no timestamps, and number types normalised. It must equal
`golden-results.properties` exactly.

Two hosts that both pass give the same business result for the same operation, inputs and rule versions.
`CrossHostEquivalence.acrossHosts` compares hosts directly. With `-Dsangam.conformance.report-dir=<dir>`, each host
writes its observed results to that directory for a diff.

`OptionalHosts` and `DefaultReactorHasNoOptionalHostTest` prove the supported build needs no optional host component.
No default-reactor module declares Quarkus, Micronaut or Helidon, and the baseline run has none on its class path.

## Status (Wave 4)

The code and tests are written. Nothing has run yet: it runs in the test phase, H2 first, then PostgreSQL, Oracle and
SQL Server.

Scenarios still to add once their ports exist host-neutrally:

- ledger posting balance;
- outbox order across a relay restart;
- inbox dedupe;
- job fencing after a lease loss;
- audit tamper detection.
