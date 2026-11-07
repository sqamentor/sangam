# sangam-engine (WS-ENGINE)

Operaton 2.2.0 used as a Maven dependency and extended only through plugins and SPIs (ADR-D-16). It provides the
`OperatonEngineAdapter` (implements `org.sangam.core.api.EngineAdapter` and `org.sangam.core.engine.SangamEngineAdapter`),
the `SangamProcessEnginePlugin`, and a Spring-free factory/configurer. Every engine behaviour change is in
[PATCHES.md](PATCHES.md) (0 engine source patches, 2 MySQL schema patches).

Build: `mvnw.cmd -q -B -DskipTests -pl sangam-engine,sangam-persistence -am install`. Fast tests (H2 in memory, no
Docker): `SangamEngineH2SmokeTest` (schema, plugin, L2, fencing, T35 id, long strings, JSON variables, DOCTYPE
rejection, user task) and `CycleExpressionTest` (DST fold, anchored months, calendar tag). Testcontainers suites
are deferred to the testing phase.

## Using it

```java
SangamEngineSettings s = new SangamEngineSettings();       // @ConfigurationProperties("sangam.engine") in the server
s.setEventSink(outboxEventSink);                             // integration: typed engine events -> OUTBOX
OperatonEngineAdapter engine = SangamEngineFactory.build(dataSource, s, jdbcUrl);
```
With an application-owned configuration (for example in Spring):
`SangamEngineConfigurer c = new SangamEngineConfigurer(s); ds = c.prepare(raw); c.configure(cfg, ds);
engine = cfg.buildProcessEngine(); adapter = c.afterBuild(engine, ds, url, null);`

## Requirements covered, file by file

Paths are under `src/main/java/org/sangam/engine/` unless stated. Engine-neutral ports added to sangam-core are listed at
the end.

| File | Requirements |
|---|---|
| `OperatonEngineAdapter.java` | EngineAdapter + SangamEngineAdapter; **A-11/R3/VI.1** L2 first on every state change (claim, complete, correlate, external complete/fail/bpmnError, cancel, pause/resume), whole-command retry with full jitter → `ConflictRetryExhaustedException` (409); **VI.3/R9/R20/D-17/D-18/D-20** fencing on every worker call, heartbeat with `cancelRequested`, `requestCancel`, max lease 24 h; **XI.10.4/D-19/W-9** concurrent fetch (per-topic fetch lock, OLE→THROW listener, retried attempts, never a stuck task); **A-10/D-21/W-10** expiry hook in every fetch path, CANCEL_REQUESTED expiry → hold; **T35/R36/A-2** operationId per leased item; **A-14** `OPERATION_LEDGER.engine_work_id`; **R35/VI.6/A-7** `sangam:exclusive` execution/resource modes with `resourceFence`; **VI.5/R23** tombstones + `INSTANCE_DELETED` with open operation ids on delete (W-11); **A-12** bounded `executeDueJobs` (per call and per job definition); **A-16** `countTasksCapped`; **D-35** `correlateMessageToBranch`; **D-32** pause/resume (re-date suspended, then activate); **D-36/W-4** OR task query; **D-51/R24** DOCTYPE/ENTITY rejected; Q1 `inEngineTransaction` |
| `SangamProcessEnginePlugin.java` | ADR-D-16 registration of all hooks below; engine defaults: VI.4 (engine authorization off), D-47 (Java serialization off), D-51 (XXE off), D-56 (isolation check on), D-13 (TTL not enforced), FC-24 config route (SKIP LOCKED, timers first, priorities), UUID ids, D-06 JDBC statement timeout, FC-26 level |
| `SangamEngineConfigurer.java` | Spring-free configurer; D-05 pinned `databaseType`; D-56/R84 isolation guard; R12 product schema before build; P-1/P-2 after build; one engine per JVM (D-10) |
| `SangamEngineFactory.java` | Plain engine + DataSource factory (usable from a Spring `@Bean`, no Spring Boot dependency) |
| `SangamEngineSettings.java` | D-53 typed configuration facade with every Sangam default |
| `SangamEngineComponents.java` | Shared parts per engine and dialect; default ports |
| `clock/OperatonClock.java` | **D-10/W-7** SangamClock: the only `ClockUtil` user; one engine per JVM guard |
| `db/EngineDialect.java` | **A-15/VI.1.6** per-dialect lock statements, insert-if-absent (MySQL no-op upsert), lock timeouts (VI.1 rule 6), TEXT_ limit (D-07: Oracle 2,000) |
| `db/Conflicts.java` | VI.1.6 retryable error classification (PG/Oracle/SQL Server/MySQL/H2) |
| `db/IsolationGuardDataSource.java` | **D-56/R84** READ COMMITTED on every connection (MySQL REPEATABLE READ) |
| `db/DatabaseGuard.java` | Start-up checks: isolation, **SQL Server RCSI** (VI.1.6), **D-05** MySQL vs MariaDB server identity, H2 `BLOB` alias for MODE=PostgreSQL |
| `tx/CommandState.java` | **Q1/D-22/W-2** explicit rollback guard; held roots; LockScope mark (VI.1 rule 5); D-25 case-task outcomes |
| `tx/RollbackGuardInterceptor.java` | Q1 guard + L2 backstop on **every** command (jobs, Operaton REST, delegates) |
| `tx/CommandRetrier.java` | VI.1 rule 7 retries: 5 attempts, base 20 ms, cap 1 s, full jitter |
| `lock/RootLock.java` | **A-11/D-24** L2 on `ROOT_SEQ` (shared with core RootSeq/AuditChain/Outbox), ascending order, `root_lock_wait_seconds` metric |
| `lock/RootLockFlushListener.java` | A-11 backstop: roots of changed executions/tasks locked just before flush |
| `lease/SangamLeaseTable.java` | **VI.3/R9/R20/R21/XI.10.4** `SANGAM_LEASE`: idempotent CAS grant per (work_id, lock_version), check, extend, end, cancel request, expired sweep in key order |
| `lease/SangamLeaseTable.java` (`lockExpired`) | **R2** product-side acquisition: expired-lease claim per dialect (PG/MySQL `FOR UPDATE SKIP LOCKED` on the expiry index; SQL Server `TOP (n) WITH (UPDLOCK, READPAST, ROWLOCK)` index seek; Oracle two-step candidate read + per-row `FOR UPDATE SKIP LOCKED`; H2 two-step blocking), in its own short command |
| `tx/CommandState.java` + `tx/RollbackGuardInterceptor.java` + `lock/RootLockFlushListener.java` (acquisition mark) | **R2 / VI.1 rule 4**: job acquisition (`AcquireJobsCmd`), external-task fetch (`FetchExternalTasksCmd`) and the adapter's sweep/fetch commands are marked as acquisitions and never take L2, not even the flush backstop |
| `feel/FeelEngineIdentity.java`, `feel/FeelBindingStore.java`, `feel/FeelBindingDeployer.java`, `feel/FeelPinningListener.java` | **R10** one FEEL engine pinned per model version: identity (factory / feel-engine version / strict-or-legacy) recorded in `SANGAM_FEEL_BINDING` at deploy for every decision, process and case definition; every decision evaluation (incl. required decisions) refuses a mismatch; pre-existing definitions are pinned on first evaluation |
| `feel/StrictNullResultGuard.java` (+ `SangamBpmnParseListener.parseBusinessRuleTask`) | **R10** strict null only where required: `sangam:routing=true` → the mapped result must be a non-null Boolean; `sangam:requiredOutputs=a,b` → mandatory outputs non-null; all other nulls stay valid; gateway conditions are already strict in Operaton (`UelExpressionCondition`) |
| `lease/ExclusiveLeaseTable.java` | **R35/VI.6/A-7** `EXCLUSIVE_LEASE` acquire/extend/release |
| `lease/CapabilityClassExpiryPolicy.java` | **A-10/A-8/VI.3 rule 5** default policy: ledger class (c) → UNKNOWN_OUTCOME + reconciliation item + hold; undeclared = (c) |
| `timer/CycleExpression.java` | **A-12/R31/D-01/D-02/D-29/D-31** UTC elapsed cadence, anchored calendar cadence, `Rn` semantics, lastFiredInterval |
| `timer/DstSafeCycleBusinessCalendar.java` | **A-12/D-01/D-30** next due strictly after max(now, due); FIRE_ALL catch-up bounded; Rn end |
| `timer/TimerGuard.java` | **A-12/R31/D-30** `SANGAM_TIMER_GUARD`: suppress duplicate interval, SKIP_MISSED, fire count |
| `timer/TimerPolicies.java` | R31 catch-up policy per timer (`sangam:catchUp`), per process key, default |
| `timer/TaggedDurationBusinessCalendar.java` | **FC-23/D-11/R79** `cal:<name>:<duration>` via `WorkCalendar` |
| `job/GuardedJobHandler.java` | Jobs: **tenant system identity bound** (VI.4 rule 7), lock timeouts, **L2 first** (VI.1 rule 4), DST guard (A-12) |
| `job/JobIdentitySupport.java` | VI.4 rule 7: tenant from job tenant id, else `sangamTenantId` variable, else default tenant; `SecurityIdentity.system(tenant, "job:<id>")` bound engine-locally and through the installed `JobIdentityBinder` |
| `job/JobMetricsInterceptor.java` | D-39/R13 `sangam_jobs_acquired_total` from the job executor's `AcquireJobsCmd` |
| `job/CountingFailedJobCommandFactory.java` | D-39/R13 `sangam_jobs_failed_total` (every failed job execution, label = failure class) |
| `variables/LargeStringValueSerializer.java` | **D-07/FC-29/GP-38** strings above 4,000 (Oracle 2,000) chars as byte arrays, automatic |
| `variables/JsonValueSerializer.java` | **FC-28/D-47/R11/R33** JSON Map/List variables, depth/size limits, BigDecimal |
| `deploy/SangamBpmnParseListener.java` | **FC-46/R44**: **GP-09/D-23** MI limit (`multiInstanceMaxCardinality`, default 50 = WS-PACKS `ModelGuard.MAX_MULTI_INSTANCE`; change both together), **D-46/R6/D-8** no script tasks, **D-12** bindings, **D-13/R29/R61** TTL by retention class, **R31** `sangam:catchUp` |
| `deploy/MultiInstanceSizeGuard.java` | GP-09 runtime guard |
| `deploy/SangamCmmnTransformListener.java` | **D-08** reject CMMN event listeners, **D-26** cancel called process on exit, D-25 outcome capture |
| `history/SangamHistoryEventHandler.java` | **R42/X.2.3/EC-09** history → audit chain; **FC-43/FC-25/D-41** typed events → EngineEventSink (outbox); **D-25/XI.10.4** CMMN exited human task projected COMPLETED / EXITED / TERMINATED |
| `history/CoreAuditChainSink.java` | Default AuditSink on core `AuditChain` |
| `history/OutboxOnlyDbHistoryEventHandler.java` | **IR-5** outbox-only history per definition: replaces Operaton's DB history handler; `ACT_HI_*` keep only the audit level for listed keys (no variable detail, incidents, job/external-task logs, identity links, decisions, op logs); full detail only via outbox → read model |
| `history/PerDefinitionHistoryLevel.java` | **FC-26/D-49** history level per process definition key and per activity (`key#activityId`) |
| `incident/SangamIncidentHandler.java` | Incidents → reconciliation (failedJob, failedExternalTask, LEASE_EXPIRED_HELD) |
| `incident/JdbcIncidentSink.java` | Default IncidentSink: `SANGAM_ENGINE_INCIDENT` with operationId join (A-14) |
| `packs/JdbcLedgerStore.java` | WS-PACKS `port.LedgerStore` (GP-11, GP-48): postings idempotent by reference, reversal by reference, no-negative check, account row lock, NUMERIC amounts (R33) |
| `packs/JdbcBatchLedger.java` | WS-PACKS `port.BatchLedger` (GP-09, GP-49): idempotent open, first outcome wins per (batch, round, item) |
| `packs/ProductJdbc.java` | Port SQL joins the active engine command's connection/transaction (Q1), else its own transaction |
| `pom.xml` | Operaton from `operaton-bom`; LIC-1 GraalJS excluded; **D-38** Spring jars excluded; Jackson from the 2.22.3 BOM (D-37) |

Ports added to `sangam-core/src/main/java/org/sangam/core/engine/` (engine-neutral, no Operaton type):
`SangamEngineAdapter` (LeasedWork with operationId/attemptNo/businessKey/tenant/resourceFence, Heartbeat), `SangamClock`
(D-10), `OperationIdentity` (T35), `ConflictRetryExhaustedException` (VI.1 rule 7), `LockScope` +
`IllegalRemoteCallInLockScope` (VI.1 rule 5), `OperationAwareExpiryPolicy` (A-10), `AuditSink`, `IncidentSink`,
`EngineEventSink` (FC-43), `EngineMetrics` (D-39), `TimerCatchUpPolicy` (R31), `WorkCalendar` (FC-23).

## FLOWABLE-INTO-OPERATON coverage (list D and route-1/4 list C)

| Item | Status here |
|---|---|
| D-01, D-02 DST runaway, cached JVM zone | Done by extension point (B-4/B-5), not by patching `DurationHelper` |
| D-03 / FC-30 MySQL ms precision | P-1 |
| D-04 utf8mb3 | P-2 |
| D-05 MariaDB driver dialect switch | `DatabaseGuard` + pinned `databaseType` |
| D-06 business-key uniqueness, statement timeout | `UNIQUE_START` (persistence, WS-INTEGRATION shape) + `jdbcStatementTimeout` |
| D-07 / FC-29 text limit (2,000 on Oracle) | `LargeStringValueSerializer` |
| D-08 CMMN event listener never fires | rejected at deploy |
| D-10 global ClockUtil | contained: `OperatonClock`, one engine per JVM (per-engine clock SPI = Stage D patch) |
| D-11 / FC-23 calendar per timer | `TaggedDurationBusinessCalendar` |
| D-12 latest binding | call activities default to deployment; business rule tasks must declare a binding |
| D-13 TTL enforcement vs R61 | TTL per retention class; default never cleaned |
| D-17, D-18, D-20, D-21 fencing, unlock owner, lease states, expiry before re-offer | `SangamLeaseTable`, adapter, policy |
| D-19 concurrent fetch | interim W-9 route (fetch lock + OLE listener); the `ExternalTask.xml` SKIP LOCKED patch is not done (engine source patch, Stage D) |
| D-22 Q1 rollback | B-1 on every command |
| D-23 large MI | deploy + runtime guard (engine profiling = Stage D) |
| D-24 hot root | L2 |
| D-25 CMMN "terminated" | audit projection |
| D-26 process task exit | CMMN listener |
| D-28 local variables on catch execution | adapter rule: correlate with `correlateMessageToBranch` and write to the branch scope |
| D-29, D-30, D-31 Rn, catch-up, P1M drift | `CycleExpression`, `DstSafeCycleBusinessCalendar`, `TimerGuard` |
| D-32 pause/resume deadlines | `pauseInstance` / `resumeInstance` |
| D-35 branch correlation | `correlateMessageToBranch` |
| D-36 task query AND | OR query |
| D-37 Jackson | parent BOM 2.22.3 (unchanged here) |
| D-38 Spring compile dependency | excluded in `pom.xml` (verify at runtime in the testing phase) |
| D-39 metrics | `EngineMetrics` port (server binds Micrometer) |
| D-41 / FC-25 sync history | typed events to `EngineEventSink` (outbox) |
| D-46 in-engine scripts | script tasks rejected |
| D-47 Java serialization | off + JSON serializer |
| D-49 / FC-26 per-definition history | `PerDefinitionHistoryLevel` |
| D-51 XXE | `enableXxeProcessing=false` + DOCTYPE rejected |
| D-53 configuration legacy | `SangamEngineSettings` facade |
| D-54 per-DB SQL scripts | Liquibase (persistence) |
| D-55 auth count | persistence indexes + `countTasksCapped` |
| D-56 REPEATABLE READ | isolation guard + start-up check |
| FC-24 job executor | config route done (SKIP LOCKED, timers first, priorities); hand-off / separate timer acquisition patch = Stage D |
| FC-28 JSON variables | own serializer (no Spin dependency) |
| FC-43 typed events | `EngineEventSink` |
| FC-46 validator | parse/transform listeners |
| **Not here** | D-09/FC-01 ad-hoc, FC-02..04 event registry, FC-20/31/32 read model and entity links, FC-55..59 CMMN, forms, content, app bundles, batch: **WS-PORTS**. D-14, D-15: WS-SECURITY. D-33, D-40: WS-INTEGRATION / WS-REST. D-42..45: WS-UI / WS-SDK. D-16, D-50: organisational. D-27, D-48, D-52: Stage D/E engine patches. FC-12, FC-13, FC-21, FC-22, FC-54 (BPMN/DMN behaviour extensions, Flowable strengths) are left to WS-PORTS per ARCHITECTURE §3 — **please confirm** |

## Integration notes (for the integrator / sangam-server)

1. **Wiring.** Build the engine with `SangamEngineFactory.build(ds, settings, url)` or the configurer; expose the
   adapter as both `EngineAdapter` and `SangamEngineAdapter`; `destroyMethod = "close"`. The DataSource must be the
   server's engine pool; the factory wraps it in `IsolationGuardDataSource`.
2. **Schema.** `settings.applyProductSchema=true` runs `db/changelog/sangam-master.xml` before the engine build. If the
   server runs Liquibase itself (Spring Boot, `sangam-master.yaml`), set it to `false`; keep `applyEnginePatches=true`
   (P-1/P-2 must run after the engine created its tables).
3. **H2 (dev profile)** needs `MODE=PostgreSQL` in the URL (core product layer); the configurer adds the `BLOB` alias.
4. **Ports to bind:** `EngineEventSink` → integration outbox writer (else typed events are dropped); `EngineMetrics` →
   Micrometer; optionally `AuditSink`, `IncidentSink`, `ExpiryPolicy` (defaults: core `AuditChain`,
   `SANGAM_ENGINE_INCIDENT`, `CapabilityClassExpiryPolicy`); `workCalendars` from packs/tenant config.
5. **Job executor.** `settings.jobExecutorActivate=true` on server nodes; job execution is guarded (L2, DST guard).
6. **Operaton REST** commands get the Q1 guard and the L2 flush backstop, but no retry and no fencing: keep the
   external-task endpoints of `/engine-rest` closed (D-18); workers use `/api/v1/work`.
7. **Requested core changes** (sangam-core is integrator-owned): add `operationId` (and attemptNo/businessKey) to
   `EngineAdapter.ExternalWork` (today in `LeasedWork`); an `OperationLedger.recordIntent` overload taking an explicit
   operationId, so the ledger uses the T35 id the adapter hands out (the policy also finds ledger rows by
   `engine_work_id`); `Dialect` has no H2 constant (the engine maps H2 → POSTGRESQL); `AuthorizationPolicy` inserts
   into `AUTH_EFFECTIVE` without `cond_key` (now NOT NULL in the adopted security shape).
8. **Exclusivity**: `sangam:exclusive` extension property: `execution[:group]` or `resource:<variable>` (the FEEL key
   is computed into that variable by an input mapping).
9. **Capability class**: extension property `sangam:capabilityClass` (`a`/`b`/`c`) or `sangam:effect=irreversible`;
   undeclared = class (c) (A-8).
10. **`inEngineTransaction` reuses an active command context** (sangam-integration relies on it): inside a delegate,
    listener, job or an outer `inEngineTransaction`, the function runs in that context, on its connection and transaction;
    only without an active context does it open one new command. The other adapter methods are retried only at top level.
11. **Plugin order (WS-PORTS).** `SangamProcessEnginePlugin` is always registered first;
    `SangamEngineFactory.build(ds, settings, url, List.of(portsPlugin, ...))` or
    `SangamEngineConfigurer.configure(cfg, ds, extraPlugins)` appends the others in the given order (also re-ordering any
    plugin already on `cfg` behind Sangam's).
12. **FC-55 switch.** `settings.cmmnEventListenersEnabled=true` (when WS-PORTS' CMMN event listeners are on) turns the
    D-08 rejection off; `rejectCmmnEventListeners` stays the default guard otherwise.
13. **Clock for other modules.** `org.sangam.core.engine.SangamClockHolder.now()` / `.get()` (sangam-core) returns the
    engine clock once the configurer has run (JVM clock before; reset on close). Prefer injecting `SangamClock`
    (`adapter.clock()`, exposed as a bean by the server); nothing outside `clock/OperatonClock` may read `ClockUtil`.
    **Per-engine clock (D-10): not feasible without an engine source patch.** Operaton reads the static
    `ClockUtil.getCurrentTime()` directly in its own code (job acquisition and lock expiry, `TimerEntity`,
    `DurationHelper`/`CronTimer`, external-task locks, history timestamps), so no plugin can give two engines in one
    JVM different clocks. The patch (a `Clock` SPI on `ProcessEngineConfigurationImpl` replacing `ClockUtil` reads)
    is the Stage D item with an upstream PR; until then: one engine per JVM, enforced by `OperatonClock.register`.
14. **History level per activity (FC-26).** `historyLevelByProcessKey` accepts `processKey#activityId` entries as well
    as `processKey`; the most specific entry wins (`PerDefinitionHistoryLevel`).
15. **WS-PACKS ports.** Install `new JdbcLedgerStore(ds, adapter.clock(), lockTimeout)` and
    `new JdbcBatchLedger(ds, adapter.clock())` through `PackRuntime.install(...)` (replacing the in-memory simulators).
    Called from a pack delegate they write in the engine command's transaction; elsewhere in their own.
16. **Job identity (VI.4 rule 7).** Install `settings.setJobIdentityBinder(TenantContext::runAs)` (sangam-security):
    every job, timer and async continuation then runs with `SecurityIdentity.system(tenant, "job:<id>")` bound. The
    engine does not depend on sangam-security (Spring Security); without a binder it binds only
    `JobIdentityBinder.current()` and logs a warning at start-up.
17. **Job metrics.** Bind `EngineMetrics.jobsAcquired(n)` → counter `sangam_jobs_acquired_total` and
    `EngineMetrics.jobFailed(cause)` → `sangam_jobs_failed_total` (tag `cause`). Acquisition is counted for the job
    executor and for `executeDueJobs`; failures for both paths.
18. **IR-1 cell ids (SCALE).** Install `CellIdGeneratorPlugin` with the ordered extra-plugin hook:
    `SangamEngineFactory.build(ds, settings, url, List.of(new CellIdGeneratorPlugin(CellId.of(cellId)), portsPlugin))`
    or `SangamEngineConfigurer.configure(cfg, ds, List.of(...))`. `SangamProcessEnginePlugin` always runs first (it sets
    `StrongUuidGenerator` in preInit); later plugins' preInit run after it, so the cell id generator wins. Only the
    engine's id generator changes; Sangam product ids are unaffected.
19. **IR-5 outbox-only history.** `settings.outboxOnlyHistoryKeys = {processOrCaseKey, ...}`. For those definitions
    Operaton writes only audit-level history (instances, activity instances, tasks, variable instances); the rest goes
    only through `EngineEventSink` → outbox → `history-projector`. An `EngineEventSink` must be bound (warning
    otherwise); `publishedHistoryEvents` must not exclude the detail event types. Engine APIs that read
    `ACT_HI_DETAIL`, incidents history, job logs or op logs return nothing for those definitions (use the read model).
20. **IR-7 job executor.** `SangamEngineSettings` job fields (SCALE 3 defaults): core 16, max 32, queue 64,
    `maxJobsPerAcquisition` 20, wait 500 ms with increase factor 2 up to `maxWait` 5 s, lock time 5 min, backoff 0,
    acquire by due date and by priority on, exclusive over process hierarchies off, optional lock owner. Environment
    overrides (applied in `prepare` when `applyEnvironment=true`): `SANGAM_JOB_CORE_POOL_SIZE`, `SANGAM_JOB_MAX_POOL_SIZE`,
    `SANGAM_JOB_QUEUE_CAPACITY`, `SANGAM_JOB_MAX_JOBS_PER_ACQUISITION`, `SANGAM_JOB_WAIT_TIME_IN_MILLIS`,
    `SANGAM_JOB_WAIT_INCREASE_FACTOR`, `SANGAM_JOB_MAX_WAIT`, `SANGAM_JOB_LOCK_TIME_IN_MILLIS`,
    `SANGAM_JOB_BACKOFF_TIME_IN_MILLIS`, `SANGAM_JOB_MAX_BACKOFF`, `SANGAM_JOB_ACQUIRE_BY_DUE_DATE`,
    `SANGAM_JOB_ACQUIRE_BY_PRIORITY`, `SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES`, `SANGAM_JOB_LOCK_OWNER`,
    `SANGAM_JOB_EXECUTOR_ACTIVATE`. Separate executor pods: `SANGAM_JOB_EXECUTOR_ACTIVATE=true` there, `false` on API
    pods. A job executor already set on the configuration (Spring) is kept; only the acquisition flags are applied.
21. **IR-8 history partitioning.** `settings.partitionedHistory=true` (env `SANGAM_PARTITIONED_HISTORY=true`) runs
    `SangamSchema.applyHistoryPartitioning` after the engine build on PostgreSQL, Oracle and SQL Server (warning only on
    MySQL/H2). Limits per dialect: sangam-persistence README "History partitioning".
22. **R2 acquisition SQL that Sangam cannot change without an engine patch** (Operaton 2.2.0 MyBatis mappings):
    - `Job.xml selectNextJobsToExecute` (job executor): with `jobExecutorAcquireWithSkipLocked=true` (set by Sangam)
      PostgreSQL/H2/MySQL use `FOR UPDATE SKIP LOCKED`, SQL Server `WITH (UPDLOCK, READPAST)`, DB2 `SKIP LOCKED DATA`;
      **Oracle has no row-limited SKIP LOCKED branch** (no two-step), so concurrent acquirers on Oracle rely on the
      lock update's optimistic `REV_` check; then `AcquireJobsCmd` locks each job by `UPDATE … WHERE ID_ = ? AND REV_ = ?`.
    - `ExternalTask.xml selectExternalTasksForTopics` (external-task fetch): **no `FOR UPDATE` / `SKIP LOCKED` on any
      dialect**; concurrent fetches compete through optimistic locking on `REV_`. Sangam's mitigation is the per-topic
      `SANGAM_FETCH_LOCK` row lock (a deliberately **blocking**, bounded wait: it serializes fetches of one topic
      with the engine's query; starvation is bounded by `lockTimeout`) plus the OLE→THROW listener (W-9, D-19).
    - Both acquisitions run in their own command and never take L2 (acquisition mark above). A real SKIP LOCKED /
      Oracle two-step in these mappings is the engine patch D-19 (Stage D, upstream PR).
23. **R10 models.** Mark routing decisions with `operaton:properties` `sangam:routing=true` and mandatory outputs with
    `sangam:requiredOutputs=out1,out2` on the business rule task (an `operaton:resultVariable` is then required).
    After a FEEL engine upgrade, decisions of models deployed under the old engine fail with an R10 message until the
    model is redeployed (re-validated); the start-up log lists them.
24. **LockScope**: connectors (WS-INTEGRATION) call `LockScope.assertNoLockHeld("http …")` before remote calls.

## Third-party dependencies

| Dependency | Licence |
|---|---|
| org.operaton.bpm:operaton-engine, operaton-engine-dmn, operaton-engine-feel-scala 2.2.0 (BOM) | Apache-2.0 (transitives as in the spike ledger LIC-1: MIT/BSD/Apache/EPL) |
| com.fasterxml.jackson.core:jackson-databind 2.22.3 | Apache-2.0 |
| org.sangam:sangam-packs (internal; for the LedgerStore/BatchLedger ports; packs depends only on core + Operaton, no cycle) | Apache-2.0 |
| org.slf4j:slf4j-api 2.0.17 | MIT |
| test: H2 2.3.232, JUnit 5.11.4 | EPL-1.0/MPL-2.0, EPL-2.0 |

## Not done, and why

- Engine source patches (D-19 SKIP LOCKED mapping, D-10 per-engine clock, FC-24 job hand-off, D-27, D-52): Stage D by
  the plan; the interim extension-point routes are in place.
- OFFERED/ack state (VI.3.1 stream offers): REST-first (ADR-D-5) — a fetch returns LOCKED work; OFFERED belongs to the
  worker API (WS-REST).
- Partition epochs (R39): blocked by A-6.
- Multi-DB verification: only H2 was executed (fast tests); PostgreSQL/Oracle/SQL Server/MySQL runs are in the testing
  phase.
