# experiments-integration

Experiments **E2, E3, E4, E5 and E7** of SANGAM.md Part XI §XI.4, plus the failure tests **T08** (poison outbox event), **T35** (restore with newer broker messages) and the new-version phase of **T28** (upgrade with instances in flight). Each one runs on **both engines**: Flowable 8.0.0 and Operaton 2.2.0. The database is PostgreSQL 17 by default; E2-E5/E7 and T08 also run on Oracle Free 23, SQL Server 2022 and MySQL 8.4 (see Databases).

The module wires the product layer (`org.sangam.spike.layer.*`) to the real engines through `EngineAdapter`. The results go to `../results/E2.json`, `E3.json`, `E4.json`, `E5.json` and `E7.json`. The comparison is in `../results/E2-E7-report.md`.

## Build

From `spike/`:

```
.\mvnw.cmd -q -pl experiments-integration verify
```

This needs JDK 21 and Docker, and every other module must be installed in `~/.m2`. A full run takes about 12 minutes on the dev machine. E2 accounts for most of it, because it starts 2,000 process instances per engine.

| Option | Effect |
|---|---|
| `-Dtest=E3*Test` | One experiment only (both engines) |
| `-De2.tasks=10000` | E2 at the XI.4 task count. It is stored as a separate, *supplementary* entry in `E2.json`. The main entry keeps the 2,000-task run |
| `-Dspike.db=postgres\|oracle\|sqlserver\|mysql` | Database (default `postgres`). See Databases |
| `-Dspike.engines=operaton,flowable` | Engines to run (default both). A class is selected by its name (`*OperatonTest` / `*FlowableTest`, `EngineSelection`) |
| `-Dspike.mysql.driver=mysql\|mariadb` | MySQL runs only: Connector/J (default) or the MariaDB Connector/J (LIC-4) |
| `-Dtest=T08*Test,T35*Test` | The failure tests T08 and T35 (T35 is PostgreSQL only and is skipped elsewhere) |

## Databases

`it.support.Db` picks the database from `-Dspike.db`. Only one container runs per JVM.

| `spike.db` | Container | Isolation per experiment class | Results file |
|---|---|---|---|
| `postgres` | `postgres:17-alpine` (`Pg`, `max_connections=200`), unchanged from Stage B | one database | `results/<E>.json` |
| `oracle` | `gvenzl/oracle-free:23-slim-faststart` through `simulators` `Databases` | one user/schema, created as SYSTEM | `results/<E>-oracle.json` |
| `sqlserver` | `mssql/server:2022-latest` through `Databases` | one database, `READ_COMMITTED_SNAPSHOT ON` | `results/<E>-sqlserver.json` |
| `mysql` | `mysql:8.4` through `Databases` (utf8mb4_bin, READ-COMMITTED) | one database | `results/<E>-mysql.json` (`-mysql-mariadb-driver` with the MariaDB driver) |

The JDBC drivers for Oracle (ojdbc11), SQL Server (mssql-jdbc) and MySQL (Connector/J, MariaDB Connector/J) are **test scope only** (LIC-2, LIC-4). The PostgreSQL result files of Stage B are never overwritten by another database.

Every product component gets the product-layer `Dialect` of the database (`Env.dialect`). SQL that product-layer `Dialect` does not offer (paging, statistics refresh, plan capture, catalogue lookup) is in `it.support.SqlDialects`, keyed on the `Dialect` name. The lease-expiry sweep uses product-api `LeaseTable.lockExpired` (dialect-aware, A-15) instead of the PostgreSQL-only `FOR UPDATE OF`.

**MySQL is blocked for E2-E5/E7 and T08**: product-layer `Dialect` has no MySQL variant, so the product tables cannot be created. `Env.create` fails fast with that finding, before a container starts, and the class records it as a setup error.

## T08, T35, T28

- **T08** (`it.t08`): a publisher that always fails for one event, against product-layer `Outbox`/`Relay` on the engine transaction. Ordered destination: PARKED after `maxAttempts`, later events of the root blocked, other roots continue, `requeue` (fresh budget) and `skip` unblock it, each audited. Unordered destination: SKIPPED with a `GAP:` record and an `OUTBOX_DELIVERY_SKIPPED` audit entry.
- **T35** (`it.t35`, PostgreSQL only): WF-1 with the inbox and the operation ledger; `pg_dump` inside the container; more messages and payments (`IfmsJitSimulator` class a); restore with `pg_restore`; replay of the newer broker messages; workers re-run. The class Javadoc and `notes.covered` / `notes.notCovered` in `results/T35.json` list exactly what is and is not covered. T35-2 (not counted) measures business-level double payments under a new operationId and engine-id reuse after the restore.
- **T28** (`it.t28.T28UpgradeTest`): the new-version phase of the upgrade test; it runs only with `-Dt28.stateFile` (written by the old-version phase in `t28-old/`, a standalone Maven project with Operaton 2.1.6 or Flowable 7.2.0). Run both phases through `run-queue.ps1` step Q7 (`RUN-QUEUE.md`).

`testFailureIgnore=true`: a failing scenario is a measured result. It is written to the JSON with its error and root cause, and it does not break the build. A compilation error or a crash during setup is still reported by surefire.

## Classpath: both engines in one JVM

Both adapters are test dependencies, and **one surefire execution** runs every class. Each experiment class creates its own database and its own engine, and closes the engine afterwards. **No conflict was observed**:
- Flowable 8.0.0 uses `tools.jackson` 3.0.1 and Operaton 2.2.0 uses `com.fasterxml.jackson` 2.22.1. These are different packages.
- `org.mybatis:mybatis` 3.5.19 resolves once, and both engines run on it.
- Operaton brings `spring-beans` 7.0.10. Flowable never touches it, because no `flowable.cfg.xml` is parsed.

Operaton's `ClockUtil` is JVM-static. That is safe here because classes run one after another and `OperatonEngineAdapter.close()` resets the clock. Do not enable parallel test execution.

If a conflict ever appears, split the module into two surefire executions with `classpathDependencyExcludes` (`org.flowable:*` or `org.operaton.bpm:*`) and `-Dtest=*FlowableTest` or `*OperatonTest`. The test code is already split by engine: `AbstractE*` holds the shared code, and `E*FlowableTest` / `E*OperatonTest` add one line each.

## Layout

| Package | Content |
|---|---|
| `it.support` | Infrastructure for every experiment:<br>- `Pg`: one `postgres:17-alpine` container per JVM, stopped by a shutdown hook; a new database per class.<br>- `Env`: the engine and the product components on the engine transaction (`Transactions.of(engine)`).<br>- `ExperimentBase`, `Scenario` and `ScenarioRecorder`: pass/fail plus measurements to JSON.<br>- `Workflows`: drives the corpus models.<br>- `Ifms`: REST client for the IFMS JIT simulator.<br>- `PaymentWorker`: the WF-1 payment worker and the product's lease-expiry sweep. |
| `it.e2` | Authorization. The `AuthorizationPolicy.visible` predicate runs on `ACT_RU_TASK` through plain JDBC and through each engine's `NativeTaskQuery`, plus a probe of Operaton's built-in authorization |
| `it.e3` | Lease, operationId and unknown outcome, against `IfmsJitSimulator` in modes a/b/c |
| `it.e4` | `IdempotencyStore` around `startProcess`, inside `inEngineTransaction` |
| `it.e5` | Outbox append with `completeTask`, relay, inbox and WF-3 message correlation |
| `it.e7` | Technical delete with tombstones while a payment worker holds the lease |

## Test-side additions (not product code)

- **`IT_WORK_OPERATION(work_id, operation_id, tenant_id, root_id)`** is a test-owned table, written in the worker's intent transaction. The lease-expiry sweep and the technical delete must find the operation of a leased work item. The product schema has no column for that: `OPERATION_LEDGER` has no task/work id. See finding F-3 in the report.
- **The product lease-expiry sweep** is `PaymentWorker.sweepExpiredLeases`. It runs in one engine transaction at the engine clock, before every fetch. It selects the held, expired `SPIKE_LEASE` rows of known operations, then calls `OperationLedger.onLeaseExpired(op, lockVersion + 1)` (A-8: class c → UNKNOWN_OUTCOME and a reconciliation item), then `LeaseTable.expire`.
- **Held work.** Work whose operation is in UNKNOWN_OUTCOME is released with `unlockExternal` and is never dispatched until an officer resolves it. Neither engine has a "held" state for external work (finding F-5).
- **Flowable continuation.** After an external-worker completion, Flowable continues the process through an async job. The tests call `executeDueJobs()` before they check that an instance ended. On Operaton this runs nothing.

## Not covered here

The poison policy (T08) and the restore scenario (T35) of E5 in XI.4 are now classes of their own (above).

E2 runs only the READ_TASK list on runtime tasks. History tables (`ACT_HI_*`, VI.4 rule 8) are not tested.
