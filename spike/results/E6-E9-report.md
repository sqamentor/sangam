# E6 (concurrency) and E9 (functional-scale benchmark): Flowable 8.0.0 vs Operaton 2.2.0

SANGAM.md Part XI §XI.4 (E6: T10/T33; E9: T31). Raw data: `results/E6.json`, `results/E9.json`.
Code: `spike/experiments-load` (test sources only). Run date: 2026-11-04.

> **Every number here is FUNCTIONAL SCALE on an 8-logical-CPU / 32 GB Windows 11 dev machine, with the database
> co-located in Docker (Docker VM: 6 CPUs, 13.6 GiB). NOT the reference scale of R22.**
> Other agents ran their own PostgreSQL / SQL Server containers and builds on the same machine during every run. Machine
> CPU averaged 88–100 % during the E9 measurement windows, while this JVM used only 4–8 % of the machine. The absolute
> numbers are therefore depressed and noisy. The two engines ran back to back under the same conditions, so the
> comparison is more reliable than the absolute values.
> All runs finished before the shared heavy-build lock rule was announced, so they overlapped other lanes. 0 minutes
> were spent waiting for the lock.

## How to run

```
.\mvnw.cmd -q -pl experiments-load verify                                    # fast unit tests only (no Docker)
.\mvnw.cmd -q -pl experiments-load verify -De6.skip=false -De6.dbs=postgres  # E6 on one DB (postgres|oracle|sqlserver, comma list)
.\mvnw.cmd -q -pl experiments-load verify -De6.skip=false                    # E6: PostgreSQL, then Oracle, then SQL Server
.\mvnw.cmd -q -pl experiments-load verify -De9.skip=false                    # E9: one JVM per engine (Flowable, then Operaton)
   optional: -De9.warmupSeconds=20 -De9.steadySeconds=60 -De9.levels=full,audit -De9.workflows=r1,r5
```

Wrap each heavy run in the shared machine lock (`heavy_lock.py acquire customwf "..."` / `release customwf`).
E6 runs both engines in one JVM, one after the other, each in its own database or schema inside ONE container. Each
container is stopped before the next one starts. E9 runs as two surefire executions (one JVM per engine).
LIC-2: `ojdbc11` and `mssql-jdbc` are declared at **test** scope only.

## Databases that actually ran

| DB | Image | Container start-up | Container memory | Ran |
|---|---|---|---|---|
| PostgreSQL 17 | `postgres:17` | 18 s | ~0.1 GiB | E6 + E9 |
| Oracle Free 23 | `gvenzl/oracle-free:23-slim-faststart` | 182 s (283 s on the first attempt) | ~3.9–4.0 GiB | E6 |
| SQL Server 2022 | `mcr.microsoft.com/mssql/server:2022-latest` | 52 s | ~1.2–1.5 GiB | E6 (READ_COMMITTED_SNAPSHOT ON) |

All three ran in acceptable time and memory. One Oracle attempt was aborted and repeated (see blocker B-1).

---

## E6: concurrency on one root

**Design.** Every concurrent command is wrapped in a retry: at most 5 retries, exponential back-off with full jitter,
base 20 ms, cap 1 s (VI.1.1 rule 7). Errors are classified by the VI.1.6 dialect codes: PG 40P01/40001/55P03,
ORA-00060/08177/30006, MSSQL 1205/3960/1222. An engine `*OptimisticLockingException` counts as OLE. A database code
found in the cause chain wins over an OLE wrapper.

- **(a1) one root, 50 parallel user tasks** (fork/join, then a user task "after"), 6 roots, all tasks of a root
  completed at once.
- **(a2) one root, 32 parallel message catch events** (fork/join), 10 roots, all correlated at once.
- (a1) and (a2) each ran with 16 and with 32 threads.
- **(b) WF-2 join storm**: 100 instances driven to the comment fork. Both comment tasks are completed at the same
  instant (a barrier per pair), 32 threads (16 pairs in flight).
- **Lost-update check**:
  1. After the concurrent phase, every variable written by a command that reported success must be present.
  2. Commands that failed after their retries are then re-run sequentially ("drain"). They are not counted as
     successes.
  3. Then the activity after the join must have run exactly once (runtime task + ACT_HI_ACTINST).
  4. No branch or message subscription may be left.
  5. The instance must have ended exactly once (runtime, ACT_HI_PROCINST, end event in ACT_HI_ACTINST).
- **History level** is as configured by the adapters: Flowable audit (the engine default), Operaton FULL.
- 1,440 operations per engine × DB.

### E6 summary (engine × DB, all scenarios)

| DB | Engine | Ops | Successes | OLE | Deadlocks | Other errors | Retries | Unrecovered after 5 retries | Lost updates | Concurrent wall time |
|---|---|---|---|---|---|---|---|---|---|---|
| PostgreSQL | Flowable | 1,440 | 929 | 4,957 | 0 | 0 | 4,446 | 511 | **0** | 68.3 s |
| PostgreSQL | Operaton | 1,440 | 881 | 5,162 | 0 | 0 | 4,603 | 559 | **0** | 68.9 s |
| Oracle Free | Flowable | 1,440 | 863 | 5,249 | 0 | 0 | 4,672 | 577 | **0** | 131.6 s |
| Oracle Free | Operaton | 1,440 | 877 | 5,136 | 0 | 0 | 4,573 | 563 | **0** | 174.8 s |
| SQL Server | Flowable | 1,440 | 736 | 5,744 | 0 | 0 | 5,040 | 704 | **0** | 113.8 s |
| SQL Server | Operaton | 1,440 | 682 | 5,747 | 0 | 0 | 4,989 | 758 | **0** | 245.1 s |

No serialization failures or lock timeouts were recorded on any DB. Every exception was an engine optimistic-lock
exception. Drain failures were 0, so every final-state check was conclusive.

### Per scenario (successes / OLE / unrecovered; lost updates = 0 in every row)

| Scenario | PG Flowable | PG Operaton | Oracle Flowable | Oracle Operaton | MSSQL Flowable | MSSQL Operaton |
|---|---|---|---|---|---|---|
| a1 50 tasks, 16 threads (300 ops) | 176 / 1,158 / 124 | 185 / 1,070 / 115 | 187 / 1,091 / 113 | 176 / 1,125 / 124 | 150 / 1,275 / 150 | 143 / 1,245 / 157 |
| a2 32 messages, 16 threads (320) | 226 / 1,044 / 94 | 203 / 1,139 / 117 | 204 / 1,181 / 116 | 208 / 1,115 / 112 | 187 / 1,258 / 133 | 161 / 1,309 / 159 |
| a1 50 tasks, 32 threads (300) | 135 / 1,352 / 165 | 133 / 1,357 / 167 | 133 / 1,353 / 167 | 135 / 1,327 / 165 | 99 / 1,485 / 201 | 97 / 1,428 / 203 |
| a2 32 messages, 32 threads (320) | 192 / 1,303 / 128 | 160 / 1,496 / 160 | 139 / 1,524 / 181 | 158 / 1,469 / 162 | 100 / 1,626 / 220 | 81 / 1,665 / 239 |
| b WF-2 join storm (200) | 200 / 100 / 0 | 200 / 100 / 0 | 200 / 100 / 0 | 200 / 100 / 0 | 200 / 100 / 0 | 200 / 100 / 0 |
| Wall time (a1 at 32 threads) | 15.1 s | 17.0 s | 25.2 s | 32.3 s | 27.3 s | 44.1 s |

### What E6 shows

1. **No lost updates and no deadlocks on any database, for either engine.**
   - Both engines serialize all commands on one root with an optimistic version on the process-instance/execution
     row:
     - Flowable: `ProcessInstance[..] was updated by another transaction concurrently`.
     - Operaton: `ENGINE-03005 UPDATE ExecutionEntity[..]`.
   - Every parallel join fired exactly once, and every instance ended exactly once.
   - In the WF-2 storm, exactly one of the two branch completions lost the race in each of the 100 pairs. It
     succeeded on its first retry (100 OLE, 100 retries, 0 failures) on all six engine × DB combinations.
2. **The pass criterion as stated (0 unrecovered after retries) FAILS for both engines on all three DBs** in the
   hot-root scenarios (a1/a2):
   - With 16–32 commands racing on one root, 30–75 % of commands still fail after 5 retries capped at 1 s.
   - The one-root rate that committed was only about 2–10 commands/s (e.g. a1 at 32 threads: PG 9.0 / 7.8 per s,
     Oracle 5.3 / 4.2 per s, MSSQL 3.6 / 2.2 per s, Flowable / Operaton).
   - That is well below the ~50/s hot-root limit that VI.1.1 rule 8 assumes. This rate was measured on a saturated
     host.
   - Under the VI.1.2 reading of T33 ("every command either commits or returns 409 after its retries"), the
     unrecovered commands are clean `409 CONFLICT_RETRY_EXHAUSTED` cases:
     - every failure rolled back completely;
     - every one succeeded when re-run;
     - no partial state.
   - So T33 holds for consistency, but not for "all commands succeed within 5 retries".
   - Neither engine has the L2 root lock of VI.1. Contention is resolved by OLE plus retry, which wastes about 10–20
     failed attempts per successful command at 32 threads (OLE ÷ successes, a1/a2).
3. **Engine comparison.** The raw OLE and retry counts are almost the same for the two engines on every DB (within
   ~10 %).
   - Operaton needed more wall time on Oracle (1.3×) and SQL Server (2.2×). It also had more unrecovered commands on
     SQL Server (758 vs 704).
   - Flowable had slightly more unrecovered commands on Oracle (577 vs 563). The two engines were about equal on
     PostgreSQL.
   - The difference on Oracle and SQL Server is consistent with Operaton writing FULL history against Flowable's
     audit history (more rows per command). The levels were not equalised in E6.

---

## E9: functional-scale benchmark (PostgreSQL only)

**Design.**
- Fixed threads for both engines:
  - 4 starters;
  - 8 synthetic actors (R5);
  - 1 fetcher (fetchAndLock up to 10, lease 60 s) feeding 4 completer threads;
  - at most 200 open instances; DB pool 24.
- Each run: 20 s warm-up, then a 60 s steady window. Only the window is measured.
- **Completed instances/s** = instances whose `ACT_HI_PROCINST.END_TIME_` was set inside the window (sampled from the
  DB at the window edges), not starts.
- Command latency includes retries.
- **Error rate** = commands that were still failing after 5 retries / all commands in the window.
- A fresh database is used per (engine, workflow, level).
- **History level:** each workflow ran at FULL and at AUDIT on both engines. The level was set on the built engine by
  a test hook. Its effect was verified: ACT_HI_DETAIL has rows only at FULL.
- Flowable's async executor was started for E9. Flowable continues the process after an external-worker completion
  in an async `external-worker-complete` job, and the adapter builds the engine with the async executor off.
- **Not in the path:** the product layer (authorization filter, outbox, idempotency, audit chain). These are adapter
  plus engine numbers only.

**Workflows.**
- **WF-R1:** start → one external work item (fetch, lock, complete) → end.
- **WF-R5-lite:** the WF-1 corpus model:
  - start with 14 variables (10 × 64-character payload);
  - checker user task;
  - DMN delegation of powers (4 amount bands);
  - approve user task (both completed by a synthetic actor);
  - payment external work.

### E9 results

| Workflow | History | Engine | **Completed inst/s** | Started/s | Commands/s | p50 / p95 / p99 command latency (ms) | Error rate | OLE | JVM CPU % of machine | DB container CPU (% of 1 core) | Machine CPU % |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R1 | full | Flowable | **22.7** | 22.6 | 47.5 | 72 / 238 / 512 | 0 | 0 | 7.5 | 146 | 98 |
| R1 | full | Operaton | **33.9** | 33.9 | 71.1 | 96 / 232 / 359 | 0 | 0 | 6.9 | 142 | 100 |
| R1 | audit | Flowable | **23.9** | 23.9 | 50.0 | 60 / 216 / 483 | 0 | 0 | 5.6 | 155 | 100 |
| R1 | audit | Operaton | **36.4** | 36.5 | 76.5 | 83 / 234 / 342 | 0 | 0 | 4.2 | 96 | 100 |
| R5-lite | full | Flowable | **11.6** | 11.5 | 70.6 | 146 / 427 / 741 | 0 | 0 | 7.8 | 229 | 100 |
| R5-lite | full | Operaton | **11.4** | 11.7 | 78.5 | 156 / 386 / 556 | 0 | 0 | 6.8 | 201 | 100 |
| R5-lite | audit | Flowable | **11.0** | 11.0 | 66.6 | 143 / 441 / 769 | 0 | 0 | 7.0 | 179 | 100 |
| R5-lite | audit | Operaton | **11.5** | 11.5 | 77.1 | 155 / 381 / 500 | 0 | 0 | 4.8 | 144 | 100 |

After the drain, every started instance had ended in every run (history matches the driver).

Per-command p50 / p99 (ms), R5-lite at full history:

| Command | Flowable | Operaton |
|---|---|---|
| startProcess | 169 / 497 | 192 / 573 |
| queryTasks | 52 / 177 | 83 / 260 |
| completeTask (incl. DMN on the first) | 276 / 833 | 241 / 632 |
| fetchAndLock | 307 / 956 | 75 / 333 |
| completeExternal | 122 / 319 | 201 / 591 |

### What E9 shows

- **WF-R1:** Operaton completed about 1.5× more instances/s than Flowable (34–36 vs 23–24) and had a lower p99 (342–359
  vs 483–512 ms).
  - The main cost on Flowable is `fetchAndLock` (p50 ~420 ms vs ~240 ms).
  - The Flowable adapter resets expired jobs before every fetch by listing ALL external-worker jobs (LEDGER-F3) in
    a separate command. That is adapter overhead, not necessarily the engine.
  - For Flowable, the instance ends in the asynchronous continuation job, so its `completeExternal` latency does not
    include the process end.
- **WF-R5-lite:** throughput is the same within noise (11.0–11.6/s).
  - This rate is bounded by the closed-loop driver: 8 actors × 4 commands, 200 open instances. The end-to-end time
    of ~16 s is consistent with Little's law (200 / 11.5 per s). It is not an engine limit.
  - Operaton had the better tail: p99 500–556 vs 741–769 ms.
  - Flowable had faster task queries and external completion. Operaton had faster fetch and user-task completion.
- **History FULL vs AUDIT** changed throughput by less than 8 % for either engine at this scale.
- **CPU:** the host was saturated by other workloads, so neither engine was CPU-bound in its own JVM (4–8 %). The
  co-located PostgreSQL container used 1–2.3 cores. These figures cannot be extrapolated.

### Preliminary "benchmark" bucket (10 % of the D-1 score)

**PRELIMINARY.** This is based on functional-scale runs on a saturated, shared dev host. It is NOT R22 reference scale.
Confidence is low.

Scoring method, proposed here (each sub-score = engine ÷ best, R5 weighted highest as XI.4 names WF-R5):

- 50 % R5-lite completed inst/s (mean of the full and audit runs);
- 30 % R5-lite p99 command latency (inverse);
- 20 % R1 completed inst/s.

| Sub-score | Flowable | Operaton |
|---|---|---|
| R5-lite inst/s (11.29 vs 11.44) | 0.99 | 1.00 |
| R5-lite p99 (755 vs 528 ms) | 0.70 | 1.00 |
| R1 inst/s (23.3 vs 35.2) | 0.66 | 1.00 |
| **Weighted** | **0.84 → 8.4 / 10** | **1.00 → 10.0 / 10** |

Re-measure on the R22 reference hardware (16 + 16 vCPU) on an idle host before using this in ADR-D-1. Fix B-2 and B-3
first.

---

## E6 as failure-test evidence (20 % bucket, E2–E8)

| Test | Flowable (PG / Oracle / MSSQL) | Operaton (PG / Oracle / MSSQL) |
|---|---|---|
| T10 / T33: 0 lost updates, join exactly once, instance ends exactly once | PASS / PASS / PASS | PASS / PASS / PASS |
| T33: 0 deadlocks | PASS / PASS / PASS | PASS / PASS / PASS |
| 0 unrecovered failures after 5 retries (hot root, 16–32 threads) | FAIL / FAIL / FAIL (511 / 577 / 704 of 1,440) | FAIL / FAIL / FAIL (559 / 563 / 758 of 1,440) |
| WF-2 parallel-join storm, 100 pairs | PASS on all three DBs (100 OLE, all recovered on the 1st retry) | PASS on all three DBs (same) |
| T34 (lock_timeout chaos) | not run | not run |

The two engines are not distinguishable on correctness. Both fail the retry-budget criterion in the same way: there is
no root lock (L2), only OLE. Operaton is slower under contention on Oracle and SQL Server.

---

## Blockers and findings (outside `experiments-load`; reported, not changed)

- **B-1 (product-api `LeaseTable`, blocker for Oracle and SQL Server).** The `spike_lease` SQL is PostgreSQL/H2 only:
  - `bigint` columns: ORA-00902 on Oracle;
  - `create table if not exists` and `select … for update`: invalid T-SQL.
  - Both adapters call `LeaseTable.createIfMissing` in their constructors, so **neither adapter can be constructed on
    Oracle or SQL Server**.
  - Workaround for E6: a JDBC proxy in the test harness (`DbSetup.leaseShim`) rewrites only those statements:
    - Oracle: `NUMBER(19)`;
    - SQL Server: `IF OBJECT_ID(..) IS NULL CREATE TABLE`, `WITH (UPDLOCK, ROWLOCK)`.
  - It needed 1–2 rewrites per engine. E6 does not use leases beyond construction.
- **B-2 (Operaton adapter, concurrent `fetchAndLock`).**
  - **Symptom.** In a trial with 4 concurrent fetchers (5 s warm-up, 15 s window), 17.4 % of R1 commands and 9.1 % of
    R5 commands failed on Operaton. 30 R1 and 28 R5 instances never ended. The errors:
    - duplicate key `spike_lease_pkey`;
    - `StaleLeaseException STALE_VERSION`;
    - "External Task … locked by worker-0";
    - `NotFoundException`.
  - **Likely cause.** Two fetch commands get the same task. Operaton's fetch tolerates optimistic-lock conflicts on
    the external-task row, while the adapter grants the SPIKE_LEASE row in the same command.
  - **Flowable.** It raised OLEs, all of which were recovered by retry, with 0 errors.
  - **Effect on the measured E9.** It uses one fetcher on both engines.
  - Data: `notes` in `E9.json`.
- **B-3 (Flowable adapter `correlateMessage`).** It filters executions by `processInstanceBusinessKey`, which matches
  no message catch event inside a parallel branch ("matched 0 waiting executions"; `adapterCorrelateMessageOnParallelBranch`
  in `E6.json`). E6 on Flowable correlates through `RuntimeService` by process instance id instead (one command).
  Operaton's adapter works.
- **F-1 (Flowable adapter).** The engine is built with the async executor off. External-worker completions then never
  end the instance unless something runs the `external-worker-complete` jobs (`executeDueJobs` or the async executor).
  An early E9 attempt showed 0 ended instances because of this.
- **F-2 (Flowable adapter `fetchAndLock`).** It lists all external-worker jobs on every fetch (expired-lease reset,
  LEDGER-F3). That is O(backlog) per fetch and is the largest Flowable cost in R1.
- **F-3 (history levels).** The adapters differ (Flowable audit by default, Operaton FULL). E6 used the adapters'
  levels. E9 equalised the levels with a runtime test hook. The levels should be made configurable in the adapters.
- **F-4 (host contention).** Other agents' containers and builds saturated the CPU during the runs. This was known
  only partly before the lock rule. Repeat E9 (and the E6 wall times) under the heavy-build lock on an idle host.
