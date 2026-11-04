# Patch ledger: Operaton 2.2.0 adapter

Module: `spike/adapter-operaton`. Engine: `org.operaton.bpm:operaton-engine:2.2.0` (Maven Central), embedded, no Spring,
`StandaloneProcessEngineConfiguration` over HikariCP 6.3.0. DMN and CMMN enabled. Job executor off.
Recorded 2026-11-04 on JDK 21.0.10 (Microsoft build), Windows 11, Docker 29.7.2.

Build: `.\mvnw.cmd -q -pl product-api install`, then `.\mvnw.cmd -q -pl adapter-operaton verify` (inside the shared
heavy-build lock). It passes with 72 tests, 0 failures, 0 errors and 0 skipped: 18 each on H2 2.3.232 in-memory,
PostgreSQL 17 (`postgres:17`), Oracle 23 Free (`gvenzl/oracle-free:23-slim-faststart`) and SQL Server 2022
(`mcr.microsoft.com/mssql/server:2022-latest`, own database with READ_COMMITTED_SNAPSHOT ON).
- The containers are started one at a time through `org.sangam.spike.sim.db.Databases`.
- `-Doperaton.dbs=postgres,oracle,sqlserver` selects the databases; the default is all three.
- History of the count: 28 tests (H2 and PostgreSQL only) before the A-15/A-10 fixes of 2026-11-04, and 18 before
  the earlier interface change.

Per-database runs of 2026-11-04 (8 workers × 200 tasks, test `concurrentFetch_8Workers_200Tasks_eachCompletesExactlyOnce`):

| DB | Tests | Concurrent-fetch wall time | Fetch retries | Give-ups | Errors |
|---|---|---|---|---|---|
| H2 | 18 / 18 | 1.3 s | 7 | 0 | 0 |
| PostgreSQL 17 | 18 / 18 | 4.4 s | 7 | 0 | 0 |
| Oracle 23 Free | 18 / 18 | 8.5 s | 7 | 0 | 0 |
| SQL Server 2022 | 18 / 18 | 17.4 s | 0 | 0 | 0 |
| MySQL 8.4 (Connector/J 8.4.0, patch P-1) | 18 / 18 | 5.2 s | 0 | 0 | 0 |
| MySQL 8.4 (MariaDB Connector/J 3.5.3) | 18 / 18 | 5.4 s | 0 | 0 | 0 |
| MySQL 8.4 (Connector/J, **without** P-1) | 18 / 18 | 7.7 s | 0 | 0 | 0 |

**MySQL run of 2026-11-04** (ADR-D-15, MySQL Tier 1): `.\mvnw.cmd -pl adapter-operaton verify -Doperaton.dbs=mysql`
passes with **41 tests**: 18 H2 (always on), **18 `OperatonAdapterMysqlTest`** and **5
`OperatonMysqlDatetimePrecisionTest`** (the P-1 evidence, see "MySQL 8.4" below), 0 failures/errors. `-Doperaton.dbs`
now defaults to `postgres,oracle,sqlserver,mysql`. MySQL settings, the P-1 schema patch and the driver results are in
the section **MySQL 8.4** below. 0 fetch retries on MySQL: the first-use race on `spike_fetch_lock` is a no-op upsert
there (`INSERT … ON DUPLICATE KEY UPDATE`), so losers wait on the row lock instead of failing.

The 7 retries are the first-use race on the new `spike_fetch_lock` row: 7 of the 8 workers lose the insert and retry
once. On SQL Server most losers wait on the key lock instead: 0 retries in the single-DB run, 2 in the full
four-database run (13.8 s, also 0 errors). In the full run the other wall times were H2 1.5 s, PostgreSQL 4.5 s and
Oracle 7.1 s, all with 0 errors.

## Summary

| Kind | Count | Size |
|---|---|---|
| Engine patches (changed Operaton source or bytecode) | **0** | 0 lines |
| Engine **schema** patches (DDL changing engine tables after the engine created them) | **1** (P-1, MySQL only, optional with the MariaDB driver) | `MysqlDatetimePrecisionPatch`, ~45 code lines; 66 columns `datetime`/`timestamp` → precision 3 |
| Reflection | **0** | none |
| Internal (`impl`) classes used in main code | **8** (I-1 to I-8) | all in `OperatonEngineAdapter.java` |
| Workarounds | **12** (W-1 to W-12) | about 45 lines for W-1…W-7 (pom and adapter), about 44 adapter lines for W-8, about 150 adapter lines for W-9…W-11, plus the shared `LeaseTable` (product-api, ~290 lines with the W-12 dialect SQL, counted once for both engines) and `ExpiryPolicy` (product-api, ~50 lines) |

## Workarounds

| ID | File | What | Why | Size |
|---|---|---|---|---|
| **W-1** | `adapter-operaton/pom.xml` | Jackson set to 2.22.1 in this module: `jackson-databind` override plus a `jackson-bom` 2.22.1 import | Operaton 2.2.0 → `operaton-engine-dmn` → `org.camunda.feel:feel-engine:1.22.1` → `jackson-module-scala_2.13:2.22.1`. That module only accepts databind 2.22.x. The parent pins `jackson-databind` to 2.19.2 and leaves the rest alone, so the build mixes versions. **The engine then fails to build:** `JsonMappingException: Scala module 2.22.1 requires Jackson Databind version >= 2.22.0 and < 2.23.0 - Found jackson-databind version 2.19.2` (checked by removing the override). **This affects every module that puts adapter-operaton next to the parent's Jackson pin, for example `experiments`.** Recommended parent change: `version.jackson` 2.22.1, or import `jackson-bom`. A test guards it: `feelJsonBuiltinWorks` runs FEEL `to json()`. **Update 2026-11-04 (security finding E12):** 2.22.1 has 5 HIGH OSV findings, fixed in 2.22.3, so `version.jackson.operaton` is now **2.22.3** (the parent's `version.jackson` is 2.22.3 too). `jackson-module-scala_2.13` 2.22.3 exists on Maven Central (also 2.22.3.1 and 2.22.4) and comes in through the `jackson-bom` 2.22.3 import; `dependency:tree` shows databind, core and scala module at 2.22.3 (annotations at `2.22`, the new annotations versioning). The Scala module accepts databind 2.22.3. **FEEL still starts:** `feelJsonBuiltinWorks` and `dmnBusinessRuleTaskEvaluates` pass on H2 and on MySQL 8.4 (both drivers) in the 2026-11-04 MySQL runs | ~20 lines of POM |
| **W-2** | `OperatonEngineAdapter.inEngineTransaction` | Registers a `TransactionState.ROLLINGBACK` listener that calls `connection.rollback()` on the engine connection | **Q1 hazard, measured on H2 and PostgreSQL.** MyBatis `DefaultSqlSession.rollback()` does nothing while its `dirty` flag is false, which means no MyBatis write has run yet. Operaton flushes its own writes only at command close. So when an exception is thrown inside the command, MyBatis skips the rollback. `JdbcTransaction.close()` then calls `resetAutoCommit()`, which runs `setAutoCommit(true)`, and under JDBC rules that **commits** the product rows written through the connection. The engine's own changes are thrown away. Test `q1HazardPlainCommandCommitsProductRowOnException` shows this on a plain Operaton command. Test `q1ProductRowAndEngineStateRollBackAndCommitTogether` passes only with W-2 in place | 8 lines |
| **W-3** | `OperatonEngineAdapter` constructor | `setEnforceHistoryTimeToLive(false)` | 2.2.0 rejects a BPMN/DMN/CMMN deployment that has no `historyTimeToLive` (the default is `true`). The corpus does not set TTL. The other choice is to put TTL in every corpus model | 1 line |
| **W-4** | `OperatonEngineAdapter.buildTaskQuery` | When both a candidate user and candidate groups are given, the adapter uses an OR query (`or().taskCandidateUser().taskCandidateGroupIn().includeAssignedTasks().endOr()`) | `TaskQueryImpl` throws "cannot set both candidateUser and candidateGroupIn" in an AND query. `taskCandidateUser` also expands the user's groups from Operaton's own identity tables (`ACT_ID_*`), which the product does not fill, so the product must pass groups itself. Assigned tasks are included on purpose | 6 lines |
| **W-5** | `adapter-operaton/pom.xml` (surefire) | System property `api.version=1.44` | Docker Engine 29 rejects the default API version 1.32 that Testcontainers 1.21.3 (docker-java) sends, with HTTP 400 "Could not find a valid Docker environment". This is test infrastructure, not an engine issue. **The other Testcontainers modules on 1.21.3 will hit the same problem** | 5 lines |
| **W-6** | `adapter-operaton/pom.xml` | `slf4j-api` set to `${version.slf4j}` (2.0.17) | HikariCP 6.3.0 brings slf4j-api 1.7.36, which cannot bind slf4j-simple 2.x. Logging only | 4 lines |
| **W-7** | `OperatonEngineAdapter` | `setClock` uses `ClockUtil`, which is **static and JVM-wide** | Every Operaton engine in the same JVM shares this clock. Two adapters in one test JVM cannot use different clocks. `close()` and the test `@AfterEach` reset it. The lease checks of W-8 read the same JVM-wide clock | n/a (design note) |
| **W-8** | `OperatonEngineAdapter`: `fetchAndLock`, `extendLock`, `completeExternal`, `failExternal`, `bpmnErrorExternal`, `unlockExternal`, `executeDueJobs`, constructor | **Fencing by lockVersion, adapter-side.** Chosen: the adapter-owned table `SPIKE_LEASE(work_id PK, lock_version, attempt_id, worker_id, expires_at)` through the shared `org.sangam.spike.api.LeaseTable` (product-api, 142 lines / ~100 code lines, the **same class and SQL as the Flowable adapter**, so counted once for both). **The `worker#lockVersion` trick was not used**: the engine sees the plain workerId. Every call is one `inEngineTransaction` command (W-2 makes it safe): `LeaseTable.check` (row lock `FOR UPDATE`; no lease / stale version / not owner / `expires_at <= ClockUtil` → `StaleLeaseException`), then the engine call, then the lease update (complete/bpmnError: delete row; fail/unlock: `lock_version+1`, owner cleared; extend: new `expires_at`). Fetch: the expiry sweep, Operaton's `fetchAndLock`, then `lock_version+1` and a new `attemptId` per task, all in one command. `executeDueJobs` also runs the expiry sweep | Operaton has no fencing token (`REV_` is internal and also moves on extend), complete/fail/bpmnError do not check `LOCK_EXP_TIME_` (G-2), and `unlock(id)` checks nothing. Before this change the adapter called `ExternalTaskService` directly in one line per method, so **each call now needs its own command wrapper**, which is most of the line cost | ~44 adapter lines (13 `LeaseTable` call lines, 4 for table creation, ~20 for the five command wrappers and the fetch wrapper, 3 for the sweep in `executeDueJobs`, 3 for `now()`) | Low (public API plus I-2…I-5 as before) |
| **W-9** | `OperatonEngineAdapter.fetchAndLock` (+ `LeaseTable.lockFetch`, `LeaseTable.grant`) | **Concurrent fetch fixed (XI.10.4 defect, E9 B-2).** Each attempt is two commands: (1) the expiry sweep (W-10), committed; (2) the fetch command: `LeaseTable.lockFetch(topic)` takes an exclusive row lock on `SPIKE_FETCH_LOCK(topic)`, which **serializes every adapter fetch of a topic with the engine's own fetch query**. Then come Operaton `fetchAndLock`, a per-task `LeaseTable.lock` row lock, and the `LeaseTable.grant` **upsert** (idempotent per work_id: PostgreSQL `INSERT … ON CONFLICT`, Oracle/H2 `MERGE`, SQL Server `MERGE WITH (HOLDLOCK)`; no insert-then-duplicate path). The adapter then registers an `OptimisticLockingListener` for `ExternalTaskEntity` that returns `THROW`. Registered after the engine's own listener, it wins, because the last listener's result is used. **Any engine optimistic-lock conflict on an external task therefore rolls the whole attempt back (lease rows too) instead of being silently ignored.** A conflict is then retried: engine OLE; deadlock, serialization or lock timeout (PG 40P01/40001/55P03, ORA-00060/08177/30006, MSSQL 1205/3960/1222, H2 HYT00); duplicate key on first use of a row (23505, ORA-00001, MSSQL 2627/2601). The retry uses jittered back-off, up to 20 attempts. After that the fetch returns an empty list: nothing committed, all work stays AVAILABLE, never stuck. When nested in a caller's `inEngineTransaction` there is one attempt only (`Context.hasActiveCommandContext()`). Counters: `fetchRetries()`, `fetchGiveUps()` | **Root cause, read from the 2.2.0 bytecode.** `FetchExternalTasksCmd.filterOnOptimisticLockingFailure` registers a listener that, at flush, removes a conflicting task from the returned list and returns `IGNORE`, so the command **commits**. The old adapter had already written the SPIKE_LEASE row for that task, before the flush, and had already put the task in its own result. The losing worker therefore received a task it did not own in the engine ("locked by worker-N"). Its lease row overwrote the winner's version (STALE_VERSION for the real owner) or collided on insert (duplicate `spike_lease_pkey`). Nobody could complete the task until expiry → stuck instances. A mid-command `DbEntityManager.flush()` was rejected: `DbOperationManager.calculateFlush()` does not clear its operation lists, so the close-time flush would replay the updates | ~110 adapter lines (fetch loop, `fetchOnce`, listener, `isTransientConflict`, back-off) + ~40 `LeaseTable` lines | Medium: I-7, I-8 (the listener contract and `ExternalTaskEntity` are internal) |
| **W-10** | `OperatonEngineAdapter.sweepExpired` / `applyExpiry`, `setExpiryPolicy`; product-api `ExpiryPolicy`; `EngineAdapter.setExpiryPolicy` (default method) | **A-10 lease-expiry hook.** `ExpiryPolicy.onExpired(ExpiredWork, Connection)` (`ExpiredWork` = workId, expired owner, lockVersion, attemptId, expiry, topic, processInstanceId, activityId, activityInstanceId). It returns `REOFFER` (default `ALLOW_REOFFER`) or `HOLD`. It is called in the same command as the lease end, **before the work can be fetched again**, in two places. (a) The sweep that runs before every fetch (its own committed command, see why) and in `executeDueJobs`: candidate rows are read without a lock, then locked by key and re-checked (`LeaseTable.lockExpired`), in work_id order. (b) Inside the fetch command, for a task the engine itself re-offered while its SPIKE_LEASE row is still held (the engine and the lease disagree on expiry by a few ms): the policy runs before the new grant. **HOLD design chosen: retries = 0 / incident.** `ExternalTaskService.handleFailure(id, engineLockOwner, "LEASE_EXPIRED_HELD: …", null, 0, 0)` is called; `setRetries(id, 0)` is used if the engine has no lock owner. The lease ends (lock_version + 1), so the stale owner gets `STALE_VERSION`. The task is not fetchable (the fetch query requires retries null or > 0). The product releases it with `setRetries(id, n)`, which also resolves the incident; the next fetch gets lock_version + 2. **The sentinel-owner option was rejected**: it needs the internal `ExternalTaskEntity.lock` (or a targeted fetch), and it leaves nothing visible for operators. An incident is visible in Cockpit/REST and carries the reason. A task whose external task no longer exists has its lease row removed without a policy call. `extendLock` now stores the engine's own new lock expiry in SPIKE_LEASE, so lease and engine agree | **Why the sweep is its own command:** a HOLD made inside the fetch command is only buffered in the entity cache until flush. The engine's fetch is a SQL query, so it would still select the held task (and the cached entity would be locked again). The nested case (sweep and fetch in one caller command) undoes the engine lock with `unlock(id)` for ids held in that command. E3 measured a double payment when engine-side expiry ran before the product sweep (F-4) | ~60 adapter lines + `ExpiryPolicy` (~50 lines) | Low (public API: `handleFailure`, `setRetries`, `createExternalTaskQuery`) |
| **W-11** | `OperatonEngineAdapter.cancelInstance` | **Leases on delete (E7 F-7).** One command: the external-task ids of the instance and of all its sub-process instances are collected (recursive `superProcessInstanceId` query, depth ≤ 64), then `deleteProcessInstance` runs, then `LeaseTable.remove` for each id. A late `complete`/`fail`/`extend`/`unlock` then gets `StaleLeaseException(NO_LEASE)` and changes nothing. Before, it passed the fence and failed inside the engine with `NotFoundException`. Rows are removed rather than tombstoned; the product's own tombstone (E7) remains the 410 source. CMMN `terminateCaseExecution` is unchanged (no external tasks) | E7 raw-adapter finding | ~25 adapter lines | Low |
| **W-12** | product-api `LeaseTable` (shared with Flowable, signature-compatible) | **A-15: per-dialect lease SQL.** The dialect is detected from `DatabaseMetaData.getDatabaseProductName()` (`LeaseTable.Dialect.detect`): PostgreSQL, Oracle, SQL Server, H2, plus a generic ANSI fallback. Per dialect: **create-if-missing** (PG/H2 `CREATE TABLE IF NOT EXISTS`; Oracle PL/SQL `EXECUTE IMMEDIATE` ignoring ORA-00955, with `NUMBER(19)` / `VARCHAR2`; SQL Server `IF OBJECT_ID(…,'U') IS NULL CREATE TABLE`); **upsert** (see W-9); **row lock** (`… FOR UPDATE` on PG/Oracle/H2, `WITH (UPDLOCK, ROWLOCK)` on SQL Server). New: `SPIKE_FETCH_LOCK(topic PK)`, `lockFetch`, `lock`, `lockExpired`, `Row`. Kept: `createIfMissing`, `grant`, `check`, `extend`, `end`, `remove`, `expire`, with unchanged signatures, so `adapter-flowable` compiles unchanged. **Neither adapter constructor assumes PostgreSQL any more**, so the experiments-load JDBC shim (`DbSetup.leaseShim`) is no longer needed for spike_lease: its patterns no longer match, so the statements pass through unchanged | E6 blocker B-1: the adapters could not even be built on Oracle/SQL Server. **MySQL added 2026-11-04 (ADR-D-15):** `LeaseTable.Dialect.MYSQL`, detected from a product name containing "MySQL" or "MariaDB" (MySQL server, MariaDB server, either Connector/J). Create-if-missing `CREATE TABLE IF NOT EXISTS … ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin` (largest key `varchar(255)` = 1020 bytes, under the 3072-byte limit); grant upsert `INSERT … ON DUPLICATE KEY UPDATE lock_version = lock_version + 1, …` with the values bound again (no deprecated `VALUES()`, no row alias that MariaDB lacks); row lock `SELECT … FOR UPDATE`; `lockFetch` creates the topic row with a no-op `ON DUPLICATE KEY UPDATE topic = topic`, so a concurrent first use waits on the row lock instead of failing with duplicate key 1062 (SQLState 23000), which `isTransientConflict` does not list; `lockExpired` unchanged (plain read, then lock by key). MySQL deadlock 1213 (SQLState 40001) and lock-wait timeout 1205 are already covered by `isTransientConflict` | ~150 lines more in `LeaseTable`; +~25 for MySQL | Low |

Adapter design decisions. These are not workarounds, but other adapters should know them:
- `startCase(...)` (interface method since 2026-11-04) uses `CaseService.withCaseDefinitionByKey`. `startProcess` still falls back to it when no process definition has the key but a CMMN case definition does.
- Case tasks go through the ordinary task calls (the interface choice: no `queryCaseTasks`). `TaskView.processInstanceId` holds the case instance id for CMMN tasks. `getInstance`, `queryTasks(processInstanceId)` and `cancelInstance` (terminate) also accept case instance ids. Operaton's `TaskService` claims and completes case tasks natively, so no routing is needed (Flowable needs it, see its F7).
- A case task's `taskDefinitionKey` is the **plan item id** (`PI_scrutiny`) on Operaton but the humanTask id on Flowable. Not normalized.
- `getInstance` returns `null` for an unknown id (now in the interface Javadoc, and tested).
- `ExternalWork.activityInstanceId` = `LockedExternalTask.getActivityInstanceId()` (native, no extra query). It is the same across lease expiry and re-fetch (test (c)).
- `executeDueJobs` runs `ManagementService.executeJob` on `createJobQuery().executable().active()` until nothing is due (at most 10,000 runs). A failing job is swallowed after the engine decrements its retries.

## MySQL 8.4 (ADR-D-15, 2026-11-04)

Container: `mysql:8.4` through `Databases` (simulators): server `--character-set-server=utf8mb4
--collation-server=utf8mb4_bin --transaction-isolation=READ-COMMITTED`. The test creates its own database
`operaton_it` (`utf8mb4` / `utf8mb4_bin`). Classes: `OperatonAdapterMysqlTest` (the 18 tests), `AbstractContainerDbTest`
(MySQL branch), `OperatonMysqlDatetimePrecisionTest` (5 tests, evidence for P-1), main `MysqlDatetimePrecisionPatch`.

**Engine configuration needed on MySQL** (no adapter code change; all in the JDBC URL and one post-create patch):

| Setting | Value | Why |
|---|---|---|
| JDBC URL (Connector/J) | `useSSL=false&allowPublicKeyRetrieval=true` | `caching_sha2_password` without TLS in a test container (production: TLS instead) |
| | `nullCatalogMeansCurrent=true&characterEncoding=UTF-8&connectionTimeZone=UTC` | harness defaults; `connectionTimeZone=UTC` keeps `Date` ↔ DATETIME conversion in UTC |
| | `sessionVariables=transaction_isolation='READ-COMMITTED'` | **required**, see finding (4) |
| JDBC URL (MariaDB Connector/J) | same, plus `timezone=UTC` | its counterpart of `connectionTimeZone` |
| Schema | **P-1** (`MysqlDatetimePrecisionPatch.apply`) after the engine's create/upgrade, with Connector/J | finding (1)–(3) |
| `sendFractionalSeconds` | **left at the default `true`** | Operaton's own MySQL CI (`OperatonMySqlContainerProvider`, `database/pom.xml`) sets `false` so that the driver truncates instead of the server rounding; with P-1 the columns keep milliseconds and the default is correct. Without P-1, `false` makes every time truncate (up to 999 ms early) |

**Findings (scoring evidence):**

1. **Column types (measured).** Operaton 2.2.0's `activiti.mysql.create.*.sql` scripts create **66 time columns with
   second precision**: 61 `datetime` + 5 `timestamp`, e.g. `ACT_RU_EXT_TASK.LOCK_EXP_TIME_`, `ACT_RU_JOB.LOCK_EXP_TIME_`,
   `ACT_RU_JOB.DUEDATE_` (timers are jobs; there is no `ACT_RU_TIMER`), `ACT_RU_TASK.CREATE_TIME_`,
   `ACT_HI_PROCINST.START_TIME_`, `ACT_HI_ACTINST.START_/END_TIME_`, `ACT_HI_DETAIL.TIME_`, `ACT_RU_METER_LOG.TIMESTAMP_`:
   `information_schema.columns.datetime_precision = 0` for all of them. The `activiti.mariadb.create.*.sql` scripts of the
   same jar use `datetime(3)` / `timestamp(3)` for the same 66 columns. The engine tables are also created with
   `DEFAULT CHARSET=utf8 COLLATE utf8_bin`, i.e. **utf8mb3** (measured on `ACT_RU_VARIABLE.TEXT_`: `utf8mb3/utf8mb3_bin`),
   even in a `utf8mb4` database: 4-byte characters (emoji, some CJK extension characters) cannot be stored in engine
   string columns. Indian scripts are in the BMP (3 bytes) and are not affected.
2. **Effects (measured, engine API, engine clock fixed by `ClockUtil`, Connector/J default rounding):**
   - **Lease 1500 ms:** locked at `…:41.700` for 1500 ms, intended expiry `…:43.200`, stored `LOCK_EXP_TIME_` `…:43.000`;
     a second worker fetching at `…:43.100` **got the task while the first worker's lock was still live** (200 ms of
     the 1500 ms lease lost; up to 500 ms when rounding, up to 999 ms with `sendFractionalSeconds=false`). The adapter's
     own fence (W-8, `SPIKE_LEASE.expires_at` in epoch ms) still stops the stale worker's late complete, but the engine
     re-offers the work early, and W-10(b) then runs the expiry policy before the product lease has expired.
   - **Timer PT1S:** started `…:10.400`, intended due `…:11.400`, stored `DUEDATE_` `…:11.000`; `executeDueJobs` at
     `…:11.100` **fired it 300 ms early** (rounding up instead delays a timer by up to 499 ms).
   - **Ordering:** two instances started at `…:40.100` and `…:40.400` were stored with the **same** `START_TIME_`
     `…:40.000`: ordering by start time (history, audit export, "latest first" task lists by `CREATE_TIME_`) is
     undefined within a second.
3. **Fix P-1 (engine schema patch, counted above).** `MysqlDatetimePrecisionPatch` alters every `ACT_*` `datetime` /
   `timestamp` column with precision 0 to precision 3 (keeping NULL / NOT NULL; the scripts have no time defaults),
   one `ALTER TABLE` per table, idempotent: **66 columns altered**. After it, measured on the same database: stored
   `LOCK_EXP_TIME_` = intended `…:23.200`, no early fetch at `…:23.100`; `DUEDATE_` = `…:51.400`, not fired at −300 ms,
   fired at +100 ms; start times `…:20.100` and `…:20.400` kept apart. **The 18 adapter tests pass with P-1 (18/18)**,
   and also without it (18/18: the adapter suite's own margins are ≥ 1 s and its fence uses epoch-ms leases), so the
   suite alone does not detect the defect; `OperatonMysqlDatetimePrecisionTest` does. Production would ship P-1 as a
   migration script run after every engine create/upgrade (an Operaton upgrade script may add or re-create columns in
   second precision; re-check `secondPrecisionColumns()` after each upgrade). Alternative without a patch: the MariaDB
   driver, see below.
4. **READ COMMITTED is enforced by the engine.** With `sessionVariables=transaction_isolation='REPEATABLE-READ'` (MySQL's
   default) the engine **refuses to start**: `ENGINE-12019 The transaction isolation level set for the database is
   'REPEATABLE_READ' which differs from the recommended value. Please change the isolation level to 'READ_COMMITTED' or
   set property 'skipIsolationLevelCheck' to true` (`ProcessEngineConfigurationImpl.checkTransactionIsolationLevel`,
   checked on every engine build). So READ COMMITTED is not only a recommendation on MySQL; the deadlock behaviour
   under REPEATABLE READ was therefore not measured (it would need `skipIsolationLevelCheck=true` in the adapter).
   The setting is now in the test URL (`sessionVariables`, set per session, so it does not depend on the server
   default); Operaton's own MySQL test container sets `--transaction-isolation=READ-COMMITTED` on the server.

**MariaDB Connector/J 3.5.3 against MySQL 8.4 (LIC-4): works, 18/18** (`-Dspike.mysql.driver=mariadb`, 5.4 s for the
concurrent fetch, 0 retries, 0 give-ups). **But Operaton then runs its MariaDB dialect on the MySQL server:**
`checkForMariaDb` returns "MariaDB" when the *driver name* contains "mariadb", so the engine executed
`activiti.mariadb.create.*.sql` (log `ENGINE-03016 … activiti.mariadb.create.engine.sql`) and the MariaDB MyBatis
mappings. Consequence: the time columns are already `datetime(3)` (P-1 found **0 columns** to alter), so this route
needs no schema patch; but `databaseType=mariadb` on a MySQL server is a combination Operaton's CI does not test, and
a schema created through one driver must keep being used through the same driver type (the upgrade scripts are chosen
by `databaseType`). Forcing `databaseType=mysql` with the MariaDB driver would bring back the second-precision scripts
(not tried). `LeaseTable` detects MYSQL in both cases.

**MySQL limitations found:** second-precision engine time columns (P-1 needed with Connector/J); utf8mb3 engine
tables (no 4-byte characters in engine string columns; Operaton does not offer utf8mb4 scripts); REPEATABLE READ (the
MySQL default) rejected by the engine; duplicate-key on INSERT takes a shared lock in InnoDB, so "insert, then lock"
patterns deadlock (fixed in product-layer `RootSeq` and `LeaseTable.lockFetch` with a no-op upsert); the MariaDB-driver
route silently switches the engine to its MariaDB dialect.

## Internal (`impl`) classes used (main code)

| ID | Class | Where | Why |
|---|---|---|---|
| I-1 | `org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration` | constructor | Standard documented way to build an engine without Spring/JTA, even though the class is in `impl` |
| I-2 | `org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl#getCommandExecutorTxRequired()` | `inEngineTransaction` | Runs a custom command (Q1) |
| I-3 | `org.operaton.bpm.engine.impl.interceptor.Command` / `CommandContext` | `inEngineTransaction` | Custom command body; engine service calls made inside it reuse the context (`CommandContextInterceptor`, the "context reuse" path) |
| I-4 | `CommandContext#getDbSqlSession()` → `DbSqlSession#getSqlSession()` (MyBatis) `.getConnection()` | `inEngineTransaction` | Exposes the engine's JDBC connection (Q1) |
| I-5 | `org.operaton.bpm.engine.impl.cfg.TransactionState` + `TransactionContext#addTransactionListener` | `inEngineTransaction` | W-2 |
| I-6 | `org.operaton.bpm.engine.impl.util.ClockUtil` | `setClock`, `close` | Engine clock (W-7). The Camunda-7 lineage has no public clock API |
| I-7 | `org.operaton.bpm.engine.impl.context.Context#hasActiveCommandContext()` | `fetchAndLock` | Tells a nested call (one attempt, no retry) from a top-level one (W-9) |
| I-8 | `CommandContext#getDbEntityManager()`, `DbEntityManager#registerOptimisticLockingListener`, `OptimisticLockingListener`, `OptimisticLockingResult`, `DbOperation`, `DbEntity`, `persistence.entity.ExternalTaskEntity` | `fetchOnce` | Turns the engine's ignored external-task OLE in fetch into a command failure (W-9). It depends on "last listener's result wins" in `DbEntityManager.invokeOptimisticLockingListeners` (2.2.0 bytecode); re-check on every engine upgrade |

Test code also casts to `ProcessEngineConfigurationImpl` in the hazard test.

## Q1: can product tables commit in the same transaction as engine state?

**Yes, with W-2, and with no engine patch.** Measured on H2 and PostgreSQL 17.

- How it works: `inEngineTransaction(work)` runs one Operaton command (tx REQUIRED) and passes `work` the connection of the command's `DbSqlSession`. Engine API calls made inside `work`, such as `adapter.startProcess(...)` or `completeTask(...)`, reuse the same command context. That gives one connection, one transaction, and one commit at command close.
- Proven by `q1ProductRowAndEngineStateRollBackAndCommitTogether`:
  - (a) Product row, then engine start, then an exception from product code: both roll back.
  - (b) Product row, then engine start, then an engine exception (unknown task): both roll back.
  - (c) No exception: both commit. The product row is present, and the instance appears in runtime and history.
  - The connection reports `autoCommit=false`.
- **Caveat 1 (W-2): without the explicit rollback, Q1 is silently broken.** It fails exactly when the engine has not yet written through MyBatis, which is the normal case because writes are buffered until flush. Every product component that uses an engine connection must go through `inEngineTransaction`, never through a raw command.
- **Caveat 2: write ordering.** Product SQL runs at once. Engine INSERT/UPDATE statements run at flush, after `work` returns. Product SQL inside `work` cannot read engine rows created in the same call. A product unique-key or FK violation fails before the engine flushes. An engine `OptimisticLockingException` at flush rolls back the product rows too, because the flush runs through MyBatis, which marks the session dirty, so the rollback works. That OLE path is reasoned from the code, not tested.
- Caveat 3: when the engine made no MyBatis write, the commit on the success path also depends on `setAutoCommit(true)` in `JdbcTransaction.close()`, because MyBatis skips `commit()` for a session that is not dirty. JDBC defines this behaviour, and it worked on H2 and PostgreSQL. Oracle and SQL Server are not yet tested (E6).

## Q3: can the external-task API support the VI.3 lease states?

**Partly. It covers AVAILABLE→LOCKED→COMPLETED/FAILED/INCIDENT/BPMN-error and release. It does not cover fencing, OFFERED, UNKNOWN_OUTCOME or CANCEL_REQUESTED.** Measured in `leaseSemanticsOfExternalTasks` on H2 and PostgreSQL.

| VI.3 need | Operaton 2.2.0 | Evidence |
|---|---|---|
| Atomic fetch + lock with lease | Yes: `fetchAndLock(max, workerId).topic(t, lockMs)` sets `WORKER_ID_` and `LOCK_EXP_TIME_`. Locked work is not offered to other workers | test |
| Extend (heartbeat) | Yes, owner only: another worker gets `BadUserRequestException`. **An expired lease cannot be extended** ("Cannot extend a lock that expired") | test |
| Lease length per topic | Only per fetch call, chosen by the worker. No engine-side per-topic default or maximum | API |
| Release (`release(lock_version)`) | `unlock(id)` with **no worker check at all**: anyone can unlock. The adapter's `unlockExternal(workId, workerId, lockVersion)` checks owner and version (W-8) | API and test (d) |
| Fail with back-off, retries=0 → INCIDENT | Yes: `handleFailure(id, worker, msg, retries, backoffMs)`. Hidden until back-off ends; incident at 0 | test |
| BPMN error routed by the model | Yes: `handleBpmnError` → boundary event | test |
| **Fencing (`lockVersion` / `attemptId`)** | **Missing in the engine.** Complete, fail and bpmnError check **only `workerId` equality** (`HandleExternalTaskCmd.validateWorkerViolation`). The task id stays the same across redeliveries. `REV_` is an internal optimistic-lock counter, not exposed by `LockedExternalTask`. It also increments on extend. **Built in the adapter (W-8)** | tests (a), (c) |
| Stale owner after lease expiry | **Accepted by the engine.** If nobody has re-locked the task, the original worker can still complete it after the lease expired: complete does not check `LOCK_EXP_TIME_`. A worker that re-fetches under the same `workerId` is also not told apart from its earlier attempt. **Rejected by the adapter (W-8)** | `leaseSemanticsOfExternalTasks` (engine call accepted, adapter call rejected), test (b) |
| OFFERED vs LOCKED (stream close releases only OFFERED) | **Missing.** Fetch locks at once; there is no un-acked state. Long polling exists only in the REST layer (`engine-rest`), not in the embedded API | API |
| UNKNOWN_OUTCOME and reconciliation | **Missing** | — |
| CANCEL_REQUESTED, `cancelRequested` in the heartbeat | **Missing.** Deleting the instance deletes the external task; the worker then gets `NotFoundException` on complete | API |
| `attempt` counter, `operationId` (R36) | No attempt counter (only worker-supplied `retries`). `LockedExternalTask.getActivityInstanceId()` is available, so `operationId = UUIDv5(tenant, rootId, activityInstanceId, …)` can be derived in the product layer | API |
| `effect=irreversible` → no blind retry | Missing; the product layer must enforce it | — |

The product layer can close these gaps without an engine patch, because Q1 holds. It can keep a lease row (`lock_version`, `attempt`, `state`) in `OPERATION_LEDGER`, written in the same transaction as `fetchAndLock`/`complete` through `inEngineTransaction`, and pass an engine `workerId` of the form `<worker>#<lockVersion>`. The engine's worker-id check then works as the fencing token, and the expired-lease check is done against the ledger. The cost is one extra product row per lease transition. **Built on 2026-11-04 as W-8, with a plain `SPIKE_LEASE` table instead of `OPERATION_LEDGER`, and without the `<worker>#<lockVersion>` trick** (the table check alone is enough, and it keeps the engine's worker id readable and the same as on Flowable).

## SKIP LOCKED job acquisition

**Present in 2.2.0.** `ProcessEngineConfiguration#setJobExecutorAcquireWithSkipLocked(boolean)` exists. The 2.2.0 `Job.xml` `selectNextJobsToExecute` has a `skipLocked` branch for each dialect: generic `FOR UPDATE SKIP LOCKED` (PostgreSQL, H2), SQL Server `UPDLOCK/READPAST`, DB2 `SKIP LOCKED DATA`, and Oracle without a row limit. The adapter sets it to `true`. It has **no effect here**, because the job executor is off and `executeDueJobs` uses `ManagementService.executeJob`. It only applies to **job** acquisition. The 2.2.0 `ExternalTask.xml` (`selectExternalTasksForTopics`) has no `FOR UPDATE`/`SKIP LOCKED`, so concurrent external-task fetches compete through optimistic locking on `REV_`. Measured in E9 (B-2: 17 % failures with 4 fetchers); fixed in the adapter by the per-topic fetch lock and the OLE listener (W-9).

## Licences and dependencies (LIC-1)

- **GraalJS: not pulled.** The operaton-engine 2.2.0 POM declares `org.graalvm.polyglot:polyglot`, `org.graalvm.polyglot:js` and `org.graalvm.js:js-scriptengine` in **test scope only**, so none of them is transitive. The adapter POM still excludes `org.graalvm.polyglot:{js,polyglot}` and `org.graalvm.js:{js,js-scriptengine}` as a guard. As a result, no JavaScript (or Groovy) script engine is available, so corpus models must use JUEL/FEEL expressions or external tasks instead of script tasks.
- Compile/runtime dependencies brought by the engine: operaton model/juel/dmn/commons 2.2.0, `org.camunda.feel:feel-engine` 1.22.1, `scala-library` 2.13.18, fastparse/sourcecode/geny (lihaoyi, MIT), `jackson-module-scala`/core/databind 2.22.1, `paranamer` 2.8.3 (BSD), `java-uuid-generator` 5.2.0, `mybatis` 3.5.19, `spring-beans` + `spring-core` 7.0.10 (+ `commons-logging`), `joda-time` 2.15.0, `jspecify`, `jakarta.el-api` 6.0.1. All are Apache-2.0, MIT, BSD or EPL-compatible.
  - `spring-beans`/`spring-core` are **compile dependencies of operaton-engine**, used for XML configuration files. This adapter does not use them: no Spring context or transaction manager. Excluding them is possible but not tested.
- Test only: H2 2.3.232 (EPL-1.0/MPL-2.0), PostgreSQL JDBC 42.7.7 (BSD-2), Testcontainers 1.21.4 (MIT), JUnit 5.11.4 (EPL-2.0), slf4j-simple, `simulators` (the `Databases` harness). **Oracle JDBC `ojdbc11` 23.8.0.25.04 (Oracle Free Use Terms) and `mssql-jdbc` 12.10.1.jre11 (MIT) are declared `test` scope only** (LIC-2): they are not transitive and are not in the module jar. **MySQL Connector/J 8.4.0 (GPL-2.0 with the Universal FOSS Exception) and MariaDB Connector/J 3.5.3 (LGPL-2.1) are
  test scope only** (2026-11-04, ADR-D-15). No Db2 driver.

## JDK notes

- JDK 21.0.10 (Microsoft OpenJDK): builds and runs with no `--add-opens`, no illegal-access warnings and no JVM flags.
- Operaton 2.2.0 needs Java 17+.
- JDK 25 has **not** been tested here (that is E10).
