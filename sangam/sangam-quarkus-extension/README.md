<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-quarkus-extension: the optional Quarkus host (M-143)

Owner decision 186 puts this host in 0.1.0 as an **optional adapter**. Decision 137 sets the rules for it:

- The core stays framework-independent.
- Quarkus is never a mandatory dependency.
- Deployment approval is a separate status and is **NOT APPROVED**.

The default reactor does not build this module. Only the opt-in reactor profile builds it; the integrator adds that profile to the root pom. No other module depends on Quarkus. `OptionalHosts` / `DefaultReactorHasNoOptionalHostTest` and `HostFrameworkBoundaryTest` check this.

## What the Quarkus host offers in 0.1.0 (owner decision 278)

The ports and the engine only:

- No REST controllers. The HTTP surface (`http`) stays with the Spring host.
- No OIDC over HTTP (`oidc-http`).

The capability catalogue marks both as Spring-only (`Capabilities.SPRING_ONLY`). This host reports them absent and never claims them.

## What it adds

The host hands five seams to the plain-Java assembly (`sangam-assembly`, design note section 10.1). By owner decision 279, each of the first four is an optional CDI bean. `QuarkusHost` takes them through `Instance`. If a bean is absent (for example an operator's `quarkus.arc.exclude-types`), that seam is not handed over, its capability is missing, and the start is refused with `CAPABILITY_MISSING`:

| Seam | Quarkus side (bean) |
|---|---|
| config source | `QuarkusConfigSource`: `SangamProperties` over the MicroProfile `Config`. The host reads its configuration only through it. |
| lifecycle | `QuarkusLifecycle`: `StartupEvent` starts `SangamRuntime`; `ShutdownEvent` stops it, draining for up to `sangam.shutdown.drain` (default 30 s) |
| scheduling | `QuarkusScheduling`: the assembly's standalone scheduler, which the runtime stops last. Without the bean there is no fallback (`HostSeams.Builder.withoutScheduler`). |
| security context | `QuarkusSecurityContext`: `RequestContextSecurity` on the ArC request context (`BoundPrincipal`) |
| shared transaction | `JtaTransactions` over Narayana's `TransactionManager` and the default Agroal data source; withdrawn by the data source switches below |

`SangamEngineConfiguration` passes the same two objects to the Operaton Quarkus engine. Engine commands and product rows therefore run in one JTA transaction (P-1).

## Sangam's engine rules on the Quarkus engine

`SangamEngineConfiguration` runs these steps in order:

1. The core UTC gate.
2. One refusal for any missing host capability together with the engine checks that need no database (extraction phase 1b, `EngineAssembly.checkBeforeDatabase`): the Jackson line of the FEEL engine, the product schema step, and the job leases while executor partitioning is on. Neither step touches the database.
3. The database checks on the very data source the engine is handed (`EngineAssembly.checkDatabase`, owner decision 287 c). A pool that hands the engine another isolation level is refused with the database guard's own message.

It then applies Sangam through `SangamEngineConfigurer`, the same path every other embedding uses:

1. `new SangamEngineConfigurer(settings)`: the UTC gate.
2. `prepare`: the database checks and the product schema before the engine.
3. `configure`: the engine options and `SangamProcessEnginePlugin` first in the plugin list (exact decimal type, strict FEEL, tenant resolution and maintenance-job policy, partitioned job acquisition, replay capture guards, engine patches), then `EngineSettingsReapplyPlugin` last.
4. After the engine is built: MySQL engine patches, history partitioning and the engine adapter.

`EngineCapabilityGuard` refuses an engine configuration without `SangamProcessEnginePlugin`.

Engine settings (`QuarkusEngineSettings`):

- `sangam.engine.system-audit-scope` is the mandatory system audit scope, default `system:platform`.
- `sangam.engine.apply-product-schema` (owner decision 287 b), default `true`. This host has no schema step of its own, so `false` leaves schema-before-engine missing and the start is refused.
- `sangam.engine.executor-partitioning` (owner decision 286), default `false`. The job leases are needed only while it is on; the engine supplies them and refuses, at engine build, an acquisition option the partitioned query does not reproduce (M-056).
- The host-neutral settings of `EngineAssembly.applyConfiguration` are read.
- Any other `sangam.engine.*` key is refused at start-up, never ignored. So is any environment variable whose MicroProfile environment form starts with `SANGAM_ENGINE_` and is not the form of a supported key (`SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE`, `SANGAM_ENGINE_APPLY_PRODUCT_SCHEMA` and `SANGAM_ENGINE_EXECUTOR_PARTITIONING` are accepted).

Parts of the plugin that cannot run on the Quarkus engine, and how they fail closed:

- The virtual-thread and bulkhead job executor options need Sangam's own job executor. Quarkus supplies its managed job executor, so `JobExecutorSetup` throws at engine build when they are set. Those keys are also refused by `QuarkusEngineSettings`.
- The module ports (audit sink, authorization, idempotency and the rest) are not assembled on this host. Their capabilities are missing, so the guard refuses start-up.

## What stays available without it

Everything. The supported host is the Spring standalone server (`sangam-server`), and it never has Quarkus on its class path.

## How a missing control fails safe

The engine area (utc-jvm, database-checks, schema-before-engine, jackson-feel-compatibility, and job-leases while executor partitioning is on) is checked by the engine step itself on every start (extraction phase 1b). A failed check returns `CAPABILITY_MISSING` with the check's own exception kept as a suppressed exception.

Every other required capability outside the five seams returns `CAPABILITY_MISSING` until its extraction phase (3, 2, 5) assembles it. These are:

- tenant-security-plugin, authorization
- audit-chain, idempotency-store, outbox-relay-inbox
- money-invariants

The host refuses at two points, so it never downgrades silently:

1. **Before the engine is built.** `EngineCapabilityGuard`, the last engine plugin, refuses in `preInit`, so no job ever runs.
2. **At Quarkus start-up.** `SangamRuntime.start()` refuses again.

The shared-transaction seam is withdrawn, and the start refused, in three cases:

- `quarkus.datasource.jdbc.transactions=disabled`
- `quarkus.operaton.datasource` names a data source other than the default
- the engine holds another transaction manager or data source

## Dependencies (D-5, decision 137(2))

The module depends on:

- `operaton-bpm-quarkus-engine` 2.2.0;
- `quarkus-arc`, `quarkus-agroal` and `quarkus-narayana-jta` 3.40.1.1. This is the Quarkus version Operaton 2.2.0 pins.

The closure has these adjustments:

- **Jackson** is held at 2.22.3, the licence policy minimum.
- **Spring** (`spring-beans`, `spring-core`) is excluded. The engine reaches Spring only through its XML (beans) configuration, which this host never calls. `EngineWithoutSpringTest` covers this.
- **jna** is held at 5.17.0 in the test closure (owner decision 275; the Quarkus dev-services test chain would bring 5.8.0).
- **No JDBC driver extension.** The deployer chooses the database. `quarkus-jdbc-h2` would bring h2 2.4.240 and jts-core 1.19.0, which the licence review holds.
- **`quarkus-maven-plugin` is not used.** The deployer's application build runs the Quarkus augmentation. `META-INF/beans.xml` makes this jar a bean archive.

All downloads, licences and checksums are recorded in `sangam-handoff/DOWNLOADS-WAVE4.md` (W4-F/B66 D-5 lines).

## Tests

Tests are written but have not been run (status NOT_RUN). They are plain JUnit and do not boot Quarkus:

| Test | What it checks |
|---|---|
| `QuarkusConfigSourceTest` | config source |
| `JtaTransactionsTest` | joining, owning and rolling back a transaction; engine and product rows in one JTA transaction on Narayana with Agroal and H2 |
| `QuarkusHostTest` | exactly five seams; every other required capability outside the engine area refuses with CAPABILITY_MISSING, both at the engine build and at start-up; engine checks not run count as missing at the engine build; job leases needed only with executor partitioning (decision 286, both cases) |
| `QuarkusSeamsTest` | decision 279: without each seam bean its capability is missing and the start is refused (no standalone scheduling fallback); decision 278: HTTP and OIDC over HTTP never claimed |
| `EngineWithoutSpringTest` | no Spring on the class path; the engine builds, deploys and runs |
| `SangamEngineConfigurationTest` | the Quarkus engine configuration carries `SangamProcessEnginePlugin` first, the guard and after-build step, `EngineSettingsReapplyPlugin` last, the host's data source and transaction manager, and the system audit scope; the bean refuses before any database access, also without the product schema (decision 287 b) |
| `QuarkusEngineSettingsTest` | default and configured system audit scope; the product schema and executor partitioning switches and their defaults; unbound `sangam.engine.*` keys and `SANGAM_ENGINE_*` environment forms refused; test mode refused in production |
| `EngineChecksTest` (sangam-assembly) | phase 1b engine checks: job leases needed only with partitioning (decision 286); schema step; database checks on the engine's data source (decision 287 c); one refusal keeps every reason |

### M-150 conformance kit on Quarkus (owner decision 229)

The kit runs from `org.sangam.quarkus.host.conformance` in this module's test sources:

- `QuarkusHostHarness` boots a fresh Quarkus application per start. It uses `QuarkusBootstrap` in TEST mode, offline, with no quarkus-maven-plugin.
- `QuarkusHostConformanceTest` and `QuarkusHostFailClosedTest` run the two kit classes. Run them separately (one engine per JVM).
- The test closure is `quarkus-junit` plus the nine `*-deployment` artefacts. It was licence-reviewed and downloaded under D-5. `jdk-classfile-backport` 26 and `org.eclipse.sisu.inject` / `org.eclipse.sisu.plexus` 1.0.1 are **test scope only**, by decision 229, and never shipped.
- The kit reaches the ports through `sangam-host-conformance` → `sangam-rest`, with `org.springframework` and `org.springframework.boot` excluded. The ports in `org.sangam.rest.port` import no Spring.

Every required capability is removable, and there are no gaps:

| Capability | How the harness takes it away |
|---|---|
| config source, lifecycle, scheduling, security context | `quarkus.arc.exclude-types` with the seam's bean, at augmentation (`QuarkusBootstrap` build system properties) |
| shared transaction | `quarkus.datasource.jdbc.transactions=disabled` |
| utc-jvm | a separate JVM (`ForkedStart`) with `-Duser.timezone=Asia/Kolkata`; the refusal is the core UTC gate's own (SL-53) |
| database-checks | `quarkus.datasource.jdbc.transaction-isolation-level=serializable` (decision 287 c) |
| schema-before-engine | `sangam.engine.apply-product-schema=false` (decision 287 b) |
| jackson-feel-compatibility | jackson-databind 2.21.7 forced into the application's dependency graph (`QuarkusBootstrap` forced dependency; decision 287 a) |
| job-leases | `sangam.engine.executor-partitioning=true` with `quarkus.operaton.generic-config.job-executor-deployment-aware=true`; the marker is the engine's M-056 refusal (decision 286) |
| the other six | absent until their extraction phase (decision 276: 3, 2, 5) assembles them |

Expected results until the extraction phases supply the rest:

- **Equivalence scenarios:** do not pass. Every start is refused with `CAPABILITY_MISSING`, and the host offers no ports (they come with phase 3).
- **Coverage:** expected to pass. All 16 required capabilities are removable and there are no gaps.
- **Fail-closed kit:** expected to pass for 14. database-checks and job-leases are checked after the host gaps (the database check after the database-free refusal, the job leases at engine build), so their markers appear once phases 3, 2 and 5 leave no host gap.

`jna` is pinned to 5.17.0 (owner decision 275), so the dev-services test chain no longer needs the flagged 5.8.0.

The test `application.properties` uses H2 as `db-kind=other` with an explicit driver, in `MODE=PostgreSQL` (the only H2 mode the database guard accepts). This is unverified on Quarkus 3.40.1.1 (NOT_RUN).
