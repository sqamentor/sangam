# E2, E3, E4, E5, E7: integration experiments on both engines

| | |
|---|---|
| **Date** | 2026-11-04 |
| **Module** | `spike/experiments-integration` (read its README for how the code is set up) |
| **Raw results** | `E2.json`, `E3.json`, `E4.json`, `E5.json`, `E7.json` in this folder |
| **Engines** | Flowable 8.0.0 and Operaton 2.2.0, both embedded, no Spring, through `EngineAdapter` |
| **Database** | PostgreSQL 17 (Testcontainers `postgres:17-alpine`). Each engine has its own database. The product tables sit in the same database, so they share the engine's transaction |
| **Host** | Dev machine: Windows 11, 8 vCPU, 32 GB, JDK 21. Other agents were running Testcontainers loads at the same time, so latency numbers are indicative |
| **Command** | `.\mvnw.cmd -q -pl experiments-integration verify`. The supplementary E2 run at 10,000 tasks adds `-Dtest=E2*Test -De2.tasks=10000` |

## 1. Summary

**Counted scenarios** (informational probes excluded):

| Experiment | Flowable 8.0.0 | Operaton 2.2.0 | Tests |
|---|---|---|---|
| E2 Authorization equivalence (2,000 tasks × 300 groups) | **7 / 7** | **7 / 7** | T13, T14, T15, T37, T38 (reduced) |
| E3 Lease + operationId + unknown outcome | **6 / 6** | **6 / 6** | T02, T03, T04, T36 |
| E4 Idempotency atomic with engine state | **6 / 6** | **6 / 6** | T01, T55 (subset), T56, T57, T60 (subset) |
| E5 Outbox / inbox with engine state | **4 / 5** | **5 / 5** | T06, T07, T09 |
| E7 Cancel/delete with external work in flight | **3 / 3** | **3 / 3** | T11, T39 |
| **Total** | **26 / 27 (96.3 %)** | **27 / 27 (100 %)** | |
| *Supplementary: E2 at 10,000 tasks × 300 groups* | *6 / 7* | *6 / 7* | *T38 at spec scale; not in the totals* |

**The only counted failure is E5-5 on Flowable, caused by an adapter defect, not an engine limit.**
- The Flowable adapter's `correlateMessage` cannot find a message catch event that runs in a child execution when correlating by business key (finding F-1).
- With the business key bypassed (probe E5-5w), the same inbox flow passes on Flowable.
- A one-line adapter fix should turn E5-5 green. That fix has not been made or tested, because the adapter is outside this agent's scope.

**Preliminary "failure-test pass rate" bucket (20 %), over E2–E5 and E7 only:**

| | Flowable | Operaton |
|---|---|---|
| Pass rate (counted scenarios) | 96.3 % | 100 % |
| Bucket points (20 × rate) | **19.3** | **20.0** |
| If the F-1 fix is confirmed | 20.0 | 20.0 |

This is preliminary:
- E6 and E8 are not in it.
- Scenario counts are unweighted.
- The supplementary 10k E2 run, where the count query is too slow on both engines (see §2.3), is not in it.

## 2. E2: authorization equivalence over the engine's own task table

### 2.1 Setup

| Item | Value |
|---|---|
| Tasks | Real engine tasks. 2,000 starts of WF-1 and WF-2, alternating, over 4 tenants with a 45/25/20/10 % skew. The supplementary run uses 10,000 |
| Predicate | `AuthorizationPolicy.visible(principal, READ_TASK, alias)`, compiled on `ACT_RU_TASK` (`TENANT_ID_`, `ID_`). The table and column names are the same on both engines |
| Read path | Plain JDBC list/count, with no post-filtering |
| `AUTH_EFFECTIVE` contents | About 4,900 rows per random policy: 300 groups, wildcard grants, specific grants with and without the READ_TASK bit, specific and wildcard denies, conditional grants and denies, user-level grants and denies |
| Random policies | 3 seeds × 8 principals with 0, 1, 3, 10, 30, 50, 100 and 300 groups |
| Oracles | (1) the object check `allow()` for every task; (2) an independent in-memory model of A-3 |

### 2.2 Results (identical semantics on both engines)

| Scenario | Flowable | Operaton | Measured |
|---|---|---|---|
| E2-1 random policies: `list == {t : allow(t)} == model`, count equal | PASS | PASS | 24 principals; 8,230 visible task rows checked; **0 mismatches** (41,093 rows at 10k, also 0) |
| E2-2 no post-filtering: pages of 50 are full and concatenate to the full list | PASS | PASS | 51 pages (235 at 10k) |
| E2-3 deny wins (A-3) | PASS | PASS | Six cases, all as specified:<br>- a group grant `*` plus another group's deny on x hides x;<br>- a user grant on x plus a group deny on x hides x;<br>- a specific grant plus a deny on `*` shows nothing;<br>- a deny of COMPLETE does not hide READ_TASK;<br>- a conditional grant never matches;<br>- a conditional deny always applies. |
| E2-4 tenant binding | PASS | PASS | 485 tn-b tasks were covered by a grant with the same group id in tn-b; 0 leaked to a tn-a principal. A request tenant `tn-b` gives 0 rows |
| E2-5 revocation without cache | PASS | PASS | policy_version 1→2→3→4; each change applied on the next query |
| E2-6 **the same predicate through the engine's own `NativeTaskQuery`** | PASS | PASS | Same list and count as the model for 8 principals; no engine patch |
| E2-7 p95 latency, 300-group principal (301 ids), 2,000 tasks | PASS | PASS | See §2.3 |
| E2-8 probe of the engine's own TaskQuery and authorization (not counted) | ran | ran | See §2.4 (Q2) |

### 2.3 Latency

Conditions: 300 measured runs after 30 warm-up runs; warm cache; one client; `IN` list of 301 ids (VI.4 rule 2 asks for a membership-table join above 200 ids; the spike does not do that).

| Query | Flowable, 2k tasks | Operaton, 2k tasks | Flowable, **10k tasks** | Operaton, **10k tasks** |
|---|---|---|---|---|
| List page of 50, plain SQL: p50 / p95 / p99 (ms) | 12 / **27** / 45 | 32 / **55** / 81 | 44 / **86** / 157 | 19 / **34** / 43 |
| Count, plain SQL: p50 / p95 / p99 (ms) | 36 / **96** / 109 | 75 / **125** / 142 | 357 / **550** / 648 | 165 / **386** / 449 |
| List page of 50 via `NativeTaskQuery`: p95 (ms) | 28 | 78 | 180 | 63 |
| Visible tasks for this principal | 812 | 812 | 4,396 | 4,396 |

What this shows:
- **XI.4 criterion "10k tasks × 300 groups, p95 list < 200 ms": met on both engines at 10k** for the list page (86 ms and 34 ms).
- **The count query misses 200 ms at 10k on both engines** (550 ms and 386 ms). The test asserts on count as well, so the supplementary runs score 6/7.
- The count query evaluates the `EXISTS` / `NOT EXISTS` pair for every task row of the tenant, about 4.5k rows. The plan shows nested-loop semi/anti joins with an `OR` on `resource_id` (`= r.id_ OR = '*'`), which prevents a clean index lookup.
- Likely remedies, not tried:
  - split the wildcard and specific branches (`UNION` or two `EXISTS`);
  - materialise per-principal wildcard flags;
  - use the membership-table join above 200 ids;
  - use estimated counts for very large lists.
- The difference between the engines is mostly the shape of `ACT_RU_TASK` and its indexes, plus noise from the shared host. It does not come from engine code, because both runs use the same SQL.

### 2.4 Q2: can the predicate be applied without engine patches, and can the engine's TaskQuery take it?

| | Flowable 8.0.0 | Operaton 2.2.0 |
|---|---|---|
| Predicate on the engine task table via plain SQL, no patch | **Yes** (E2-1…E2-7) | **Yes** (E2-1…E2-7) |
| Engine `TaskQuery` API can take the predicate | **No.** There is no hook for an extra clause; it offers only candidate user/group(In), involved user and tenant filters | **No.** Same, plus OR-queries |
| Engine native query API can take it | **Yes:** `TaskService.createNativeTaskQuery().sql(..).parameter(..)` with `#{p0..}` parameters. It returns `Task` entities and counts, with identical results | **Yes:** the same API, identical results. Native queries bypass the engine's own authorization |
| Built-in authorization | **None.** No authorization service and no `ACT_*AUTH*` table (checked). Access is only identity links (candidate/assignee). The engine's candidate-group count (932) differs from the policy count (812): it is a different model with no deny | **Yes:** `AuthorizationService`, `ACT_RU_AUTHORIZATION`, GRANT/REVOKE/GLOBAL. Probe results:<br>(A) group grant `*` + another group's REVOKE on x → x **hidden** (consistent with A-3);<br>(B) user GRANT x + group REVOKE x → x **visible** (**violates A-3**);<br>(C) group GRANT x + group REVOKE `*` → x **visible** (differs from the product's NOT EXISTS rule).<br>An authorized TaskQuery with 300 groups: page p95 57 ms at 2k tasks and 73 ms at 10k |
| What it would need | Product query builder over `ACT_RU_TASK` / `ACT_HI_*`: plain SQL, `NativeTaskQuery`, or a custom MyBatis mapper. No engine patch | Built-in authorization cannot carry A-3 as shipped. It applies user-over-group precedence and lets a specific grant beat a broader revoke, and it has none of the product's permission bits, conditions or tenant binding. Keep it off, as the adapter does, and use the same product read path as on Flowable. No engine patch |

**Answer to Q2: yes on both engines, with zero patches, through plain SQL or the engine's `NativeTaskQuery`. Neither engine's `TaskQuery` API can take the filter. Operaton's built-in authorization is not A-3-equivalent, so it gives Operaton no real advantage for E2.**

## 3. E3: lease, operationId and unknown outcome

Setup:
- WF-1 is driven to `wf1_payment`.
- The worker (`PaymentWorker`) records the intent in `OPERATION_LEDGER`, calls the IFMS JIT simulator's PI with the operationId as idempotency key, then records the outcome and calls `completeExternal` in one engine transaction.
- Leases last 60 s on the engine clock, which the test moves forward.
- A product lease-expiry sweep applies A-8 before each fetch.

| Scenario | Flowable | Operaton | Measured (identical on both unless noted) |
|---|---|---|---|
| E3-i-a crash after a successful PI; lease expires; re-fetch; class (a) | PASS | PASS | lockVersion 1 → 3; new attemptId; **same operationId**; second PI answered `duplicate:true`; **IFMS payments = 1**; ledger SUCCEEDED, 2 attempts |
| E3-i-b same crash, class (b) | PASS | PASS | The retry looked the outcome up (PD) and sent no second PI; payments = 1 |
| E3-ii-c undeclared topic (A-8 default c), IFMS in mode c | PASS | PASS | At expiry: UNKNOWN_OUTCOME and a reconciliation item. Two later polls were **held without dispatch**. officer-17 resolved it with CONFIRM_SUCCESS (`RECONCILIATION_ACTION` audit). The work was then completed from the stored outcome; payments = 1 |
| E3-iii stale worker after re-acquire | PASS | PASS | 7 stale calls rejected (ledger 409 or adapter `STALE_VERSION` / `NO_LEASE`), including complete, extend, fail, unlock, and the old version used by the new owner. Engine state unchanged; the current holder completed |
| E3-iv network cut of 2× the lease while worker-1's PI is in flight | PASS | PASS | No redispatch before expiry (T04). Worker-2 completed with the **same operationId**. Worker-1's late PI returned `duplicate:true` and its completion got 409. **Exactly 1 completion** in `ACT_HI_ACTINST`; payments = 1 |
| E3-sum effects ledger | PASS | PASS | 7 payments; the only duplicate is the deliberate control |
| E3-ctl control (not counted): class-(c) IFMS with the topic wrongly declared (a) | ran | ran | Pays twice. This shows the duplicate check works and why A-8 defaults to (c) |
| E3-hz probe (not counted): adapter-internal lease expiry before the product sweep | **hazard confirmed** | **hazard confirmed** | `executeDueJobs()` ended the lease inside the adapter. The product sweep then found nothing, the class-(c) work was re-dispatched, and **IFMS paid twice** (finding F-4) |

Engine difference: on Flowable each completion needs one async job before the instance moves on (`asyncJobsAfterCompletion` = 1 per completion). On Operaton the continuation is synchronous. Known as patch-ledger-flowable note; finding F-2.

## 4. E4: idempotency atomic with engine state

The start command is `startProcess("wf2-file")`, wrapped in `IdempotencyStore`:
- Step 1, the claim, runs in its own engine transaction.
- Step 2 runs `startProcess` and `complete()` (the last write) in **one** `inEngineTransaction`.

| Scenario | Flowable | Operaton | Measured |
|---|---|---|---|
| E4-1 duplicate start returns the same instance | PASS | PASS | 201, then a 201 replay with the same id; 1 instance |
| E4-2 conflicting reuse | PASS | PASS | Different payload → 422 `IDEMPOTENCY_KEY_REUSED`; different principal → 422 `..._PRINCIPAL_MISMATCH`; 1 instance |
| E4-3 concurrent duplicates with 2, 4 and 8 threads | PASS | PASS | Exactly 1 instance at each level; every caller got the same id. The other callers got `409 IN_PROGRESS` followed by a replay (8 threads: 1 × 201, 7 replays, 7–9 × 409) |
| E4-4 exception after the engine start, inside the transaction | PASS | PASS | 0 instances (runtime and history). The record stays IN_PROGRESS: the claim row is from step 1, and COMPLETED was rolled back. After `abandon` the record is absent; the retry created 1 instance |
| E4-5 crash before commit, no cleanup | PASS | PASS | 409 while the claim is live. After the 700 ms TTL, takeover (epoch 2) executed once. The zombie's late commit got `ClaimLostException` and its engine start rolled back; 1 instance |
| E4-6 crash after commit, before the response | PASS | PASS | The retry replayed the same instance |

Clarification on E4-4:
- "No idempotency record" holds for the COMPLETED record only.
- The step-1 `IN_PROGRESS` claim row survives until `abandon` or claim expiry. This is by design in X.2.2.4, where the claim is a separate transaction. It is not a defect.

## 5. E5: outbox and inbox with engine state

| Scenario | Flowable | Operaton | Measured |
|---|---|---|---|
| E5-1 append in the same transaction as `completeTask`; relay publishes | PASS | PASS | root_seq 1; 2 destinations claimed, published and DONE |
| E5-2 relay crash between publish and mark | PASS | PASS | Sweep reset 2 rows; re-published; 2 deliveries and 1 applied effect per destination (dedup on event_id); all DONE |
| E5-3 rollback removes the outbox row | PASS | PASS | Exception after the callback: 0 rows. Engine failure inside the command (missing `checkerDecision`): 0 rows. In both cases the task was still open and the root_seq allocation was rolled back |
| E5-4 a late commit with a lower outbox id is still delivered (T06) | PASS | PASS | id 5 committed after id 6; the first pass delivered only id 6, the next pass delivered id 5 |
| E5-5 early WF-3 clarification message buffered, then correlated at the catch event; redelivery dropped | **FAIL** | PASS | Operaton: BUFFERED → PROCESSED, 1 correlation, redelivery DUPLICATE. Flowable: `correlateMessage` found "0 waiting executions" (F-1) |
| E5-5w the same with the business-key lookup bypassed (not counted) | PASS | PASS | BUFFERED → PROCESSED, 1 correlation: the inbox mechanism itself works on Flowable |

Not covered: the poison policy (T08) and the restore scenario (T35).

## 6. E7: cancel/delete with external work in flight

The delete command runs in one engine transaction:
1. a tombstone for each leased work item;
2. the `INSTANCE_DELETED` audit event with the open operationIds;
3. `cancelInstance`.

| Scenario | Flowable | Operaton | Measured |
|---|---|---|---|
| E7-1 delete while the worker is mid-call; late success | PASS | PASS | Tombstone CANCEL_REQUESTED. The late completion got **410 GONE_INSTANCE_DELETED**; a `LATE_RESULT_AFTER_DELETE` audit event and reconciliation item were written; RETRY refused; CONFIRM_SUCCESS accepted. The **instance stayed deleted** (no runtime rows, no work offered); payments = 1 |
| E7-2 the tombstoned lease expires without an answer (VI.5 rule 4) | PASS | PASS | Tombstone → UNKNOWN_OUTCOME; item UNKNOWN_OUTCOME; ledger UNKNOWN_OUTCOME |
| E7-3 late fail / outcomeUnknown after delete | PASS | PASS | Both got 410. Fail without an effect → audited, no item. outcomeUnknown → item |

Raw adapter behaviour **without** the product's tombstone check: the late `completeExternal` passes the lease fence, because the `SPIKE_LEASE` row is left behind after delete. The engine then throws:
- Flowable: `FlowableObjectNotFoundException`;
- Operaton: `NotFoundException`.

Either way it is a 500-class error, not 410. The tombstone check must come first (finding F-7).

## 7. Findings

| # | Kind | Finding | Engines | Suggested action (owner) |
|---|---|---|---|---|
| **F-1** | **Adapter defect** | `FlowableEngineAdapter.correlateMessage` uses `processInstanceBusinessKey(bk)`. Flowable then matches only the process-instance execution (`RES.BUSINESS_KEY_`), so a message catch in a child execution (WF-3 `wf3_awaitApplicantResponse`) is not found. Flowable offers `processInstanceBusinessKey(bk, true)` (include child executions) | Flowable | Use the two-argument form (adapter-flowable owner); then rerun E5 |
| F-2 | Engine behaviour | After `completeExternal`, Flowable continues through a new async job, so the next wait state is reached in a later transaction. Operaton continues synchronously | Flowable | Product must not assume synchronous continuation (already in patch-ledger-flowable) |
| **F-3** | Product schema gap | No mapping from a work/task id to an operationId in the product schema (`OPERATION_LEDGER` has no task id). The lease-expiry sweep and technical delete need it; the test uses its own `IT_WORK_OPERATION` table | both | Add `task_id` / work id to `OPERATION_LEDGER`, or an index table (product-layer owner; spec VI.3 table) |
| **F-4** | **Product/adapter integration hazard** | Both adapters end expired leases inside `fetchAndLock` / `executeDueJobs` (`LeaseTable.expire`) with no product callback. `OperationLedger.recordIntent` then treats the higher lockVersion as a normal new attempt for any capability class. If anything runs the adapter's expiry before the product sweep, class-(c) work is re-dispatched: **measured double payment** (E3-hz) | both | Either give the adapters an expiry hook that calls `onLeaseExpired`, or make `recordIntent` apply the A-8 rule itself when it finds an unfinished INTENT at a lower lockVersion (product-layer and adapter owners) |
| F-5 | Gap in both engines | No "held" state for external work waiting for reconciliation. The spike releases the lease and refuses dispatch, so each poll re-fetches it. Real options: Operaton retries=0 (incident) plus `setRetries`; Flowable dead-letter plus move back. Neither is in `EngineAdapter` | both | Add a park/unpark operation to `EngineAdapter` |
| F-6 | Deviation from A-1 | Adapters time leases on the engine's application clock (JVM `ClockUtil` / Flowable `Clock`). The product layer uses the DB clock (`CURRENT_TIMESTAMP`). Tombstones mix the two (`lease_expires_at` = DB now + the remaining lease from the engine clock) | both | Choose one authoritative clock (A-1 says DB) |
| F-7 | Adapter hygiene | `cancelInstance` leaves `SPIKE_LEASE` rows behind. A late raw complete passes the fence and fails inside the engine (not-found exception) instead of 410 | both | Remove lease rows on cancel; the product must always check the tombstone first |
| F-8 | Product pattern | An engine exception inside a nested engine call marks the whole outer command for rollback, even if it is caught. The inbox handler therefore checks the subscription by SQL (`ACT_RU_EVENT_SUBSCR`) before it correlates. A handler failure leaves the inbox row CLAIMED until the claim TTL and sweep | both | Document it for inbox and worker handlers |
| F-9 | Performance | The count query at 10k tasks × 300 groups has p95 550 ms (Flowable DB) and 386 ms (Operaton DB). The list page passes | both | Rework the predicate shape and add the membership-table join (product-layer owner) |
| F-10 | Engine authorization | Operaton's built-in authorization is not A-3-equivalent: a user grant beats a group revoke, and a specific grant beats a broader revoke | Operaton | Keep it disabled; the product predicate is the only read path |

### Spec defects and ambiguities

- **S-1 (A-3 wording vs the compiled rule).**
  - A-3 says an explicit deny wins "over any grant at the same or broader scope". It does not say what happens when a **broader deny** (`*`) meets a **narrower grant** (task x).
  - VI.4 / A-3 also say the deny is compiled as a `NOT EXISTS` clause. That makes *any* matching deny win, which is what `AuthorizationPolicy` does and what E2-3 verifies.
  - The wording should say "any matching deny wins, regardless of the scope of the grant" (or state the opposite on purpose).
- **S-2 (who applies the capability class at lease expiry).**
  - VI.3.1 and A-8 define the outcome of expiry: AVAILABLE, or UNKNOWN_OUTCOME for class (c).
  - R21 assigns expiry to the "sweeper", but no rule says the sweeper must consult the operation's capability class. Nor does it say that a fetch must never silently end an expired lease.
  - F-4 shows that this gap allows a double payment.
- **S-3 (OPERATION_LEDGER columns).** VI.3 rule 4 lists `(operationId, requestFingerprint, outcome)`, but tombstones (VI.5), reconciliation and expiry handling need the task/work id as well (F-3).
- Already known, re-confirmed here: `ROOT_SEQ` stands in for the L2 root lock and `RT_ROOT.next_outbox_seq`. Engine rows are flushed after the `inEngineTransaction` callback. On Flowable a nested call's flush failure would still roll back the product rows; no such failure was provoked.

## 8. Blockers

None for E2–E5 and E7.
- **Outstanding:** the F-1 fix in `adapter-flowable`, which this agent does not own, and the E5 rerun after it.
- **Coverage gaps:**
  - poison policy (T08) and restore (T35) in E5;
  - history-table predicates (VI.4 rule 8);
  - the membership-table join above 200 groups;
  - Oracle and SQL Server (PostgreSQL only, as instructed).
