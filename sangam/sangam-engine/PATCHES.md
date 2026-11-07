# sangam-engine: engine behaviour changes (patch ledger)

Base: `org.operaton.bpm:operaton-engine:2.2.0` from Maven Central, used **unchanged as a dependency** (ADR-D-16).
This ledger records every place where Sangam changes engine behaviour, so each upgrade can re-check it.

## Summary

| Kind | Count |
|---|---|
| Engine source or bytecode patches | **0** |
| Engine schema patches (DDL on `ACT_*` after the engine created them) | **2**: P-1, P-2 (MySQL only) |
| Behaviour changed through public extension points | B-1 … B-14 |
| Internal (`impl`) classes used | I-1 … I-12 |
| Reflection | 0 |

## Schema patches

| ID | What | Where | Why | Re-check on upgrade |
|---|---|---|---|---|
| **P-1** | MySQL: every `ACT_*` `datetime`/`timestamp` column with precision 0 → precision 3 (66 columns in 2.2.0) | `sangam-persistence/.../mysql/MysqlDatetimePrecisionPatch.java`, changeset `P-1-mysql-datetime-precision` in `db/changelog/sangam-engine-patches.xml` (runAlways), run by `SangamEngineConfigurer.afterBuild` | D-03 / FC-30: a lease was re-offered 200 ms early and a timer fired 300 ms early (spike ledger, MySQL (2)) | yes: upgrade scripts may add second-precision columns; the patch is idempotent |
| **P-3** (optional) | `ACT_HI_*` (9 high-volume tables) partitioned by `REMOVAL_TIME_` on PostgreSQL / Oracle / SQL Server; PostgreSQL drops the `ID_` PK in favour of a plain index | `sangam-persistence/.../sangam-history-partitioning.xml` (context `partitioned-history`), run by `SangamSchema.applyHistoryPartitioning` / setting `partitionedHistory` | IR-8, R29: cleanup and retention at volume | yes: engine upgrade scripts that add unique indexes to these tables fail on PostgreSQL |
| **P-2** | MySQL: every `ACT_*` table in utf8mb3 → `utf8mb4 / utf8mb4_bin`; a table whose widest index would exceed 3,072 bytes is skipped and logged | `.../mysql/MysqlUtf8mb4Patch.java`, changeset `P-2-mysql-utf8mb4` | D-04: 4-byte characters cannot be stored in engine string columns | yes |

Upstream PRs to offer: MySQL create/upgrade scripts with `datetime(3)` and `utf8mb4` (as the MariaDB scripts already do).

## Behaviour changed through extension points

| ID | Change | Mechanism (file) | Requirement |
|---|---|---|---|
| B-1 | The connection of every command is rolled back explicitly on `ROLLINGBACK` (MyBatis skips the rollback when no MyBatis write ran; `setAutoCommit(true)` would then commit product rows) | post command interceptor `tx/RollbackGuardInterceptor`, `tx/CommandState` | Q1, D-22 (W-2) |
| B-2 | L2 root lock on `ROOT_SEQ` before state changes: adapter calls (first), job execution (handler wrapper), every other command (flush backstop, just before flush) | `lock/RootLock`, `lock/RootLockFlushListener`, `job/GuardedJobHandler` | A-11, D-24, VI.1 |
| B-3 | Lock waits bounded per command (PG `SET LOCAL lock_timeout/statement_timeout`, SQL Server/H2 `SET LOCK_TIMEOUT`, MySQL `innodb_lock_wait_timeout`, Oracle `FOR UPDATE WAIT n`) | `db/EngineDialect.applyLockTimeouts` | VI.1 rule 6 |
| B-4 | The `cycle` business calendar is replaced: UTC elapsed cadence, anchored calendar cadence, `Rn` = n firings, catch-up policy | `timer/DstSafeCycleBusinessCalendar`, `timer/CycleExpression` | A-12, R31, D-01, D-02, D-29, D-30, D-31 |
| B-5 | Cycle timer firings whose interval already fired (or missed ones under `SKIP_MISSED`) are suppressed; the follow-up timer is still scheduled | `job/GuardedJobHandler`, `timer/TimerGuard` | A-12 |
| B-6 | The `duration` calendar accepts a calendar tag `cal:<name>:<duration>` | `timer/TaggedDurationBusinessCalendar` | FC-23, D-11 |
| B-7 | Strings above the DB's `TEXT_` limit (Oracle 2,000, others 4,000) are stored as byte arrays (type name `sangam-large-string`) | `variables/LargeStringValueSerializer` (custom pre serializer) | D-07, FC-29 |
| B-8 | Map/List variables are stored as JSON (`sangam-json`, `application/json`); Java serialization stays disabled | `variables/JsonValueSerializer` | FC-28, D-47 |
| B-9 | Deployment rejects: literal MI cardinality above the limit, script tasks, business rule tasks without `decisionRefBinding`, CMMN event listeners, DOCTYPE/ENTITY; call activities default to `calledElementBinding=deployment`; TTL injected per retention class | `deploy/SangamBpmnParseListener`, `deploy/SangamCmmnTransformListener`, `OperatonEngineAdapter.deploy` | GP-09, D-23, D-46, D-12, D-08, D-51, D-13 |
| B-10 | Runtime MI guard: `nrOfInstances` above the limit fails the command | `deploy/MultiInstanceSizeGuard` (built-in execution listener) | GP-09 |
| B-11 | Exiting/terminating a CMMN process task deletes the called process instance | `deploy/SangamCmmnTransformListener` | D-26 |
| B-12 | Extra history handler (audit chain, typed events, CMMN outcome projection); the engine's own history rows are unchanged | `history/SangamHistoryEventHandler` | R42, FC-43, FC-25, D-25 |
| B-13 | Incident handlers for `failedJob` / `failedExternalTask` extend the default handler and also feed `SANGAM_ENGINE_INCIDENT` | `incident/SangamIncidentHandler` | R21, VI.3 |
| B-14 | Engine defaults: authorization off, Java serialization off, XXE off, isolation check on, history TTL not enforced, SKIP LOCKED job acquisition, timers preferred, prioritized jobs, `StrongUuidGenerator`, JDBC statement timeout, pinned `databaseType` | `SangamProcessEnginePlugin.preInit`, `SangamEngineConfigurer` | VI.4, D-47, D-51, D-56, D-13, FC-24, D-05, D-06 |
| B-15 | Job execution runs as the tenant's system identity (bound around the handler) | `job/GuardedJobHandler`, `job/JobIdentitySupport` | VI.4 rule 7 |
| B-16 | Job metrics: acquisition counted after `AcquireJobsCmd`; the failed-job command factory is wrapped (delegates unchanged) | `job/JobMetricsInterceptor`, `job/CountingFailedJobCommandFactory` | D-39, R13 |
| B-17 | (opt., IR-5) Operaton's default DB history handler replaced by `OutboxOnlyDbHistoryEventHandler` when `outboxOnlyHistoryKeys` is set; unlisted definitions unchanged | `history/OutboxOnlyDbHistoryEventHandler` (overrides `handleEvent`, `shouldWriteHistoricDetail`) | IR-5, FC-25 |
| B-18 | (IR-7) a `DefaultJobExecutor` sized from the settings is installed when the configuration has none; acquisition by due date / priority / exclusive-over-hierarchies set from the settings | `SangamProcessEnginePlugin.preInit` | IR-7, FC-24 |
| B-19 | (R2) acquisition commands (job acquisition, external-task fetch, lease sweep) are marked and never take L2; the lease sweep claims with per-dialect SKIP LOCKED / READPAST / Oracle two-step | `tx/RollbackGuardInterceptor`, `lock/RootLockFlushListener`, `lease/SangamLeaseTable` | R2, VI.1 rule 4 |
| B-20 | (R10) custom post deployer binds definitions to the FEEL engine; a pre decision-evaluation listener on the DMN engine refuses a mismatch; business rule tasks may declare strict-null results | `feel/*`, `SangamProcessEnginePlugin.preInit` (sets a `DefaultDmnEngineConfiguration` when none is set) | R10 |
| (opt.) | Per-definition history level (only when `historyLevelByProcessKey` is set; level id 21 stored in `ACT_GE_PROPERTY`) | `history/PerDefinitionHistoryLevel` | FC-26, D-49 |

## Internal (`impl`) classes used

| ID | Class | Where | Why |
|---|---|---|---|
| I-1 | `impl.cfg.StandaloneProcessEngineConfiguration`, `ProcessEngineConfigurationImpl` | factory, configurer, plugin | documented way to configure an embedded engine |
| I-2 | `impl.interceptor.Command`, `CommandContext`, `CommandInterceptor`, `CommandContextListener` | adapter, tx, lock | own commands, per-command hooks |
| I-3 | `CommandContext#getDbSqlSession().getSqlSession().getConnection()` | `tx/CommandState` | engine JDBC connection (Q1) |
| I-4 | `impl.cfg.TransactionState`, `TransactionContext#addTransactionListener` | `tx/CommandState` | B-1, LockScope exit |
| I-5 | `impl.util.ClockUtil` | **only** `clock/OperatonClock` | D-10 (no public clock API) |
| I-6 | `impl.context.Context` | several | current command context |
| I-7 | `DbEntityManager#registerOptimisticLockingListener`, `OptimisticLockingListener`, `DbOperation`, `DbEntity`, `ExternalTaskEntity` | adapter fetch | W-9 / D-19: relies on "last listener's result wins" in `DbEntityManager.invokeOptimisticLockingListeners` |
| I-8 | `DbEntityCache#getCachedEntities`, `CachedDbEntity`, `DbEntityState` | `lock/RootLockFlushListener` | L2 backstop; relies on `fireCommandContextClose` running before `flushSessions` (2.2.0 `CommandContext.tryCloseInternal`) |
| I-9 | `impl.calendar.*` (`CycleBusinessCalendar`, `DurationBusinessCalendar`, `DueDateBusinessCalendar`, `MapBusinessCalendarManager`) | timer | `TimerEntity.calculateNewDueDate` calls the cycle calendar through `CycleBusinessCalendar` (must stay a subclass) |
| I-10 | `impl.jobexecutor.JobHandler`, `persistence.entity.JobEntity/TimerEntity/ExecutionEntity`, `CommandContext#getCurrentJob` | `job/GuardedJobHandler`, timer | handler wrapping; `TimerEntity.preExecute` schedules the follow-up timer before the handler runs |
| I-11 | `impl.bpmn.parser.BpmnParseListener`, `impl.util.xml.Element`, `pvm.process.ActivityImpl`, `CallableElementActivityBehavior`, `cmmn.transformer.CmmnTransformListener`, `cmmn.entity.runtime.CaseExecutionEntity` | deploy | parse/transform SPI |
| I-13 | `impl.cmd.AcquireJobsCmd`, `impl.jobexecutor.AcquiredJobs`, `impl.jobexecutor.FailedJobCommandFactory` | `job/JobMetricsInterceptor`, `job/CountingFailedJobCommandFactory` | job metrics |
| I-12 | `impl.variable.serializer.*`, `impl.history.*`, `impl.incident.*`, `impl.persistence.StrongUuidGenerator` | variables, history, incident, plugin | serializer / history / incident SPI |

## Upgrade checklist (every Operaton minor)

1. Re-run P-1/P-2 detection (`secondPrecisionColumns`, `utf8mb3Tables`) after the upgrade scripts.
2. Re-read `DbEntityManager.invokeOptimisticLockingListeners` (I-7) and `CommandContext.tryCloseInternal` (I-8).
3. Check that `TimerEntity.calculateNewDueDate` still calls the `cycle` calendar and that `preExecute` still precedes the handler (I-9, I-10).
4. Re-check the `TEXT_` column sizes per dialect (B-7) and the H2 `BLOB` type in PostgreSQL mode (`DatabaseGuard.prepareH2`).
