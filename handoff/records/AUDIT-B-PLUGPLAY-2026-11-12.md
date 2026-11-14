# Audit B: is Sangam plug-and-play ready for any project? (2026-11-12)

Read-only audit of the integration tree `.claude\worktrees\workspace-scan-resume-06b524` (HEAD 1105d9c plus uncommitted
changes). No build, test, npm or git write was run. Paths are relative to that tree unless stated. "Tests written, not
run" is the repo's own statement for the current tree (SANGAM.md:6602-6608, CHANGES-SINCE-1105d9c.md:3-6).

## Short answer

**No, not completely.** The standalone server, REST API, packs/onboarding, compose/Helm/air-gap tooling and five Tier-1
SDKs are **coded**. On the current tree **nothing is certified and no behaviour test has run** (SUPPORT-MATRIX.md:10;
SANGAM.md:6669). Testing is not the only thing left. There are also real **code and packaging gaps** for "plug into
*any* project":

1. There is no embedded starter (Spring Boot or Quarkus).
2. The SDKs are typed subsets, have no test engine, and are not published to any package registry.
3. No artefacts are published to Maven Central, NuGet, PyPI, npm or Packagist. There is no release tag, and the
   version is 0.1.0-SNAPSHOT.
4. Database-per-tenant is not wired into the server.
5. The control-plane and roll-up APIs have no OpenAPI contract.
6. The UI supports en/hi only.
7. Throughput at national scale has not been measured.

## 1. Promised modes and criteria vs evidence

| # | Promise (source) | Implemented (evidence) | Documented | Runnable example | Proof status |
|---|---|---|---|---|---|
| 1 | Standalone server + REST first (ADR-D-5, SANGAM.md:58) | `sangam-server` Boot 4.1.2 serves `/api/v1`, `/engine-rest` and the offline contract `/api/v1/openapi.yaml` (sangam-server/README.md:7-11). The contract has **879 paths / 1,047 operationIds**, `info.version: 0.1.0` (sangam-rest/api/sangam-api.yaml:6). SANGAM.md:6621 still says "283 paths" (stale) | Yes (server README, deploy/README) | `spring-boot:run -Dspring-boot.run.profiles=h2` + curl (sangam-server/README.md:15-21) | Server start was proven earlier on PostgreSQL 17 (migrate-only, then validate, readiness UP) on 2026-11-08 (TESTING-GAP-REGISTER.md:23, SG-14). Not re-run on the current tree |
| 2 | Embedded mode: Spring Boot / Quarkus starter (SANGAM.md:473-474, :553, :591) | **Partial.** Engine level only: `SangamEngineFactory.build(ds, settings, url[, plugins])` (sangam-engine/src/main/java/org/sangam/engine/SangamEngineFactory.java:11-35; sangam-engine/README.md:18, :132-148). `sangam-rest` has a Boot auto-config (`META-INF/spring/...AutoConfiguration.imports`), but its defaults include in-memory adapters (`adapter.memory.GroupPermissionAdapter` in SangamRestAutoConfiguration). The real product wiring is 36 `*Wiring` classes inside `sangam-server`. **No starter module and no Quarkus extension** in the reactor (sangam/pom.xml modules) | Partly (engine README, deploy/README.md:246-259, :368) | None (no sample host app) | Engine H2 tests written. The embedded path has no own acceptance |
| 3 | Tier-1 SDKs: Java, .NET, PHP, Python, TS (ADR-D-4, SANGAM.md:43) | All five present (`sdks/*`): typed client + worker runtime (VI.3 fencing, heartbeat, idempotency) | Yes, README per SDK | Yes, a bill-payment worker example in each SDK | See section 2 |
| 4 | Tier-2 SDKs Go, Rust, Kotlin (ADR-D-4) | **Missing** (only 5 dirs under `sdks/`) | Only in the decision text | No | n/a |
| 5 | Docker compose, 4 Tier-1 DBs (deploy/README.md:71-131) | `deploy/compose/docker-compose.yml` (+ scale, cells), DB init, Keycloak realm generator, OTel/Prometheus/Grafana | Yes | Yes (`docker compose --profile postgres up -d --build`) | `docker compose config` passes. Server vs PG proven locally on 2026-11-08 (SUPPORT-MATRIX.md:54) |
| 6 | Helm, K8s ≥1.30 (deploy/README.md:132-220) | `deploy/helm/sangam` (server, UI, HPA, PDB, NetworkPolicy, Liquibase job, backups, schema) + `sangam-national`. Six example values files | Yes | Yes (examples/values-*.yaml) | `helm lint/template` pass. **No cluster install run** (SUPPORT-MATRIX.md:53; SG-14) |
| 7 | VM without containers (fat jar + `loader.path`) | deploy/README.md:222-276; sangam-server/README.md:23-34 | Yes | Command lines | Declared only (SUPPORT-MATRIX.md:55) |
| 8 | Air-gapped (IX.6, AC-11/T50) | `deploy/airgap/*` (save, load, verify, SBOM); release.yml:354-359 | Yes (deploy/README.md:622-692) | Scripts | 2-image save/verify/tamper proven. **AC-11/T50 with product images not run** (SUPPORT-MATRIX.md:56) |
| 9 | Native (R7) | Java SDK worker only (`deploy/native/sdk-worker`, sdks.yml:44-75) | Yes (SUPPORT-MATRIX.md:59-81) | Dockerfile | **Not built locally** (no GraalVM, SUPPORT-MATRIX.md:162). Server native is excluded (SUPPORT-MATRIX.md:63) |
| 10 | Generic, configuration-only adoption: packs, profiles, onboarding (ADR-D-20, ACCEPTANCE-PLUG-AND-PLAY.md:4) | 132 packs (SANGAM.md:6650), `sangam-onboarding` (16 `/onboarding` paths), dry run, maker-checker, bundles, promotion (SL-42), pack uninstall/rollback (SL-41), `DomainNeutralityGuardTest` (SANGAM.md:4351) | Yes | AT-1…AT-7 data (`sangam-onboarding/src/test/resources/acceptance/AT-1..7.json`) | **Mixed:** ACCEPTANCE-PLUG-AND-PLAY.md:6 says "written, not yet run". But the register records `PlugAndPlayIT` on PostgreSQL / JDK 25 with AT 6 passed and AT-6 skipped, on an earlier tree (TESTING-GAP-REGISTER.md:168, APPROVE-401, 2026-11-10). Sector conformance: 22 rows PASS on H2 with simulators on 2026-11-08 (SECTOR-CONFORMANCE.md:31-54). Open data/logic items: SL-125 fiscal year silently assumed (OPEN, :158); SL-15 GA-55 unverified (:39); SL-111 (:144) |
| 11 | Signed third-party plugins (connector, pack, FEEL, UI) | `lib-ext/plugins`, Ed25519 signature, trusted keys, `requires` ranges, audit (examples/plugin-connector-example/README.md) | Yes | Yes (`examples/plugin-connector-example`) | **AT-6 never run** (manual, skipped in PlugAndPlayIT) |
| 12 | Auth: OIDC/JWT, platform vs tenant issuer, seats | `OidcConfig`, `SecurityConfig`, configurable claims, platform issuer SL-70 (sangam-server/README.md:65-108). Dev token refused in production. Keycloak realm for dev (deploy/compose/keycloak) | Yes | Compose Keycloak realm | Tests written. SL-70 "server compile waiting" note (TESTING-GAP-REGISTER.md:95); SL-107 open (:136) |
| 13 | Multi-tenant, row level (VI.4) | Tenant from token only; cross-tenant fixes SL-45/72/73 (TESTING-GAP-REGISTER.md:73, :97-98) | Yes | No | `CrossTenantIsolationIT` written. **SL-78 open:** one step proved nothing (:103) |
| 14 | Database-per-tenant routing (SANGAM.md:629; FC-34) | `TenantEngineRegistry` exists in sangam-flowable-ports (README.md:69, :127) but is **not referenced by sangam-server**. Isolation in the server comes from cells (DB per cell, ADR-D-21) | Port README only | No | Unit test only |
| 15 | Cells / control plane / gateway / roll-up (ADR-D-21) | Modules + Helm `scale.*` + `sangam-national` | Yes | values-scale.yaml, compose.scale/cells | **No OpenAPI for `/cells`, `/routes`, `/rollup`** (SANGAM.md:6677). T-SCALE throughput unmeasurable: pool deadlock under load (SG-15 OPEN, TESTING-GAP-REGISTER.md:24) |
| 16 | Tier-1 databases PG/Oracle/SQL Server/MySQL (ADR-D-15) | One Liquibase changelog per dialect, `IsolationGuard`, Helm `database.type` | Yes (SUPPORT-MATRIX.md:34-47) | Compose profiles | **No row certified** (SUPPORT-MATRIX.md:10). SG-04 IN PROGRESS (TESTING-GAP-REGISTER.md:15). SQL Server 2025 is a non-gating candidate (:41) |
| 17 | UI shipped | Separate `sangam-ui` image (nginx-unprivileged, runtime `/config.json`, deploy/docker/ui.Dockerfile:17-37). Helm `ui.enabled: true` (values.yaml:276-277). Compose UI on :3000 proxying `/api` (deploy/README.md:85). Plugin UI assets from the server (`/api/v1/plugins/ui`) | Yes | Compose | ui.yml builds and tests in CI. Detail is out of scope here (see Audit C). **Languages en/hi only** (SANGAM.md:6672) |

## 2. SDKs

| SDK | Typed surface | Unit tests | Packaging | Gaps (own README) |
|---|---|---|---|---|
| Java `org.sangam:sangam-sdk-java` | work, tasks, processes, decisions, audit, admin (untyped maps), ops, repository, me, plus raw `transport()` (sdks/java/README.md:37-50) | `ModelContractTest` (contract guard against the yaml), `SangamWorkerTest` + `FakeWorkServer`. A surefire report in `sdks/java/target-b5` shows **18 + 7 passed, 2026-11-12 01:28**; which tree produced it is not proven. SUPPORT-MATRIX.md:149 still calls the Java SDK "compile-checked only" (stale) | Maven jar copied into the GitHub release (release.yml:96). **Not deployed to any Maven repo** | No in-memory test engine (README:186); T01-T04/T18 contract tests not written |
| .NET `Sangam.Sdk` net8.0 | Same core groups; many endpoints via `ResourceApi` only (sdks/dotnet/README.md:212-215) | 4 test files (worker, contract guard, stored outcome) | `dotnet pack` in sdks.yml:100 as a CI artifact only | No test engine (:216). **Compile-checked on .NET 7 only, never built against net8** (:217) |
| PHP `sangam/sdk` ≥8.2 | Core groups. Responses are arrays (README:204-208) | ContractGuard, Runner, TypedValue (phpunit) | `composer validate` only. **Not on Packagist** | No test engine (:211) |
| Python `sangam-sdk` ≥3.10 | Core groups. History, packages, documents, links, batches, analytics etc. raw only (README:136-138) | 3 pytest files | `python -m build` CI artifact only. **Not on PyPI** | No test engine (:143) |
| TypeScript `@sangam/sdk` Node ≥20.3 | Core groups. Comments, attachments, modify, migrate, packages, documents, links, history raw only (README:182) | 4 node:test files | `npm pack` CI artifact only. **Not on npm** | README:184 says tests are "not written yet", which contradicts the 4 test files present (stale doc) |

**Coverage verdict.** Every SDK covers a **stated core subset**: the worker lease API, tasks, processes, decisions,
audit, plus basic admin and ops. Everything else is reached through a raw transport. None of the SDKs types the large
generic-module surface: registry (134 paths), admin (78), resources, field, case files, programmes, compliance, AI,
onboarding. Those are about 600+ of the 879 paths. Every SDK says it was reconciled against "0.1.0", but the contract
kept that version number while it grew from 283 to 879 paths, so "reconciled" cannot be checked from the version.

## 3. Integration friction

- **BOM:** `sangam-bom` exists. It pins Jackson 2.22.3, Liquibase 4.33.0 and the FEEL closure (sangam-bom/pom.xml:57-70),
  and SG-26 guards it (`LiquibaseLicenceGuard`). Consumers must import it **before** the Spring Boot BOM, or Boot 4's
  Liquibase 5.0.4 is refused at startup. The version is `0.1.0-SNAPSHOT` (:19), the repo has **no git tag**, and no
  repository is published, so a consumer has to build from source.
- **Hard version constraints for an embedding host:** Spring Boot 4.1.2 (forced by the Operaton starter), so a Boot 3
  host cannot embed it. Jackson databind **2.22.x only**, with fail-fast outside it (SUPPORT-MATRIX.md:31). A **UTC
  JVM** with no override switch (ADR-D-27, SANGAM.md:4331). Bytecode release 21 (SUPPORT-MATRIX.md:22).
- **JDK:** 25 and 27-EA are unit-tested on a developer machine (2026-11-08, older commit). 21 is "Declared" only
  (SUPPORT-MATRIX.md:16-18). The CI matrix exists (ci-build.yml:38) but has not run on this tree.
- **Required external services and secrets (production):** a Tier-1 DB with mandatory session settings (RCSI on SQL
  Server, READ COMMITTED and utf8mb4 on MySQL, sangam-server/README.md:110-115). A customer-supplied driver for Oracle
  (LIC-2), and the MariaDB connector in `lib-ext` for MySQL. An OIDC IdP with **two issuers** (a tenant realm and a
  separate platform realm for onboarding, SL-70). A KEK, an Ed25519 registry signer, an HMAC pepper, onboarding bundle
  keys, and non-default DB credentials (deploy/README.md:181-197). A ReadWriteMany `/var/lib/sangam` volume with a
  180-day compliance archive (:37). `SANGAM_CELL_JURISDICTION` on cells (:198). That is a heavy minimum for
  "drop-in".
- **Licences:** Apache-2.0. The licence gate is strict on release (release.yml:117-122). The `org.camunda` FEEL
  exception is documented (SG-27). LGPL MariaDB is shipped separately with a source offer.
- **DB matrix:** PG17, Oracle 23, SQL Server 2022 and MySQL 8.4 are Tier 1. MariaDB 11.4 is Tier 2 (coded, lane not run
  yet). **Db2 is unsupported by owner decision** (SL-114, SUPPORT-MATRIX.md:44); the db-matrix leg only prints a skip
  notice (db-matrix.yml:6, :123).
- **Consumer onboarding docs:** per-module READMEs are thorough. There is **no "integrate Sangam into your
  application" guide**: `sangam/docs/` holds only IFMS-KNOWLEDGE-LINKS.md.
- **Stale or contradictory docs found:**
  - deploy/README.md:48 says "Temurin JRE 21", but server.Dockerfile:37 uses 25.
  - ACCEPTANCE-PLUG-AND-PLAY.md:6 says "not yet run", but the register shows a PG run.
  - SUPPORT-MATRIX.md:149 calls the Java SDK compile-only.
  - The TS README says no tests are written.
  - SANGAM.md:6621 says 283 paths.
- **Not present from the Part I design:** gRPC worker stream, `proto/` and AsyncAPI contracts (SANGAM.md:543, :549).
  No `.proto` or AsyncAPI file exists. The current baseline says REST first (D-5), but nothing formally supersedes
  these items.

## 4. Verdict table

| Area | Verdict | What is missing | Confidence |
|---|---|---|---|
| Standalone server + `/api/v1` REST + OpenAPI | READY-PENDING-TESTS | AC-01…11, T01-T89 on the current tree | 92% |
| Control-plane / roll-up API contract | GAP | OpenAPI for `/cells`, `/routes`, `/rollup` (SANGAM.md:6677) | 90% |
| Embedded mode (Spring Boot / Quarkus starter) | GAP | Starter or extension with the product wiring (security, REST, packs) outside `sangam-server`; a sample host; acceptance. Only `SangamEngineFactory` exists | 90% |
| Embedded engine library (`SangamEngineFactory`) | READY-PENDING-TESTS | H2 tests to run; the constraints above (Boot 4, Jackson 2.22, UTC) | 88% |
| Java SDK | READY-PENDING-TESTS (stated subset) | Test engine (R18), T01-T04/T18, Maven publication | 88% |
| .NET / PHP / Python / TS SDKs | READY-PENDING-TESTS (stated subset) | Test engine; T01-T04/T18; registry publication; .NET never built on net8 | 85% |
| Go / Rust / Kotlin SDKs (Tier 2) | GAP | Not started | 95% |
| Artefact publishing (Maven Central, NuGet, PyPI, npm, Packagist) | GAP | release.yml publishes only GHCR images, a Helm tgz and a GitHub release; no tag yet | 90% |
| Docker compose | READY-PENDING-TESTS | Full stack run per DB on the current tree | 88% |
| Helm chart | READY-PENDING-TESTS | kind/cluster install; production-settings boot proof (SG-38 partly coded) | 88% |
| VM fat jar | READY-PENDING-TESTS | Any recorded run | 80% |
| Air-gapped bundle | READY-PENDING-TESTS | AC-11/T50 with product images | 88% |
| Native SDK worker | READY-PENDING-TESTS | First GraalVM build; worker contract on the binary | 85% |
| Native server / control plane / gateway / roll-up | OUT OF SCOPE BY DECISION (SUPPORT-MATRIX.md:63) | — | 95% |
| Packs, profiles, onboarding (configuration-only adoption) | READY-PENDING-TESTS | AT-1…7 on the current tree; SL-125 OPEN (fiscal year); SL-15 GA-55 unverified; real wire formats (PK-05, SANGAM.md:6678) | 85% |
| Signed plugins | READY-PENDING-TESTS | AT-6 has never run | 85% |
| OIDC auth, platform/tenant issuers | READY-PENDING-TESTS | Tests for SL-70/95/107 to run | 85% |
| Row-level multi-tenancy | READY-PENDING-TESTS | `CrossTenantIsolationIT` run + SL-78 fix | 85% |
| Database-per-tenant in the server | GAP | `TenantEngineRegistry` not wired into sangam-server; only cells give DB isolation | 80% |
| Tier-1 DBs (PG, Oracle, SQL Server, MySQL) | READY-PENDING-TESTS | Certification (T28/T31 evidence pack); no row certified | 90% |
| MariaDB 11.4 (Tier 2) | READY-PENDING-TESTS | First lane run | 85% |
| SQL Server 2025 | READY-PENDING-TESTS (candidate, non-gating) | Green acceptance run | 85% |
| Db2 | OUT OF SCOPE BY DECISION (SL-114) | — | 98% |
| JDK 25 / 27-EA | READY-PENDING-TESTS (unit PASS locally on an older commit) | CI run on this tree; ITs | 85% |
| JDK 21 | READY-PENDING-TESTS (declared only) | Any run | 80% |
| UI shipping/serving (image, Helm, compose, plugin assets) | READY-PENDING-TESTS | Detail in Audit C | 80% |
| Languages beyond en/hi ("any country") | GAP | Further locales; accessibility verification (SANGAM.md:6672) | 85% |
| National-scale load (ADR-D-24, 500 starts/s per cell) | GAP | Throughput unmeasurable until the SL-43 pool deadlock is cleared (SG-15 OPEN) | 85% |
| Consumer integration guide | GAP (documentation) | "Integrate into your project" guide | 85% |
| gRPC / AsyncAPI contracts (Part I design) | GAP or silently superseded | Either a decision recording them as superseded by D-5, or the contracts | 70% |

**Bottom line.** The server, packs and deployment path are code-complete and pending tests. But "complete plug-and-play
into ANY project" is not met even apart from testing: embedded starter, SDK breadth, test engine, package publication,
DB-per-tenant wiring, control-plane contract, locales and measured scale are all missing.
