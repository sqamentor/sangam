<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-assembly

The plain-Java assembly layer and the host SPI (M-141, M-144; owner decisions 54R, 80 and 81). Design:
`sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md`, sections 2, 3 and 10.

## What it is

- **Never a host.** It names no host framework (Spring, Quarkus, Micronaut, Helidon, CDI, JSR-330, Guice,
  Dropwizard, JAX-RS, Jersey). `HostFrameworkBoundaryTest` (sangam-core) and `AssemblyIsPlainJavaTest` (this module:
  sources and pom) enforce it.
- **Dependencies:** sangam-core and slf4j-api only.

## Host SPI (`org.sangam.assembly.host`)

| Seam | Types | Kind |
|---|---|---|
| Lifecycle | `SangamRuntime` (start by phase, stop in reverse, refuse before anything starts), `Lifecycle` | required |
| Transaction and connection | `ConnectionBinding` (DataSource, core `Transactions`, the P-1 statement) | required |
| HTTP | `HttpBinding` | convenience |
| Security context | `SecurityContextBinding` | required |
| Config source | `SangamProperties` (typed readings, Spring-compatible booleans and durations), `ModuleSwitch` | required |
| Scheduling | `HostScheduler`, `HostScheduler.standalone` | required |
| Health | `HealthCheck`, `SangamRuntime.health()` | convenience |

A host collects its seams in `HostSeams`. The seam capabilities are derived from what it handed over, so a host can
never claim one. `CapabilitySet` resolves needed against supplied once, using the catalogue `Capabilities`
(design note matrix 3.1 plus the seams of 10.1).

A missing required capability refuses start-up, or the affected operation, with `CapabilityMissingException`
(`CAPABILITY_MISSING`). A missing convenience is reported only.

## History archive (RM-52, decision 386)

`org.sangam.assembly.history.HistoryArchiveAssembly` reads the `sangam.history.archive.*` settings once for every
host. With `enabled=false` (the default) it returns the gate that refuses every purge, so no engine history leaves
the database. With `enabled=true` it builds `HistoryArchives` over the file-system document store wrapped in a
`RetentionClassStore` (the archive's retention class is enforced on every store and delete). A missing, short or
literal setting fails the start-up. sangam-ops and sangam-flowable-ports are optional dependencies here: the Spring
host brings both. The Quarkus host does not wire the archive yet and stays fail-closed (every purge refused).

## Rules for a host

- A host is a thin adapter.
- It contains no business rule, permission check, money arithmetic, audit write, idempotency or dedupe logic, retry or
  ordering policy, and no SQL.
- It never adds a default for a required capability.
- It passes the host conformance kit in `sangam-host-conformance`.

## Status (Wave 4)

- **M-144:** the SPI types and their unit tests.
- **M-141b:** `EngineAssembly` follows once M-141a lands; the extraction phases are in section 4 of the design note.
- Tests are written, not run (test phase).
