# E16-b / E17-b: government patterns GP-21 to GP-40 and domain models, on both engines

Date: 2026-11-04. Module: `spike/experiments-gov-b`. Raw results: `results/E16-b.json`, `results/E17-b.json`.

| | Operaton | Flowable |
|---|---|---|
| Engine | `operaton-2.2.0` | `flowable-8.0.0.0` |
| Adapter jars | re-installed (`product-api`, `adapter-operaton`, `adapter-flowable`, `-DskipTests install`) just before the measured runs | same |
| Database (measured run) | PostgreSQL 17, Testcontainers `postgres:17-alpine`, own database per test class | same |
| JDK / OS | Microsoft OpenJDK 21.0.10, Windows 11 | same |
| Client / worker threads in bulk phases | 4 | 4 |
| Wall time of the measured run (E16-b + E17-b) | 14.1 min | 9.0 min |

Development runs used H2 in-memory (results in `experiments-gov-b/target/gov-results`, not here). There was one PostgreSQL run per engine, inside the shared `customwf` heavy lock.

**How to rerun:**
```
.\mvnw.cmd -q -pl experiments-gov-b test "-Dgov.engine=operaton" "-Dgov.db=postgres"
.\mvnw.cmd -q -pl experiments-gov-b test "-Dgov.engine=flowable" "-Dgov.db=postgres"
```

## Method

- **Models are identical on both engines.** Everything is standard BPMN 2.0.2, DMN 1.3 and CMMN 1.1 (`src/test/resources/govb/models`).
  - Where only a vendor extension can express an element, the file carries a token instead: `{{EXT}}` (external task, V-02 style), `{{DMN}}` / `{{DMNCOLLECT}}` (DMN call from BPMN, V-01 style), `{{CALLED}}` (cross-tenant call activity) or `{{CALLIO}}` (call-activity variable mapping).
  - `Models.render` turns each token into the engine's own syntax. Every rendered token is counted as one **variant element** in the JSON.
  - CMMN models carry no tokens.
- **Native API.** Anything the `EngineAdapter` interface does not expose is called on the engine's own public API through `support/Native*`. That covers case ad-hoc actions, suspension, history queries, decision evaluation, timer rescheduling, incidents and tenants. Every call is recorded per pattern and engine (`nativeApi` in the JSON).
- **Results.**
  - PASS: done with standard models, variant tokens and native API.
  - PASS-WITH-WORKAROUND: also needed extra product or engine-configuration code. The lines are counted.
  - FAIL / UNSUPPORTED: the pattern could not be done.
  - Sub-checks record the outcome of individual features inside a pattern.
- **Fairness.** Both engines ran the same data, the same scale (`Env.scale`) and the same 4 threads. Each engine ran in its own JVM, because Operaton's clock is JVM-wide (W-7).

## GP × engine matrix (E16-b, PostgreSQL)

Columns: result, then (variant elements, native API calls, workaround lines, total ms).

| ID | Pattern | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|---|
| GP-21 | Ad-hoc / case handling | **PASS** (2 nat, 3875 ms) | **PASS** (4 nat, 2422 ms) |
| GP-22 | Appeal / reopen after closure | **PASS** (1 nat, 916 ms) | **PASS** (1 nat, 763 ms) |
| GP-23 | Two-envelope opening, gated by time and committee | **PASS** (1719 ms) | **PASS** (1294 ms) |
| GP-24 | Time-locked action | **PASS** (1 nat, 294 ms) | **PASS** (1 nat, 247 ms) |
| GP-25 | Negotiation loop, retender | **PASS** (2 nat, 1127 ms) | **PASS** (2 nat, 954 ms) |
| GP-26 | Merit list with reservation (DMN COLLECT) | **PASS** (1 var, 2 nat, 20148 ms) | **PASS** (1 var, 2 nat, 3546 ms) |
| GP-27 | Bulk document generation (1,000 admit cards) | **PASS** (1 var, 1 nat, 33521 ms) | **PASS** (1 var, 1 nat, 49133 ms) |
| GP-28 | Waitlist promotion on decline | **PASS** (1 var, 3 nat, 1084 ms) | **PASS** (1 var, 3 nat, 939 ms) |
| GP-29 | Auto-approve below threshold, else route | **PASS** (1 var, 1 nat, 681 ms) | **PASS** (1 var, 1 nat, 399 ms) |
| GP-30 | Periodic generation (monthly bills) | **PASS** (447 ms) | **PASS** (410 ms) |
| GP-31 | Bank scroll reconciliation (1,000 challans) | **PASS** (1 var, 1 nat, 41777 ms) | **PASS** (1 var, 1 nat, 39536 ms) |
| GP-32 | Inter-department referral (cross-tenant call) | **PASS** (2 var, 2 nat, 757 ms) | **PASS-WITH-WORKAROUND** (2 var, 4 nat, 25 wl, 826 ms) |
| GP-33 | Notifications at each step (DLT SMS) | **PASS** (1 var, 1 nat, 1269 ms) | **PASS** (1 var, 1 nat, 757 ms) |
| GP-34 | eSign bound to the approval | **PASS** (1 var, 1 nat, 952 ms) | **PASS** (1 var, 1 nat, 917 ms) |
| GP-35 | RTI clocks 30 days / 48 hours with appeal | **PASS** (2 nat, 1065 ms) | **PASS** (2 nat, 683 ms) |
| GP-36 | MIS / audit queries over history | **PASS** (1 var, 5 nat, 14515 ms) | **PASS** (1 var, 5 nat, 8398 ms) |
| GP-37 | Department-specific rule override (tenants) | **PASS** (1 var, 405 ms) | **PASS** (1 var, 311 ms) |
| GP-38 | Hindi / Unicode round trip | **PASS-WITH-WORKAROUND** (7 nat, 4 wl, 912 ms) | **PASS** (7 nat, 594 ms) |
| GP-39 | Inbox ≥ 10k tasks under VI.4 | **PASS** (414905 ms incl. setup) | **PASS** (165786 ms incl. setup) |
| GP-40 | Suspend / resume (court stay) | **PASS** (5 nat, 529 ms) | **PASS** (5 nat, 476 ms) |

**E16-b totals:**
- Operaton: PASS 19, PASS-WITH-WORKAROUND 1, FAIL 0, UNSUPPORTED 0. 37 native API uses, 11 variant elements, 4 workaround lines.
- Flowable: PASS 19, PASS-WITH-WORKAROUND 1, FAIL 0, UNSUPPORTED 0. 41 native API uses, 11 variant elements, 25 workaround lines.

## Domain × engine table (E17-b, PostgreSQL)

| Model | Operaton 2.2.0 | Flowable 8.0.0 | Scale |
|---|---|---|---|
| D-RECRUIT: notification → fee callback → scrutiny → exam → reservation DMN → DV with waitlist → appointment | **PASS** (2 var, 2 nat, 184 s) | **PASS** (2 var, 2 nat, 176 s) | 2,000 applications, 1,900 fee callbacks, 1,833 eligible, 40 posts, 10 waitlisted, 3 promotions, 0 conflict retries |
| D-GRIEVANCE: case with ad-hoc actions, escalation, reopen | **PASS-WITH-WORKAROUND** (4 nat, 6 wl, 1564 ms) | **PASS** (4 nat, 1413 ms) | 3 cases |
| D-RTI: 30-day clock, 48-hour variant, transfer to another authority, first appeal | **PASS** (4 nat, 1458 ms) | **PASS** (4 nat, 1525 ms) | 5 requests, 2 appeals |
| D-LEAVE: balance check, auto-approve, routing | **PASS** (1 var, 2 nat, 34 s) | **PASS** (1 var, 2 nat, 40 s) | 500 applications |
| D-DBT-SCHEME: verification → sanction → PFMS payment, unknown outcome | **PASS** (2 var, 760 ms) | **PASS** (2 var, 1586 ms) | 4 beneficiaries: 1 ineligible, 1 account failed and then corrected, 1 lost status reconciled. 3 payments, 0 duplicates |
| D-REFERRAL: parallel call activities to Finance and Law tenants | **PASS** (4 var, 2 nat, 455 ms) | **PASS-WITH-WORKAROUND** (4 var, 4 nat, 26 wl, 966 ms) | 2 rounds × 2 departments |

**E17-b totals:**
- Operaton: PASS 5, PASS-WITH-WORKAROUND 1, FAIL 0, UNSUPPORTED 0. 14 native API uses, 9 variant elements, 6 workaround lines.
- Flowable: PASS 5, PASS-WITH-WORKAROUND 1, FAIL 0, UNSUPPORTED 0. 16 native API uses, 9 variant elements, 26 workaround lines.

## Summary counts per engine (E16-b + E17-b, 26 items)

| | PASS | PASS-WITH-WORKAROUND | FAIL | UNSUPPORTED | Native API uses | Variant elements | Workaround lines |
|---|---|---|---|---|---|---|---|
| Operaton 2.2.0 | 24 | 2 (GP-38, D-GRIEVANCE) | 0 | 0 | 51 | 20 | 10 |
| Flowable 8.0.0 | 24 | 2 (GP-32, D-REFERRAL) | 0 | 0 | 57 | 20 | 51 |

The variant counts are identical on both engines because the same tokens render on both. They come from:
- external tasks (11 elements);
- DMN calls from BPMN (5);
- cross-tenant call activities and their in/out mappings (6 across GP-32 and D-REFERRAL);
- part of each count is the same file deployed in several patterns.

## Engine differences that decided a result

| # | Finding | Operaton 2.2.0 | Flowable 8.0.0 | Effect |
|---|---|---|---|---|
| 1 | **Cross-tenant call activity** (GP-32, D-REFERRAL) | `operaton:calledElementTenantId` resolves the callee in the other tenant. **The called instance runs in the callee's tenant**, so the Finance task is in Finance's inbox | No tenant attribute: the callee resolves only in the caller's tenant (or the default tenant). The workaround is `calledElementType="id"` plus a definition-id variable (3–4 lines). **The called instance also inherits the CALLER's tenant** (`ExecutionEntityManagerImpl.createSubprocessInstance` → `inheritCommonProperties`). Measured: the probe child ran in `dept-works`, so the task is invisible to Finance's tenant-bound inbox. Workaround: a `StartProcessInstanceInterceptor` that re-tenants the sub-process instance and its first execution (impl `ExecutionEntity.setTenantId`, 22 lines) | Flowable PASS-WITH-WORKAROUND, 25–26 lines with impl-class use. Re-check on every upgrade |
| 2 | **Exiting a blocking CMMN processTask** (D-GRIEVANCE) | The case closes, but **the called BPMN process keeps running** (the SLA timer would still escalate a disposed grievance). Workaround: on case closure, query `superCaseInstanceId` and cancel what is still running (6 lines) | Exit terminates the called process | Operaton PASS-WITH-WORKAROUND |
| 3 | **String variable > 4,000 characters** (GP-38: 5,590 Devanagari characters) | `PSQLException: value too long for type character varying(4000)`. Workaround: store it as a UTF-8 `byte[]` (4 lines) | Stored as `longString` automatically | Operaton PASS-WITH-WORKAROUND. This also matters for long notings and file remarks |
| 4 | CMMN `timerEventListener` (GP-21 sub-check) | It deploys, but it is **silently ignored**: no timer job, and the escalation never happens (the handler is commented out in `DefaultCmmnElementHandlerRegistry`) | Works | Case escalation is modelled with a processTask + BPMN timer on both engines (identical model). Not counted against Operaton, but a modeller trap |
| 5 | BPMN `adHocSubProcess` (GP-21 sub-check) | UNSUPPORTED: `ENGINE-09005 Invalid destination 'gp21b_adhoc'` | Works through the native ad-hoc API | GP-21 passes on both through CMMN manual activation |
| 6 | DMN COLLECT result shape (GP-26, D-RECRUIT) | `collectEntries` → `List<String>` | JSON array (`tools.jackson…ArrayNode`) of `{"pool": …}` in a variable named after the decision key | Each is usable on its own engine. A two-engine product needs an 18-line normaliser (not counted as a workaround) |
| 7 | Case task key | Plan item id (`PI_examine`) | humanTask id (`grv_examine`) | Already known from the adapter notes. The tests map it |

Behaviour that is the **same** on both engines:
- **GP-30.** A cycle missed while the engine was down is **not replayed**: after a gap over February, one instance is created on 1 March and the next date is in the future. On both engines, for both ISO `R12/…/P1M` and cron. Pension and salary rolls need a product check for missing months. Cron uses the JVM time zone (Asia/Kolkata here); the ISO cycle carries `+05:30`.
- **GP-40.** Suspension blocks task completion, message correlation and timers. **Activation does not move deadlines**: the stay period must be added by rescheduling the timer through the native API (Operaton `setJobDuedate`, Flowable `rescheduleTimeDateJob`).
- **GP-38.**
  - Devanagari works in business keys, candidate groups, task names, variable *names* (also as JUEL identifiers), gateway literals, DMN literals, equals and like queries, and history.
  - Queries compare bytes. A precomposed `क़` (U+0958) does not match its NFC form, so the product must normalise input to NFC.
  - Both engines accepted a `java.util.ArrayList` variable.
- **GP-22.** There is no "reopen a completed instance" on either engine. Appeal and reopen are linked new instances, with the closed record read from history.
- **GP-33.** A DLT rejection reported with retries 0 becomes an incident on Operaton and a dead-letter job on Flowable. Both are visible only through the native API.
- The adapter's `correlateMessage` worked on both engines for main-flow catches, event-based gateways and parallel branches. The fallback in `Gov.correlate` was never used.

## Scale and timing (PostgreSQL, 4 threads)

| Measurement | Operaton | Flowable |
|---|---|---|
| GP-39: creating 11,000 inbox tasks (10k + 1k other tenant) | 177.1 s | 97.7 s |
| GP-39 VI.4 list (page 50), principal with 20 seats, p50 / p95 | 46.6 / **52.9 ms** | 15.1 / **45.9 ms** |
| GP-39 VI.4 count, 20 seats, p95 | 54.4 ms | 30.5 ms |
| GP-39 VI.4 list, principal with **300 seats**, p50 / p95 (T38 target p95 < 200 ms) | 207.9 / **466.8 ms (miss)** | 90.1 / **251.8 ms (miss)** |
| GP-39 VI.4 count, 300 seats, p95 | 432.2 ms | 299.2 ms |
| GP-39 adapter `queryTasks` page 50 (no VI.4), 20 / 300 groups, p95 | 507 / 1385 ms | 133 / 274 ms |
| GP-39 adapter `countTasks`, 20 / 300 groups, p95 | 18.0 / 80.7 ms | 17.3 / 119.0 ms |
| GP-26: native DMN evaluations per second (1,000 calls) | 54 | 353 |
| GP-27: 1,000 admit cards, 4 workers, batch 50 (start / workers) | 11.8 s / 21.7 s (46 docs/s) | 9.2 s / 39.8 s (25 docs/s) |
| GP-31: 950 bank-scroll correlations, 4 threads | 23.5 s (40/s) | 25.9 s (37/s) |
| D-RECRUIT: 2,000 starts / 1,900 fee callbacks | 27.1 s / 35.4 s | 18.9 s / 38.0 s |
| D-RECRUIT: scrutiny inbox query (1,900 tasks, one call) / bulk completion | 14.3 s / 27.5 s | 0.46 s / 30.8 s |
| D-RECRUIT: 1,833 exam scores + DMN COLLECT / result declarations | 30.2 s / 27.3 s | 26.5 s / 37.0 s |
| D-LEAVE: 500 applications / 305 officer decisions | 16.3 s / 17.8 s | 12.9 s / 27.0 s |

**GP-39 inbox setup:**
- The VI.4 predicate (`AuthorizationPolicy.visible`) is product SQL over the engines' identical `ACT_RU_TASK` / `ACT_RU_IDENTITYLINK` tables.
- Grants: a role grant `READ_TASK` on `*` for each of the 300 seats.
- Denies: an explicit deny on 50 tasks for `seat-000`.

**GP-39 results:**
- The VI.4 count equals the expected visible set on both engines: 630 for 20 seats, 9,950 for 300 seats. Denied tasks never appear in a page.
- The engine-native query has no deny: 680 tasks.
- **Both engines miss T38 (p95 < 200 ms for 10k tasks × 300 groups)** with the plain `IN`-list predicate. That matches VI.4 rule 2, which says to join a materialised membership table above 200 principal ids. That variant was not built here.

**Adapter cost.** Operaton's adapter list issues one `getVariables` call per task. That explains its 0.5–1.4 s pages and the 14 s scrutiny inbox. It is an adapter projection cost, not the engine query: the count is 18 ms.

## Workaround lines by item

| Item | Engine | Workaround | Lines |
|---|---|---|---|
| GP-32 | Flowable | Callee definition id looked up and passed (`calledElementType=id`) | 3 |
| GP-32 | Flowable | Re-tenant the called instance (`StartProcessInstanceInterceptor`, impl `ExecutionEntity.setTenantId`) | 22 |
| D-REFERRAL | Flowable | Two callee definition ids looked up and passed | 4 |
| D-REFERRAL | Flowable | Same interceptor as GP-32 | 22 |
| GP-38 | Operaton | Long string as UTF-8 `byte[]` | 4 |
| D-GRIEVANCE | Operaton | Cancel the called SLA processes still running when the case closes | 6 |

## FAIL / UNSUPPORTED

- **No item is FAIL or UNSUPPORTED on either engine.**
- Sub-features that are unsupported or not executed:
  - Operaton: BPMN `adHocSubProcess` (deploy rejected) and CMMN `timerEventListener` (deployed, never fires). Both patterns were met through CMMN manual activation and a processTask timer.
  - Flowable: native cross-tenant call activity (the child runs in the caller's tenant). Met with the interceptor workaround.

## Not covered, or limits of this run

- The GP-39 T38 target is missed on both engines. Next step: the membership-table form of the predicate (VI.4 rule 2) and indexes on `ACT_RU_IDENTITYLINK(GROUP_ID_, TASK_ID_)`.
- A shared (tenant-less) BPMN with per-tenant DMN was not tested: it needs a decision-tenant hint (a variant) on both engines. Per-tenant deployment of the same file was tested and needs nothing.
- D-DBT reconciliation relies on the PFMS simulator's class (a) idempotency (`DUPLICATE_OPERATION_ID` on re-send). The real PFMS behaviour is still on request (PK-05).
- Fee callbacks arriving before the instance waits (GP-15 early arrival) were not part of D-RECRUIT here.
- Engine authorization stays off (as configured in the adapters). VI.4 is product SQL.
