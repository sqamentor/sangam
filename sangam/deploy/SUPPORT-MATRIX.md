# Sangam support matrix (R5, R7, R17, R32)

Combinations outside this matrix are unsupported (R5). Each row has a status:

- **Declared**: the target. CI is set up for it, but no release has yet passed it.
- **Compile-checked**: compiled on that JDK, not run.

**No row is certified yet.** A row becomes *Certified* only when a release's evidence pack (`05-tests`, R52) shows it passing T28 and T31. Update this file from that evidence at each release.

## 1. Java runtime

| JDK | Role | Images | CI | Status |
|---|---|---|---|---|
| **25 LTS (Temurin)** | **Baseline and default runtime (R5)** | `eclipse-temurin:25.0.1_8-jre-noble`, `gcr.io/distroless/java25-debian13:nonroot` | `ci-build.yml` (build + unit tests), `db-matrix.yml`, `release.yml` | Declared; see the local check in section 7 |
| 21 LTS (Temurin) | Supported minimum; Java SDK consumers and existing installs | Build args on `server.Dockerfile` | `ci-build.yml` (build + unit tests) | Declared |

Bytecode level: the parent pom still sets `maven.compiler.release=21`, so JDK 25 is the runtime and build JDK and the class files stay Java 21. Moving to `release=25` would drop JDK 21 and is an integrator decision.

## 2. Frameworks

| Component | Version | Note |
|---|---|---|
| Operaton | 2.2.0 | Engine, as a dependency (ADR-D-16) |
| Spring Boot | 4.1.2 | Fixed by the Operaton starter |
| Liquibase | 4.33.0 | Below 5.0 (LIC-7) |
| Jackson | ≥ 2.22.3 | Licence gate floor |
| Node (UI build only) | 22 | Not a runtime dependency |

## 3. Databases and drivers (R17, CF-11)

| Database | Tier | Driver | Shipped? | CI cadence |
|---|---|---|---|---|
| PostgreSQL 17 | 1 | pgJDBC 42.7.7 | In fat jar | Every PR, nightly, release |
| Oracle Free 23 | 1 | `ojdbc11` / `ojdbc17` | **No**, customer-supplied (LIC-2) | Affected PRs, nightly, release |
| SQL Server 2022 (RCSI ON) | 1 | mssql-jdbc 12.10.1 | In fat jar | Affected PRs, nightly, release |
| MySQL 8.4 (READ COMMITTED, utf8mb4) | 1 | MariaDB Connector/J 3.5.3 | Separate jar in `lib-ext` (LIC-5) | Affected PRs, nightly, release |
| MariaDB 11.4 | 2 | MariaDB Connector/J 3.5.3 | Separate jar | Weekly |
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

Each row follows its upstream's security fixes for as long as the upstream supports that line. The Sangam support window per release is not yet set (R32, D-11).

## Local JDK 25 evidence

Local check on 2026-11-08, Microsoft OpenJDK 25.0.5 on Windows, compile only, no tests:

- All reactor modules compile, including the control plane, gateway, roll-up and Java SDK, except `sangam-server`.
- `sangam-server` failed in `HealthHooksBridge.java` while other workstreams were editing it. **The server has not yet been compiled on JDK 25.**
- The native worker module builds, and its `--smoke` passes on the JVM (36 records, 0 failures). The native executable itself has not been built yet.

Replace this section with CI results once CI has run.
