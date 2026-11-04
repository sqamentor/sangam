# Run queue: the heavy work left after Stage B

`spike/run-queue.ps1` runs every queued heavy step **in order**, each inside the machine's shared heavy-build lock. It
closes the "not yet tested" list of SANGAM.md XI.10.4 for **both engines** (Operaton 2.2.0 and Flowable 8.0.0), so the
ADR-D-1 comparison stays complete and fair, on the four Tier-1 databases (PostgreSQL, Oracle, SQL Server, MySQL).

## How to run

From the `Custom WorkFlow` folder (or anywhere, the script finds `spike/` itself):

```
powershell -File spike\run-queue.ps1                  # all steps, Q1..Q7
powershell -File spike\run-queue.ps1 -Only Q2,Q4      # selected steps, still in queue order
powershell -File spike\run-queue.ps1 -Only Q3.oracle  # one sub-step
```

Prerequisites: Docker running; JDK 21 as the default `JAVA_HOME`; every module installed
(`.\mvnw.cmd -q -DskipTests install` from `spike/`); Python for the lock script; JDK 25 at
`%USERPROFILE%\tools\jdk25\jdk-25.0.5.0.2+1` for Q5.

Rules the script follows:
- **Lock.** Before each (sub-)step: `python …\heavy_lock.py acquire customwf "<step>"`; it **waits** for the lock and never
  breaks it. `release customwf` runs in a `finally`. A step whose acquire fails is skipped and recorded.
- **Failures do not stop the queue.** Every exit code is logged; the next step runs.
- **One container at a time.** Each step's JVM stops its own Testcontainers database (shutdown hook / Ryuk). After each
  step the script waits up to 2 minutes for the containers that appeared during the step to go away and removes
  leftovers of that step only. Q7 starts and removes its own `customwf-t28-pg` container.
- **JDK 25 only for Q5.** `JAVA_HOME` and `PATH` are set for the child Maven processes of Q5 and restored afterwards;
  nothing is changed system-wide.

## Where results go

| What | Where |
|---|---|
| Queue log (timestamps, lock, commands, exit codes, CPU, summary) | `spike/results/run-queue.log` |
| Maven output per step | `spike/results/run-queue/<step>.out.log`, `<step>.err.log` |
| E1 on another database | `spike/results/E1-oracle.json`, `E1-sqlserver.json`, `E1-mysql.json`, `E1-mysql-mariadb-driver.json` |
| E2–E5, E7 on another database | `spike/results/E2-oracle.json` … `E7-mysql.json` |
| T08, T35 | `spike/results/T08.json`, `T35.json` |
| T28 | `spike/results/T28.json` (one entry per engine and version pair) |
| E9 | `spike/results/E9.json` (merged per run, as in Stage B) |
| Adapters, JDK 25 | surefire reports of the modules + the log lines `JDK 25 <module>: PASS/FAIL` |

The PostgreSQL result files of Stage B (`E1.json` … `E7.json`) are not overwritten by the Oracle/SQL Server/MySQL runs.
In `experiments-integration` a failing scenario is a measured result (`testFailureIgnore=true`): exit code 0 does not
mean every scenario passed. Read the JSON.

## Steps

| Step | Command (per sub-step) | What it proves | Links | Expected duration |
|---|---|---|---|---|
| **Q1** adapters | `adapter-operaton` verify `-Doperaton.dbs=postgres,oracle,sqlserver,mysql`, then `adapter-flowable` verify (`-Dflowable.dbs=…`) | Both adapters' 26/28 contract tests on every Tier-1 database, incl. the per-dialect lease SQL. **Skipped with a log line** when the module's patch ledger already records a passing verify on all four databases | A-15, R17 | 60–100 min (Oracle start ~3 min per run) |
| **Q2** E1 | `experiments` verify `-Dspike.db=<db> -Dtest=E1CorpusCoverageTest`, E8 skipped; oracle, sqlserver, mysql, then mysql with the MariaDB driver | The corpus (WF-1, WF-2, WF-3, CMMN) runs unpatched on Oracle, SQL Server and MySQL on both engines; whether both engines work with the MariaDB Connector/J against MySQL 8.4 | XI.10.4 "Oracle/SQL Server for E1", LIC-4 | 40–70 min |
| **Q3** E2–E5, E7 | `experiments-integration` verify `-Dspike.db=<db> -Dtest=E2*Test,…,E7*Test`; oracle, sqlserver, mysql | Authorization predicate (with the dialect's bit-and and paging), lease/operationId, idempotency, outbox/inbox and delete on each database, both engines. **MySQL fails fast** (product-layer `Dialect` has no MySQL variant): that failure is the recorded finding | A-11, A-13, A-15, A-16, R19 | 70–110 min |
| **Q4** T08 + T35 | `experiments-integration` verify `-Dspike.db=postgres -Dtest=T08*Test,T35*Test` | **T08**: poison event PARKED (ordered, blocks only its root, requeue/skip audited) or SKIPPED with a gap record (unordered). **T35**: after a restore with newer broker messages, inbox dedup + operationIds prevent duplicate starts and duplicate payments by operationId; T35-2 measures what that does **not** cover (a lost start restarts with a new operationId) | T08, T35, R38, VI.2 | 5–10 min |
| **Q5** JDK 25 | `install` (tests included) of `product-api`, `corpus`, `product-layer`, `simulators`, `adapter-operaton`, `adapter-flowable`, `experiments` on PostgreSQL, JDK 25 for these processes only | Build + tests on JDK 25 per module, PASS/FAIL per module in the log | R5, E10 "JDK 25 not tested" | 30–50 min |
| **Q6** E9 | waits until the 2-min average CPU (`Get-Counter`) is < 25 % (up to 3 h), takes the lock, re-checks, then `experiments-load` `test-compile surefire:test@e9-flowable` and `@e9-operaton` back to back with the same flags (`-De9.skip=false -De9.warmupSeconds=20 -De9.steadySeconds=60 -De9.levels=full,audit -De9.workflows=r1,r5`); before the second engine the CPU must again be < 25 % | Functional benchmark R1 / R5-lite for both engines under the same quiet-machine condition; CPU at start and end recorded per engine | R22 | wait ≤ 3 h + ~20–30 min |
| **Q7** T28 | per engine: start `postgres:17-alpine` (`customwf-t28-pg`, port 55432); **old phase** `experiments-integration/t28-old` (`-Dt28.engine=operaton` with Operaton 2.1.6, or `flowable` with Flowable 7.2.0) creates the schema and leaves WF-1 instances at checker review, approval and the payment external task; **new phase** `T28UpgradeTest` (2.2.0 / 8.0.0) upgrades the schema and finishes them; container removed | Upgrade with instances in flight on each engine's own mechanism. If `databaseSchemaUpdate=true` does not move the schema version, the SQL scripts shipped in the engine jar are applied by hand and recorded as the finding "upgrade needs manual SQL" (nothing is skipped) | T28, R32 | 10–20 min |

Total without the Q6 wait: about 4–6.5 hours.

## What the queue cannot settle by itself

- **MySQL for the product layer (Q3):** needs a `MYSQL` variant of product-layer `Dialect` (skip-locked claim,
  `FOR UPDATE`, insert-if-absent with error 1062, types, `NOW(6)`); E2–E5/E7/T08 then run unchanged (`SqlDialects`
  already handles MySQL paging and catalogue queries).
- **MySQL for the adapters (Q1):** product-api `LeaseTable` covers PostgreSQL, Oracle, SQL Server and H2; on MySQL it
  falls back to generic SQL. A failure there is recorded as "A-15 incomplete for MySQL"; the adapter test suites also
  need a MySQL test class and the driver at test scope (adapter owners).
- **adapter-flowable DB selection (Q1, Q5):** the queue passes `-Dflowable.dbs=` (and `-Dspike.dbs=`); the adapter's
  tests must honour one of them.
