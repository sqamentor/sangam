# E16-a / E17-a: government patterns GP-01..GP-20 and domain models, on Operaton 2.2.0 and Flowable 8.0.0

**Evidence:** `results/E16-a.json` and `results/E17-a.json`. Both files come from ONE PostgreSQL run per engine, on 2026-11-04.
**Code:** module `spike/experiments-gov-a`. The tests are `E16PatternsTest` and `E17DomainsTest`. Engine-specific code sits in `kit/operaton/OperatonKit` and `kit/flowable/FlowableKit`.

To repeat the run:

```
.\mvnw.cmd -q -pl experiments-gov-a verify -Dgov.db=postgres -Dgov.scaleN=5000   (inside the heavy lock)
```

Use `-Dgov.db=h2` for a pre-run with no Docker.

## Setup and fairness

| | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|
| Adapter | `OperatonEngineAdapter` (jar installed from source 2026-11-04 18:1x) | `FlowableEngineAdapter` (same) |
| Database | PostgreSQL 17.11 (Testcontainers `postgres:17-alpine`), a fresh database per scenario | same |
| JVM | own surefire JVM, JDK 21.0.10, `-Xmx2g`, `user.timezone=Asia/Kolkata` | same |
| History level | **FULL** (the adapter sets it) | **AUDIT** (engine default; the adapter sets nothing) |
| Job execution | job executor off; timers run through `executeDueJobs()` with a driven engine clock | same |
| Threads | GP-08: 4 threads to start, 4 to complete. GP-09: 1 thread | same |

Fairness rules:
- The models are identical on both engines. They differ only in the vendor tokens listed under "variants" in each JSON row. The variant code is in `Kit.externalTask / dmnTask / callActivity / dueDateAttr / timerCalendarAttr / parallelExternalTaskAttrs`.
- Nothing was tuned on either engine. Both adapters are used as installed.
- History level is the one asymmetry. It comes from the adapters, not from this module, and it affects the row counts: Operaton writes `ACT_HI_DETAIL`, while Flowable writes runtime `ACT_RU_ACTINST` rows.

Result classes (`Rec.java`):
- **PASS:** the engine does it with its own features. Vendor attributes count as variants, not as workarounds.
- **PASS-WITH-WORKAROUND:** product/test code or a model trick stands in for a missing or broken engine feature. Each workaround is listed with its line count.
- **FAIL:** the expected behaviour was not reached.
- **UNSUPPORTED:** the engine has no means at all.

Some code is outside the engine by design: budget/EMD ledgers, the seat register, file workers and the operationId rule. It is recorded under `productLayer`, is the same on both engines, and does **not** count as a workaround.

## Summary counts

| Engine | Set | PASS | PASS-WITH-WORKAROUND | FAIL | UNSUPPORTED |
|---|---|---|---|---|---|
| Operaton 2.2.0 | E16-a (20 patterns) | 16 | 3 | 1 | 0 |
| Operaton 2.2.0 | E17-a (5 domains) | 3 | 2 | 0 | 0 |
| **Operaton 2.2.0** | **total (25)** | **19** | **5** | **1** | **0** |
| Flowable 8.0.0 | E16-a (20 patterns) | 15 | 3 | 2 | 0 |
| Flowable 8.0.0 | E17-a (5 domains) | 4 | 0 | 1 | 0 |
| **Flowable 8.0.0** | **total (25)** | **19** | **3** | **3** | **0** |

## GP × engine matrix (E16-a, PostgreSQL)

Times are the scenario time in ms (Operaton / Flowable), without engine boot. "Variant" lists the vendor tokens needed (O = Operaton, F = Flowable).

| GP | Pattern | Operaton | Flowable | ms O / F | Variant (both unless noted) | Native API outside `EngineAdapter` |
|---|---|---|---|---|---|---|
| GP-01 | Maker → checker → approver, amount bands | PASS | PASS | 4212 / 3457 | DMN call (O: `businessRuleTask operaton:decisionRef` + `mapDecisionResult=singleEntry`; F: `serviceTask flowable:type="dmn"`) | none |
| GP-02 | Hierarchy, return to any level | PASS | PASS | 1448 / 1265 | none (one router gateway) | instance modification (O: `createProcessInstanceModification`; F: `createChangeActivityStateBuilder().moveActivityIdTo`), history query |
| GP-03 | Committee, all sign | PASS | PASS | 692 / 653 | none (`loopCardinality` + `humanPerformer ${members.split(';')[loopCounter]}`) | none |
| GP-04 | Quorum n of m | PASS | PASS | 506 / 635 | none (`completionCondition`) | history query |
| GP-05 | Chained sanctions AA → TS → ES | PASS | PASS | 890 / 687 | `in`/`out` mapping on `callActivity` (O: `operaton:in/out`; F: `flowable:in/out`) | super-instance query |
| GP-06 | SLA, working days, holidays, reminders, escalation | **PASS-WITH-WORKAROUND** | PASS | 615 / 893 | F: `flowable:businessCalendarName` per timer | O: replace the engine-wide `duration` calendar (`MapBusinessCalendarManager.addBusinessCalendar("duration", …)`, impl API). F: register a named calendar (`addBusinessCalendar(name, …)`, impl API) |
| GP-07 | Extend the deadline of running instances | PASS | PASS | 381 / 440 | none | O: `ManagementService.setJobDuedate` (job id kept). F: `ManagementService.rescheduleTimeDateJob` (new job id) |
| GP-08 | Bulk intake 5,000 + bulk decide/withdraw | PASS-WITH-WORKAROUND | PASS-WITH-WORKAROUND | 112 499 / 162 678 | none | O: `deleteProcessInstances(List,…)`. F: `bulkDeleteProcessInstances`. Both: task id paging, `processVariableValueEquals` |
| GP-09 | Multi-instance with 5,000 items | **FAIL** | **FAIL** | 275 249 / 301 115 | none | task id paging |
| GP-10 | 36 monthly cycles | PASS | PASS | 3358 / 5699 | none (`R36/P1M`) | timer job query |
| GP-11 | Encumbrance, released on cancel | PASS | **PASS-WITH-WORKAROUND** | 566 / 1253 | external task (O: `operaton:type="external"`; F: `flowable:type="external-worker"`) | none |
| GP-12 | Payment with unknown outcome + reconciliation (IFMS JIT) | PASS | PASS | 1345 / 2608 | external task | none |
| GP-13 | EMD refund by compensation | PASS | **FAIL** | 3295 / 3169 | none | history query |
| GP-14 | Withdraw / cancel at any stage | PASS | PASS | 607 / 624 | none | `RuntimeService.signalEventReceived` (adapter has no signal call) |
| GP-15 | Portal callbacks, early arrival, duplicates | PASS-WITH-WORKAROUND | PASS-WITH-WORKAROUND | 591 / 571 | none | message subscription query |
| GP-16 | Checklist + clarification loop, deemed closure | PASS | PASS | 631 / 936 | none (event-based gateway, message vs `P30D`) | none |
| GP-17 | Inspection due date, reschedule, reassign | PASS | PASS | 232 / 211 | task due date (O: `operaton:dueDate`; F: `flowable:dueDate`) | `setDueDate` (O: `Task.setDueDate`+`saveTask`), `setAssignee`, assignee history (O: `createHistoricIdentityLinkLogQuery`; F: `getHistoricIdentityLinksForTask`) |
| GP-18 | Transfer, additional charge, leave delegation | PASS | PASS | 397 / 348 | none | `setAssignee(null)`, `delegateTask` / `resolveTask` |
| GP-19 | Rule change: old instances keep the old rule | PASS | PASS | 921 / 582 | binding (O: `operaton:decisionRefBinding="deployment"`; F: field `sameDeployment=true`) | none |
| GP-20 | Migrate running instances | PASS | PASS | 904 / 691 | none | O: `createMigrationPlan().mapEqualActivities().mapActivities()` + `newMigration().execute()`. F: `ProcessMigrationService…addActivityMigrationMapping().migrate()` |

## Domain × engine table (E17-a, PostgreSQL)

| Domain | Operaton | Flowable | ms O / F | Notes |
|---|---|---|---|---|
| D-BUDGET-REAPPROPRIATION | PASS | PASS | 1051 / 949 | Savings check (external), revise loop, DMN authority (5 L → HOD, 1.5 Cr → FD-SECY), idempotent posting after lease re-delivery |
| D-WORKS | PASS | PASS | 1779 / 1838 | Estimate → AA → TS → tender stub → WO → MB sub-process with `R3/P1M` boundary cycle. 3 RA bills plus the final bill are paid through `IfmsJitSimulator`, with 4 distinct operationIds and no duplicate |
| D-TENDER | PASS-WITH-WORKAROUND | **FAIL** | 1552 / 1869 | O: early bid through the inbox; the compensation handler cannot see its MI item, so the product EMD ledger assigns it. F: every step up to the LoA passed; the EMD refund by compensation failed (FK violation, below). F also needs `flowable:exclusive="false"` on the parallel MI external task |
| D-PENSION-MONTHLY | PASS-WITH-WORKAROUND | PASS | 1540 / 1631 | Timer start `R3/2026-12-01T06:00+05:30/P1M`: Operaton runs **4** times, Flowable 3. 50 pensioners per month are paid through IFMS JIT |
| D-CHALLAN-RECON | PASS | PASS | 609 / 879 | `PfmsSftpSimulator`: outbound file, polling worker with a 30 min back-off, `PT2H` overdue escalation, match (1 exception → user task), post 4 |

## Scale numbers (PostgreSQL)

### GP-08: 5,000 instances, 4 threads

| | Operaton | Flowable |
|---|---|---|
| Start 5,000 | 49.4 s (101/s) | 46.5 s (108/s) |
| Count tasks by seat / first page of 50 / count by process variable | 23 / 317 / 15 ms | 25 / 39 / 46 ms |
| Native bulk delete of 500 (withdrawn) | 9.6 s | 22.4 s |
| Decide the remaining 4,500 (product loop, 4 threads) | 51.8 s (87/s) | 92.8 s (49/s) |
| DB rows after start | 70,000 (25,000 runtime + 45,000 history) | 85,000 (45,000 runtime + 40,000 history) |
| DB rows at end | 63,000 (all history) | 62,500 (all history) |
| Heap after GC before / after; peak heap | 64 / 65 MB; 200 MB | 34 / 36 MB; 232 MB |

Flowable's runtime rows include `ACT_RU_ACTINST` (3 per instance) and 2 executions per instance. Operaton's history includes `ACT_HI_DETAIL`, because it runs at FULL history.

### GP-09: 5,000 items in one parallel multi-instance, 1 thread, completion capped at 180 s

| | Operaton | Flowable |
|---|---|---|
| Start with 5,000 items (one transaction) | 43.5 s | 32.2 s |
| Rows created | 45,017 (20,006 runtime) | 45,015 (25,008 runtime) |
| Items completed in 180 s | **1,732** (≈ 107 s for the first 1,000) | **1,290** (≈ 142 s for the first 1,000) |
| Linear projection for all 5,000 | ≥ 520 s | ≥ 698 s |
| Cancelling the instance with the open items | 43.8 s | 82.3 s |
| Heap peak | 247 MB | 312 MB |

On H2 the cost per item grew with the number of open items: 500 items took 14 / 26 ms per completion (O / F), and 2,000 items took 41 / 77 ms. Completing one item therefore costs time in proportion to the open siblings, so the total grows as about n².

## FAIL and UNSUPPORTED items, with reasons

| Item | Engine | Reason |
|---|---|---|
| GP-09 | both | 5,000 parallel MI items are created fine, but completing them does not finish in 180 s: 1,732 (O) and 1,290 (F) were done. The completion cost grows with the open items. Workaround (not measured): one instance per item (the GP-08 shape, linear) or chunked sub-processes. |
| GP-13 | Flowable | Throwing compensation fails when the handler is a wait state. On PostgreSQL the error is `PSQLException: update or delete on table "act_ru_execution" violates foreign key constraint "act_fk_exe_parent"`; H2 gives the same. The probe matrix in the JSON covers 12 cells. Every userTask and external-task handler fails, single or MI, through normal flow or an interrupting message boundary. Only a pass-through `manualTask` handler works. Operaton passes all 12 cells. |
| D-TENDER | Flowable | The same compensation defect hits the EMD refund to losers after the LoA. Everything before the LoA passed: corrigendum, early bid, committee all-sign, time lock, negotiation loop. |

No item was UNSUPPORTED on either engine.

## Engine differences found (precise)

1. **Business calendar (GP-06).**
   - Flowable has a per-timer calendar, `flowable:businessCalendarName`, and named calendars in `MapBusinessCalendarManager`.
   - Operaton 2.2.0 has only one calendar per timer type (`TimerDeclarationType.calendarName`). A working-day calendar therefore has to replace `"duration"` engine-wide.
   - On both engines, registering a calendar is impl API.
   - The portable form works the same on both: the product layer computes `${slaDue}` and the model uses `timeDate`.
2. **Timer reschedule (GP-07).** Operaton `setJobDuedate` keeps the job id. Flowable `rescheduleTimeDateJob` replaces the job, so it gets a new id.
3. **ISO cycle with a start date (D-PENSION).** `R3/<start>/P1M` gives 4 runs on Operaton and 3 on Flowable. Without a start date, `R3/P1M` gives 3 on both (D-WORKS), and `R36/P1M` gives 36 (GP-10).
4. **P1M from the 31st (GP-10, both engines).** The first cycle lands on 28 Feb, and every later one on the 28th, because each repeat starts from the previous due date.
5. **Long-lived instance (GP-10).** After 36 cycles Flowable's runtime `ACT_RU_ACTINST` had grown by 175 rows. Operaton's runtime rows stay flat (5–6).
6. **DMN default binding (GP-19).** Without a binding attribute:
   - Operaton uses the latest decision, so the old instance got the new fee 1500.
   - Flowable used the decision of the instance's own deployment, so the old instance kept fee 1000.
   With the explicit binding, both engines keep the old rule.
7. **Compensation.**
   - Flowable fails with wait-state handlers (above).
   - On Operaton, the external-task compensation handler of a parallel MI item gets no `loopCounter` (D-TENDER), so the worker cannot tell which item it compensates.
8. **Parallel MI external task (D-TENDER).**
   - Flowable external-worker jobs are exclusive by default. Acquiring the 3 jobs of one instance returned **0 of 3**, both through the adapter and through native `createExternalWorkerJobAcquireBuilder().acquireAndLock`, and it left the jobs locked.
   - `flowable:exclusive="false"` fixes it (Flowable variant).
   - Operaton fetched 3 of 3.
9. **Early messages (GP-15).** Neither engine buffers them: Operaton throws `MismatchingMessageCorrelationException`, and the Flowable adapter throws "matched 0 waiting executions". The product inbox (VI.2), or a model that opens the subscription at start, is required on both.
10. **Assignment history (GP-17).** Operaton keeps an ordered add/delete log (`assignee:add:a, assignee:delete:a, assignee:add:b`). Flowable keeps only the identity links (`assignee:a, assignee:b`), with no operation or order.
11. **Segregation of duties (GP-01).** Neither engine stops the same user from claiming the maker and checker tasks, so SoD is a product check.
12. **Adapter difference (GP-18).** Candidate-group queries include assigned tasks in the Operaton adapter (`includeAssignedTasks`) but not in the Flowable adapter: the successor saw 3 tasks on Operaton and 2 on Flowable before the release.

## Workarounds (counted lines)

| Item | Engine | Workaround | Lines |
|---|---|---|---|
| GP-06 | Operaton | Replace the engine-wide `duration` calendar with a working-day calendar (impl API; affects every duration timer) | 30 |
| GP-08 | both | No bulk task-completion API: the product pages task ids and completes them with 4 threads | 25 |
| GP-11 | Flowable | Compensation is broken, so the model has an explicit cancel → release external task | 4 |
| GP-15 | both | Product inbox: buffer by business key and callback id, deliver when the subscription exists | 20 |
| GP-15 | both | Model alternative: a parallel gateway opens the message catch at start | 6 |
| D-TENDER | both | Inbox for bids that arrive before the NIT is published | 10 |
| D-TENDER | Operaton | Compensation handler cannot see its MI item, so the product EMD ledger assigns each handler run | 8 |
| D-PENSION | Operaton | `R3/<start>/P1M` runs 4 times; three runs need `R2/...` (an engine-specific value) | 1 |

## Needs and blockers

- **Blockers:** none. Both PostgreSQL runs completed, and the jars were reinstalled from source just before them.
- **For the adapter owners (information, no change made here):**
  - `FlowableEngineAdapter` does not set a history level, so it runs at AUDIT while Operaton runs at FULL.
  - The candidate-group semantics for assigned tasks differ between the adapters (GP-18).
  - Neither adapter exposes signals, timer changes, migration, modification, bulk delete, task reassignment or due dates. Those were done here through the native API, as listed above.
- **Not covered:** GP-21..GP-40 (another owner).
