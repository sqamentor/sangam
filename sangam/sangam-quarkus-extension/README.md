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
- The module ports (audit sink, authorization, idempotency and the rest) are not assembled on this host yet. Their capabilities are missing, so the guard refuses start-up.

## Integration services on this host (extraction phase 3; owner decisions 315, 316, 317)

Phase 3 (decision 276) brings the audit chain, the idempotency store, the outbox relay and the inbox to this host. It is **not finished**. This section says exactly what works today and what does not.

### What works today

| Item | Where | State |
|---|---|---|
| Broker inbox refusal (decision 317) | `BrokerInboxRefusal`, called by `QuarkusHost` | Works. See below. |
| Relay wake-up mode (decision 316) | `RelayWakeup` in `sangam-assembly`; `QuarkusHost.relayWakeup` | Decided in code and documented. Nothing calls it until the relay is assembled on this host. |
| Start and stop order of the integration services | `IntegrationLifecycles` in `sangam-assembly` | Plain Java, tested. Not called by this host yet. |
| Refusal without the scheduling seam (decision 315) | `HostSeams`, `SangamRuntime`, `IntegrationLifecycles.builder` | Works: the start is refused with `CAPABILITY_MISSING` naming `scheduling`, and no integration service is assembled. |
| The assembly's own sweeps on the host scheduler (decision 315) | `IntegrationSweeps` in `sangam-assembly` | Plain Java, tested. Not called by this host yet. |

### What does not work yet

The integration services themselves are **not constructed on this host yet**. `audit-chain`, `idempotency-store` and `outbox-relay-inbox` are still reported missing, so every start is still refused with `CAPABILITY_MISSING`. A capability is reported present only when its service really runs; none of the three does.

Round 5 (lane q1) removed the five blockers of phase 3 in code (the fifth by owner decision 376 in round 6):

1. **Attribution tenant: DONE.** The one resolver is plain Java: `org.sangam.assembly.host.AttributionTenant.resolve(SangamProperties)`. The Spring host's `org.sangam.server.config.AttributionTenant` now only reads its `Environment` through it. `QuarkusHost.attributionTenant()` resolves it at construction; the reserved shared key `-` refuses the start, naming `sangam.tenant.default`.
2. **Outbox destinations: DONE.** The rule is plain Java: `org.sangam.assembly.integration.OutboxDestinations`. A host hands in the destinations it has a publisher for (`Routes`). This host has no roll-up or history-projection publisher (`QuarkusHost.OUTBOX_ROUTES` is empty), so `sangam.cell.id` with `sangam.cell.rollup.ingest-url`, and any `sangam.history.projection.{search.url,kafka.topic,datalake.bucket}`, refuse the start with `OUTBOX_DESTINATION_NOT_DELIVERED`, naming each key. Open: the Spring host's `IntegrationWiring.subscriptions/merge` and `HistoryProjectionWiring.destinations` should call this class (assigned to lane w2 in round 6).
3. **Transactions for the idempotency store and the audit port: DONE.** `JtaTxSupport` is the `TxSupport` of this host, on the engine's own Narayana transaction manager and Agroal data source (`QuarkusHost.txSupport()`):
   - joining or owning a transaction;
   - an always-new transaction that suspends and resumes the running one (what Spring's JTA transaction manager does for REQUIRES_NEW);
   - one connection bound per transaction and closed at completion, refused outside a transaction;
   - rollback-only, reported to the owner when a participant set it;
   - after-commit work through a JTA `Synchronization`.
   `JtaTxSupportTest` covers each of these, also with the engine in the same transaction.
4. **Lifecycle authorizer: DONE (owner decision 374).** `org.sangam.assembly.integration.LifecycleAuthorization.require` hands out only a bound security module's authorizer. Without one, it refuses with `SECURITY_MODULE_NOT_BOUND`, naming the host. This host binds no security module before phase 2, so `QuarkusHost.lifecycleAuthorizer()` always refuses, also with `sangam.modules.security.enabled=false`. There is no allow-everything authorizer on this host.
5. **Order of engine build and capability check: DONE (owner decision 376, option C).**
   - Services that need no engine (audit chain and outbox writer, idempotency store) are claimed before the engine build (`QuarkusHost.CLAIMED_BEFORE_BUILD`). `EngineBuildClaims.requireClaimableBeforeBuild` refuses `outbox-relay-inbox` there.
   - The inbox and relay, reconciliation, lifecycle and maintenance services need the built engine. They claim `outbox-relay-inbox` only after the build succeeded (`org.sangam.assembly.host.EngineBuildClaims`): the host `expect`s it before the build, and `EngineStartOrder.postProcessEngineBuild` assembles it. Before the build an expected capability is pending, not missing (`QuarkusHost.capabilitiesBeforeBuild()`, read by `EngineCapabilityGuard`). One that nothing expects is missing already then.
   - The capability set is settled at start-up (`SangamRuntime.settledAtStart`). An expected capability whose engine was never built, or whose assembly failed, is missing. The start is refused with `CAPABILITY_MISSING`, with the assembly failure suppressed in the refusal.
   - The engine is built with the job executor off (`org.sangam.assembly.engine.EngineStartOrder`, in the plugin list right before the settings re-apply). Its `preInit` keeps the configured wish and switches the activation off; its `postInit` refuses a build whose job executor would still start with it.
   - The job executor is the runtime's last component (`sangam-engine-job-executor`, phase `Integer.MAX_VALUE`). It starts only after every capability check passed and every other component started, and stops first. A refused start leaves it stopped, so no job runs before the check (`EngineStartOrderTest`).
   - In 0.1.0 this host still assembles none of these services (`CLAIMED_BEFORE_BUILD` is empty and nothing is expected), so every start is still refused with `CAPABILITY_MISSING`. `SECURITY_MODULE_NOT_BOUND` (decision 374) is unchanged.
### Scheduling (decision 315)

- The integration services keep their own executors. No service takes a `HostScheduler`.
- A host without the scheduling seam is refused at start (`CAPABILITY_MISSING` naming `scheduling`). `IntegrationLifecycles.builder` gives the same refusal, so nothing is assembled for such a host.
- The assembly's own two sweeps run on the `HostScheduler` (`IntegrationSweeps`):
  - the pending-audit sweep, every `sangam.audit.pending.sweep-interval` (default 30 s; 0 means no sweep);
  - the idempotency cleanup, every `sangam.rest.idempotency-cleanup-minutes` (default 60), on the leader node only.

### Host difference: the relay wake-up on PostgreSQL (decision 316)

**What the wake-up is.** After a cycle that published something, the outbox relay sends a hint so that waiting subscribers (the worker long-poll, the task-list push) poll again at once. It is only a hint: every subscriber keeps its own poll, and the relay always polls the outbox at its own interval.

**What the Spring host does on PostgreSQL.** The relay sends `pg_notify`. Every node holds a `LISTEN` session on a dedicated physical connection, opened outside the pool with the HikariCP pool's driver settings. A cycle on any node wakes the subscribers on every node.

**What this host does.** The Agroal pool offers no physical connection outside the pool, so this host never opens a `LISTEN` session. `QuarkusHost.relayWakeup` always returns the polling mode (`RelayWakeup.Mode.POLLING`), on every database:

- A relay cycle on this node wakes this node's subscribers in-JVM.
- A relay cycle on another node is seen at the subscribers' next poll.
- On PostgreSQL the relay still sends `pg_notify`, so nodes that do listen (Spring nodes on the same database) are woken by this node.

**The intervals.** These are fixed in code on both hosts; **no configuration key sets them**:

- the relay polls the outbox every 1 s (`OutboxRelayService.Settings.defaults`);
- a waiting worker long-poll polls again after 100 ms, doubling up to 1 s (`WorkLongPoll.Settings.defaults`).

**The effect.** Only latency differs, never a result: at worst a subscriber on this host notices another node's delivery up to 1 s later. In 0.1.0 this host has no HTTP surface (decision 278), so it has no worker long-poll and no subscriber of its own yet.

**Why it is never silent.** The mode is chosen by name. `RelayWakeup.listen` refuses a host that cannot listen; it does not fall back. `RelayWakeup.polling` builds a listener that never opens a connection.

### Broker inbox (decision 317)

The Kafka and JMS broker inbox for this host comes in extraction phase 5 (still 0.1.0). Until then the host refuses to start when either switch is on:

- `sangam.integration.kafka.enabled=true`
- `sangam.integration.jms.enabled=true`

These are the Spring host's own keys. The refusal is an `IllegalStateException` whose message starts with `BROKER_INBOX_NOT_ON_QUARKUS` and names each switch that is on. `on`, `yes` and `1` are refused like `true`. A value that is not a boolean fails with the key named. `QuarkusHost` raises the refusal when it is constructed, which is before the engine is built.

## What stays available without it

Everything. The supported host is the Spring standalone server (`sangam-server`), and it never has Quarkus on its class path.

## How a missing control fails safe

The engine area (utc-jvm, database-checks, schema-before-engine, jackson-feel-compatibility, and job-leases while executor partitioning is on) is checked by the engine step itself on every start (extraction phase 1b). A failed check returns `CAPABILITY_MISSING` with the check's own exception kept as a suppressed exception.

Every other required capability outside the five seams returns `CAPABILITY_MISSING` until its extraction phase assembles it. All six are still missing today:

| Capability | Phase | State |
|---|---|---|
| audit-chain, idempotency-store, outbox-relay-inbox | 3 | Started, not finished. See "Integration services on this host". |
| tenant-security-plugin, authorization | 2 | Not started. |
| money-invariants | 5 | Not started. |

A broker inbox switched on is refused by name until phase 5 (decision 317, `BROKER_INBOX_NOT_ON_QUARKUS`).

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

Since extraction phase 3 started, `sangam-assembly` depends on `sangam-integration`, so these also reach this host's closure. They are the versions the default reactor resolves, and they were already in the local repository; none was downloaded:

- `json-schema-validator` 1.5.1 with `itu` 1.10.2, `jackson-dataformat-yaml` 2.22.3 and `snakeyaml` 2.6;
- `angus-mail` 2.0.4 with `angus-activation` 2.0.2, `jakarta.mail-api` 2.1.5 and `jakarta.activation-api` 2.1.4;
- `sshd-sftp`, `sshd-core` and `sshd-common` 2.15.0 with `jcl-over-slf4j` 2.0.20.

The Quarkus BOM would move the SSHD and Angus artefacts to other versions, so this module's pom pins them.

Since round 5, the module also depends on `sangam-rest-core` (the plain-Java `TxSupport` and the JDBC stores, decision 277). That adds `jackson-datatype-jsr310` 2.22.3. Its `operaton-engine` dependency would bring `spring-beans` and `spring-core` back, so they are excluded there as well.

All 13 artefacts above are in the D-5 licence record for this host (`sangam/docs/records/DOWNLOADS-WAVE4.md`, rows `r5-q1 D-5 licence`, 2026-11-15). Every one is ALLOWED. The Angus and Jakarta Mail jars use the BSD-3-Clause election of decision 117. Each was already cached with a matching SHA-1, so nothing was downloaded.

The closure has these adjustments:

- **Jackson** is held at 2.22.3, the licence policy minimum.
- **Spring** (`spring-beans`, `spring-core`) is excluded. The engine reaches Spring only through its XML (beans) configuration, which this host never calls. `EngineWithoutSpringTest` covers this.
- **jna** is held at 5.17.0 in the test closure (owner decision 275; the Quarkus dev-services test chain would bring 5.8.0).
- **No JDBC driver extension.** The deployer chooses the database. `quarkus-jdbc-h2` would bring h2 2.4.240 and jts-core 1.19.0, which the licence review holds.
- **`quarkus-maven-plugin` is not used.** The deployer's application build runs the Quarkus augmentation. `META-INF/beans.xml` makes this jar a bean archive.

All downloads, licences and checksums are recorded in `sangam/docs/records/DOWNLOADS-WAVE4.md` (W4-F/B66 D-5 lines).

## Tests

The tests in this table are plain JUnit and do not boot Quarkus. They were run on 2026-11-16 (H2, offline, JDK 25, round 6 lane q1, after decision 376): 65 tests, all pass.

- **Fixed:** `JtaTransactionsTest.engineAndProductRowsCommitOrRollBackTogether` failed with `Unknown data type: "BLOB"`. There were two causes:
  - The test created its product table before the engine, and H2 creates the `BLOB` domain only on an empty database (`DatabaseGuard.prepareH2`, its "Order" note). `JtaFixture` now prepares the database first.
  - The plain `JakartaTransactionProcessEngineConfiguration` then failed with Agroal's "Deferred enlistment not supported". Its bootstrap command uses a connection opened before the JTA transaction. The test engines now use `QuarkusProcessEngineConfiguration`, the class this host's engine really uses; it overrides the schema-operations executor for this case.
- **Not run:** the two conformance kit classes below (they boot Quarkus). On 2026-11-15 and again in round 6 (2026-11-16) they could not start offline: `io.quarkus:quarkus-bom-quarkus-platform-properties:properties:3.40.1.1` is not in the local repository, and downloads are not allowed.

| Test | What it checks |
|---|---|
| `QuarkusConfigSourceTest` | config source |
| `JtaTransactionsTest` | joining, owning and rolling back a transaction; engine and product rows in one JTA transaction on Narayana with Agroal and H2 |
| `QuarkusHostTest` | exactly five seams; every other required capability outside the engine area refuses with CAPABILITY_MISSING, both at the engine build and at start-up; engine checks not run count as missing at the engine build; job leases needed only with executor partitioning (decision 286, both cases); decision 376: an expected after-build capability is pending at the build guard and missing at start until the build claims it, and the job executor is the last runtime component |
| `QuarkusSeamsTest` | decision 279: without each seam bean its capability is missing and the start is refused (no standalone scheduling fallback); decision 278: HTTP and OIDC over HTTP never claimed |
| `EngineWithoutSpringTest` | no Spring on the class path; the engine builds, deploys and runs |
| `SangamEngineConfigurationTest` | the Quarkus engine configuration carries `SangamProcessEnginePlugin` first, the guard and after-build step, `EngineSettingsReapplyPlugin` last, the host's data source and transaction manager, and the system audit scope; the bean refuses before any database access, also without the product schema (decision 287 b) |
| `QuarkusEngineSettingsTest` | default and configured system audit scope; the product schema and executor partitioning switches and their defaults; unbound `sangam.engine.*` keys and `SANGAM_ENGINE_*` environment forms refused; test mode refused in production |
| `BrokerInboxRefusalTest` | decision 317: the host is refused by name while the Kafka or JMS broker inbox is switched on; off or absent, nothing is refused |
| `QuarkusRelayWakeupTest` | decision 316: the polling wake-up on every dialect, no `LISTEN` session; a notification that cannot be sent leaves no JTA transaction behind |
| `JtaTxSupportTest` | phase 3 blocker 3: the `TxSupport` contract on Narayana and Agroal: joining and owning, the always-new transaction independent of the suspended one in both directions, one bound connection per transaction released at completion, rollback-only, after-commit, the engine in the same transaction |
| `AttributionTenantTest`, `OutboxDestinationsTest`, `LifecycleAuthorizationTest` (sangam-assembly) | phase 3 blockers 1, 2 and 4: the one attribution-tenant rule; the outbox destination rule with host routes and `OUTBOX_DESTINATION_NOT_DELIVERED`; `SECURITY_MODULE_NOT_BOUND` (decision 374) |
| `EngineStartOrderTest` | phase 3 blocker 5, decision 376, on a real engine: built with the job executor off although configured on; a due job does not run while the start is refused; the job executor starts last only after a passing check and stops with the runtime; the engine build reports itself to the after-build claims; the plugin sits right before the settings re-apply |
| `EngineBuildClaimsTest` (sangam-assembly) | decision 376: only `outbox-relay-inbox` is claimed after the build; pending before the build, missing at start until the build claims it; a refused start starts nothing; after-build components start with the fixed ones in phase order, the job executor last; a failed assembly is named in the refusal |
| `RelayWakeupTest` (sangam-assembly) | decision 316: polling never opens a connection, wakes its own JVM and still sends `pg_notify`; `LISTEN` is never chosen or left silently |
| `IntegrationLifecyclesTest` (sangam-assembly) | phase 3, decision 315: the integration services' start and stop order; nothing assembled without the scheduling seam |
| `IntegrationSweepsTest` (sangam-assembly) | decision 315: the pending-audit sweep and the idempotency cleanup run on the host scheduler with the Spring host's keys and defaults |
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

- **Equivalence scenarios:** do not pass. Every start is refused with `CAPABILITY_MISSING`, and the host offers no ports. The audit and idempotency ports come when phase 3 is finished (see "Integration services on this host"); the others with phases 2 and 5.
- **Coverage:** expected to pass. All 16 required capabilities are removable and there are no gaps.
- **Fail-closed kit:** expected to pass for 14. database-checks and job-leases are checked after the host gaps (the database check after the database-free refusal, the job leases at engine build), so their markers appear once phases 3, 2 and 5 leave no host gap.

`jna` is pinned to 5.17.0 (owner decision 275), so the dev-services test chain no longer needs the flagged 5.8.0.

The test `application.properties` uses H2 as `db-kind=other` with an explicit driver, in `MODE=PostgreSQL` (the only H2 mode the database guard accepts). This is unverified on Quarkus 3.40.1.1 (NOT_RUN).
