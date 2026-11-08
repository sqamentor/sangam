# Sangam support matrix (R5, R7, R17, R32)

Combinations outside this matrix are unsupported (R5). Each row has a status:

- **Declared**: the target. CI is set up for it, but no release has yet passed it.
- **Compile-checked**: compiled on that JDK, not run.
- **Unit-tested (local)**: full reactor build + all unit tests (surefire) passed on that JDK on a developer machine; integration/acceptance suites (failsafe, Testcontainers, T28/T31) not part of it.
- **Being validated**: a lane is set up and its acceptance run is pending; not supported until it passes.

**No row is certified yet.** A row becomes *Certified* only when a release's evidence pack (`05-tests`, R52) shows it passing T28 and T31. Update this file from that evidence at each release.

## 1. Java runtime

| JDK | Role | Images | CI | Status |
|---|---|---|---|---|
| **25 LTS (Temurin)** | **Baseline and default runtime (R5)** | `eclipse-temurin:25.0.1_8-jre-noble`, `gcr.io/distroless/java25-debian13:nonroot` | `ci-build.yml` (build + unit tests), `db-matrix.yml`, `release.yml` | **Unit-tested (local)** 2026-11-08, see "Local JDK evidence" |
| 21 LTS (Temurin) | Supported minimum; Java SDK consumers and existing installs | Build args on `server.Dockerfile` | `ci-build.yml` (build + unit tests) | Declared |
| 27 (OpenJDK early-access build 27+35) | Forward-compatibility lane (SG-06); not a release runtime | None | none yet | **Unit-tested (local)** 2026-11-08, see "Local JDK evidence"; not supported for production |

Bytecode level: the parent pom still sets `maven.compiler.release=21`, so JDK 25 is the runtime and build JDK and the class files stay Java 21. Moving to `release=25` would drop JDK 21 and is an integrator decision.

## 2. Frameworks

| Component | Version | Note |
|---|---|---|
| Operaton | 2.2.0 | Engine, as a dependency (ADR-D-16) |
| Spring Boot | 4.1.2 | Fixed by the Operaton starter |
| Liquibase | 4.33.0 | Below 5.0 (LIC-7) |
| Jackson (jackson-databind / jackson-bom) | 2.22.x (pinned 2.22.3) | Licence gate floor; 2.22.x only, because the FEEL closure's jackson-module-scala 2.22.3 refuses any other databind minor. `SangamFeel.install` fails fast outside 2.22.x (SG-33): import `org.sangam:sangam-bom` and set `jackson.version` / import `jackson-bom` 2.22.3 in your own parent pom |
| Node (UI build only) | 22 | Not a runtime dependency |

## 3. Databases and drivers (R17, CF-11)

| Database | Tier | Driver | Shipped? | CI cadence |
|---|---|---|---|---|
| PostgreSQL 17 | 1 | pgJDBC 42.7.12 | In fat jar | Every PR, nightly, release |
| Oracle Free 23 | 1 | `ojdbc11` / `ojdbc17` | **No**, customer-supplied (LIC-2) | Affected PRs, nightly, release |
| SQL Server 2022 (RCSI ON) | 1 | mssql-jdbc 12.10.2 | In fat jar | Affected PRs, nightly, release |
| SQL Server 2025 (RCSI ON) | 1 candidate | mssql-jdbc 12.10.2 | In fat jar | **Being validated (SG-05)**: not supported until its acceptance run passes |
| MySQL 8.4 (READ COMMITTED, utf8mb4) | 1 | MariaDB Connector/J 3.5.11 | Separate jar in `lib-ext` (LIC-5) | Affected PRs, nightly, release |
| MariaDB 11.4 | 2 | MariaDB Connector/J 3.5.11 | Separate jar | Weekly |
| Db2 12.1 | 2 (experimental, non-blocking) | JCC | **No**, customer-supplied (LIC-3) | Weekly |
| H2 | Development and tests only | Bundled | In fat jar | Never for production |

`SANGAM.md` lists other PostgreSQL, Oracle and SQL Server versions in places, and `deploy/README.md` section 1.4 mentions them as preparation guidance. Only the versions above run in CI, so only they can be certified.

## 4. Deployment targets (IX.6)

| Target | Artefact | Status |
|---|---|---|
| Kubernetes ≥ 1.30 | Helm chart `deploy/helm/sangam` | Declared; rendering is checked by CI only |
| VMs with Docker or Podman | `deploy/compose` as a template | Declared |
| VMs without containers | Fat jar + `loader.path` | Declared |
| Air-gapped | `deploy/airgap` bundle | Declared; AC-11/T50 not yet run |
| MeghRaj / NIC cloud | Kubernetes or VM rows above | Declared; hosting norms still open (IX.7a) |

## 5. Native-light profile (R7)

Native image is limited to **the Java SDK worker**: `deploy/native/sdk-worker`, built with GraalVM CE 25, without GraalJS or polyglot (LIC-1, D-8).

**No native promise** for `sangam-server`, `sangam-control-plane`, `sangam-rollup` or `sangam-gateway`. They run on the JVM only. Native is unavailable for the server because of the engine's dynamic class loading and MyBatis.

| SDK surface | Native status |
|---|---|
| Worker runtime: `SangamWorker`, `TopicSubscription`, `TaskContext`, lease heartbeat, back-off | Supported |
| `work()`: fetch-and-lock, extend-lock, complete, fail, bpmn-error, unlock, outcome-unknown, cancel-ack | Supported |
| `processes()`: start, get, messages, signals; `decisions()`: evaluate | Supported |
| `tasks()`: list, get, claim, complete | Supported |
| Auth: `StaticTokenProvider`, `ClientCredentialsTokenProvider` | Supported |
| `TypedValue`, problem+json errors, retries, Idempotency-Key | Supported |
| `admin()`, `ops()`, `audit()`, `repository()`, `transport()` raw calls | Unsupported: may work, not covered by the profile |
| Handlers mapping their own POJOs with Jackson | Unsupported unless you add reflection metadata for those types |
| Dynamic class loading, JMX, script engines, Java agents | Unsupported |

**What the evidence covers.** `--smoke` checks offline that Jackson can map every SDK model record and that a client can be built. It does not call a server, so "Supported" means *in profile*, not *proven against a live server*. A worker contract run on the native binary (T01–T04) is still to be added.

Build: `deploy/native/sdk-worker/pom.xml` (profile `native`), or `deploy/docker/sdk-worker-native.Dockerfile`. CI: the `java-native` job in `sdks.yml`.

When SDK model records change, run `generate_native_config.py`. CI fails if the metadata is stale.

## 6. Deployable applications

| Application | Image | Ports (app / management) | Database | Helm |
|---|---|---|---|---|
| Server (includes config, ledger, registry, billing, case, programme, resources, geo, field, compliance, connectors-health, cell as libraries) | `sangam-server` | 8080 / 8081 | Tier 1 | Always |
| Control plane | `sangam-control-plane` | 8090 / 8091 | Own | `scale.controlPlane.enabled` |
| Gateway | `sangam-gateway` | 8000 / 8001 | None | `scale.gateway.enabled` |
| Roll-up | `sangam-rollup` | 8095 / 8096 | Own | `scale.rollup.enabled` |
| UI | `sangam-ui` | 8080 | None | `ui.enabled` |
| Native worker | `sangam-sdk-worker` | None | None | Not in the chart |
| Simulators (dev only) | `sangam-simulators` | 9000 | None | Not in the chart |

## 7. Patch support

Each row follows its upstream's security fixes for as long as the upstream supports that line. The Sangam support
window per release is set in section 8.

## 8. Release lines, LTS and upgrade path (SL-46)

| Line | Cadence | Support | Upgrade path |
|---|---|---|---|
| Feature release `x.y` | as needed | security and critical fixes until the next feature release plus 3 months | `x.y` to any later `x.y'` of the same major in one step |
| LTS `x.y` (one per calendar year, tagged `-lts`) | yearly | 24 months of security and critical fixes; no new features | LTS to the next LTS in one step; LTS to any later feature release |
| Major `x` | when the REST contract must break | the previous major's last LTS keeps its 24 months | through the last LTS of the previous major |

Rules:
- **REST API.** `/api/v1` changes only in compatible ways. An operation is removed only after it was listed in
  `sangam-rest/api/sangam-api-deprecations.yaml` for at least the minimum support window (365 days) and its sunset
  date has passed. Policy and gate: `sangam-rest/API-LIFECYCLE-POLICY.md`, `OpenApiCompatibilityTest`.
- **Schema.** Liquibase change sets are forward only. Every supported upgrade path in the table runs the change sets
  of the releases in between in order; no release skips a change set.
- **In-flight work.** Running process and case instances keep their deployment binding and profile binding across an
  upgrade (`ChangeAdoptionTest`, section C of `SECTOR-CONFORMANCE.md`).
- **Packs.** A pack built for `x.y` installs on every later release of the same major.

References (official vendor pages; titles as published, not re-fetched in this pass): Salesforce "API End-of-Life
Policy" (Salesforce Developers, REST API Developer Guide); ServiceNow "Release cycle" and "Upgrade" pages (ServiceNow
product documentation); Microsoft "Release planning" and "Solution concepts" for Dataverse (Microsoft Learn).

## Local JDK evidence (SG-06, SG-07)

Run on 2026-11-08, Windows 11, `mvnw -B -fae -Dsangam.target=<dir> test` (every module, every unit test, surefire
only), on a private worktree of commit 15e4135 plus the SL-18 changes and the class-path auto-deployment fix
(`operaton.bpm.auto-deployment-enabled: false`). JDKs: Microsoft OpenJDK 25.0.5 (`jdk-25.0.5.0.2+1`) and OpenJDK 27
early access `jdk-27+35`; JAVA_HOME was set only for those processes.

| Module (with unit tests) | JDK 25 | JDK 27 |
|---|---|---|
| sangam-core | PASS (72) | PASS (72) |
| sangam-engine | PASS (65) | PASS (65), note 2 |
| sangam-ledger | PASS (25) | PASS (25) |
| sangam-ai | PASS (22) | PASS (22) |
| sangam-server | PASS (21), note 1 | PASS (21) |
| sangam-billing | PASS (18) | PASS (18) |
| sangam-packs | PASS (15) | PASS (15) |
| sangam-security | PASS (15) | PASS (15) |
| sangam-channels | PASS (13) | PASS (13) |
| sangam-modeller | PASS (12) | PASS (12) |
| sangam-ops | PASS (12) | PASS (12) |
| sangam-registry | PASS (10) | PASS (10) |
| sangam-config, sangam-onboarding, sangam-cli | PASS (5 each) | PASS (5 each) |
| sangam-flowable-ports, sangam-connectors-health | PASS (4 each) | PASS (4 each) |
| sangam-programme | PASS (3) | PASS (3) |
| sangam-persistence | PASS (2) | PASS (2) |
| sangam-integration | PASS (1) | PASS (1) |

The other reactor modules (BOM, compliance, REST, resources, case, geo, field, cell, Java SDK, control plane,
gateway, roll-up) **compile** on both JDKs. They have no surefire unit tests, so they are compile-checked only.

Notes:

1. The first JDK 25 run failed `sangam-server` at context load, with the duplicate decision key
   `ifms-procurement-mode`. The starter's class-path auto-deployment had deployed every jurisdiction profile together.
   This is not JDK-specific. It is fixed by `auto-deployment-enabled: false`; another workstream made the same fix in
   the main tree. The rerun passed.
2. The first JDK 27 run failed `EngineInternalsPinTest.i8_flushBackstopTakesRootLockForPlainEngineCommands` once,
   while the machine was under shared heavy load (the test gives the blocked command a 2-second window). Two reruns on
   JDK 27, two on JDK 25 and the full rerun all passed. This is recorded as a load-sensitive timing test, not a JDK
   defect.
3. GraalVM is not installed on this machine, so the native worker executable was **not built**.

Nothing here is *Certified*. Certification still needs CI and the release evidence pack (T28, T31).
