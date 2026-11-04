# E16-c / E17-c: patterns GP-41 to GP-55 and the added domains, on both engines

**What was run.** Two test classes in `experiments-gov-c`, each on **Operaton 2.2.0** and **Flowable 8.0.0** through the spike adapters, on **PostgreSQL 17** (Testcontainers `postgres:17-alpine`, server 17.11), on 2026-11-04. There was one measured run per engine, inside the shared heavy-build lock (held 18:12–18:17 IST). The H2 pre-runs gave the same results.
- `E16cPatternTest`: the 15 patterns GP-41 to GP-55 (`GOV-PATTERNS.md`, "Added after validation").
- `E17cDomainTest`: the domains D-BANK-GUARANTEE, D-CONTRACT-VARIATION, D-COURT-CASE, D-STORES-VERIFICATION, D-LAND-ACQUISITION and D-AUDIT-PARA.

Evidence:
- `results/E16-c.json` and `results/E17-c.json`: per engine and item, the result, checks, features, native APIs, vendor tokens, workarounds with line counts, details, and timing.
- Models: `experiments-gov-c/src/test/resources/models/`.

**Rerun.** `.\mvnw.cmd -pl experiments-gov-c test` runs PostgreSQL inside the lock. `'-Dgov.db=h2'` gives a development run that writes only under `target/`.

**Fairness.**
- Both engines run the **same model files**, the same data, the same clock script and the same thread counts (GP-48: 8 workers).
- Each item starts on a fresh engine and a fresh database.
- The only engine-specific XML comes from four vendor tokens, which are expanded at load time and listed per item:
  - `ext`: an external task. The reason is the same as corpus V-02.
  - `expr`: an expression service task, used for counters and aggregates.
  - `callio` / `callbk`: call-activity variable mapping and business-key inheritance.
- One token option applies to **Flowable only**: `ext|…;nonexclusive` adds `flowable:exclusive="false"`, and nothing on Operaton. It is counted as a Flowable workaround (see F-4).
- Native engine APIs are called directly wherever `EngineAdapter` has no equivalent. Every call is recorded per item in `nativeApis`.

**Result rule.**

| Result | Meaning |
|---|---|
| PASS | Every check holds. The engine does it through standard model support plus its public API, native calls included. |
| PASS-WITH-WORKAROUND (PASS-WW) | Every check holds, but engine-specific compensation was needed: a composition of several calls, product-side bookkeeping the engine does not keep, or an engine-only attribute. The lines of code for this are counted. |
| FAIL | A check failed. |
| UNSUPPORTED | No way was found. |

## Summary counts per engine (PostgreSQL)

| | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|
| E16-c (15 patterns): PASS / PASS-WW / FAIL / UNSUPPORTED | **10 / 5 / 0 / 0** | **6 / 9 / 0 / 0** |
| E17-c (6 domains): PASS / PASS-WW / FAIL / UNSUPPORTED | **4 / 2 / 0 / 0** | **2 / 4 / 0 / 0** |
| Total (21 items) | **14 / 7 / 0 / 0** | **8 / 13 / 0 / 0** |
| Workaround lines, E16-c + E17-c | 43 + 16 = **59** | 100 + 53 = **153** |
| Items whose workaround applies to this engine only | 1 (GP-54) | 9 |

**Both engines can do all 21 items.** Nothing failed and nothing is unsupported. Most of the difference is in workarounds, and 7 of Operaton's 8 workarounds are needed on both engines.
- **Operaton:** its 59 lines are almost all shared. Those shared workarounds are per-branch correlation, local results per branch, and pause arithmetic. The rest are GP-54 (links) and a small GP-45 resume step.
- **Flowable:** it needs those same shared workarounds, plus three of its own:
  - timer recalculation, in 5 items, about 18 lines each;
  - conditional events (GP-42);
  - instance modification and exclusive external jobs.

## Area 1: re-arming and pausing timers on running instances (GP-41, 43, 45, 51)

| Capability | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|
| Timer follows a changed date variable by itself | No (GP-43 check: still at the old date) | No (same) |
| Re-arm from the model expression with the new variables | `ManagementService.recalculateJobDuedate(jobId, false)`: one call | **No such call.** The expression is read from `RepositoryService.getBpmnModel(...)` and passed back to `ManagementService.rescheduleTimeDateJob(jobId, "${expr}")`, which evaluates it against the job's execution. About 18 lines (`FlowableNative.recalculateTimerFromModel`). |
| Move to an absolute date | `ManagementService.setJobDuedate` (the job keeps its id) | `ManagementService.rescheduleTimeDateJob` (**a new job id** is returned) |
| Model-only re-arm (event-based gateway loop on a message: GP-41 `bgExtended`, GP-51 `hearingRescheduled`) | Works | Works (identical model) |
| Boundary timer on a task the model cannot loop (GP-43 LD timer, GP-51 brief deadline) | Native re-arm needed (above) | Native re-arm needed (above) |
| Race: extension arrives after the expiry date but before the timer job runs (GP-41) | The message wins and the expiry job is removed | Same |
| Pause (GP-45): `suspendProcessInstanceById` | The timer is suspended and does not fire past its due date; the task cannot be completed | Same |
| Resume with the remaining time | Re-dating the suspended timer and activating **in one transaction** fails with `OptimisticLockingException ENGINE-03005`. Re-dating while still suspended (tx 1) and then activating (tx 2) works, with **no window** for an early fire. | `rescheduleTimeDateJob` on a **suspended** timer returns **without error and without effect**, and activation then restores the old date. The only working order is activate (tx 1), then re-date (tx 2). Under a running job executor this leaves a window in which the overdue timer can fire. |
| Remaining time and net elapsed time | Product arithmetic on both engines; neither engine keeps a paused-clock balance | Same |
| Calendar months (D-AUDIT-PARA `P2M`/`P3M` from 4 Jan → 4 Mar / 4 Apr) | Correct | Correct |

**Verdict on area 1: Operaton is ahead.** It has a recalculation call and a safe pause/resume order. Flowable needs a model-reading composition, and its pause/resume has a race window.

## Area 2: a child or sub-process updating its parent's data (GP-43, GP-54)

| Capability | Operaton | Flowable |
|---|---|---|
| Child result to the parent at child end (call-activity out-mapping) | `operaton:out` (token `callio`) | `flowable:out` (same token) |
| Live update from a running child | `ProcessInstanceQuery.subProcessInstanceId` → `RuntimeService.setVariable(parent, …)`. The parent sees it while the child still runs. | Same APIs, same result |
| Parent finds its children | `ProcessInstanceQuery.superProcessInstanceId` | Same |
| Add an activity to a running instance (GP-43 extra item) | `createProcessInstanceModification(pid).startBeforeActivity(id)`. The running work task keeps its id. | `createChangeActivityStateBuilder().moveSingleActivityIdToActivityIds(running, [running, extra])`. Flowable can only *move*, so the running task is **re-created with a new id**: claims and assignees on it are lost, and its boundary timer is re-created from the current variables. |
| Linked sibling instance (GP-54) | No reference field: two variables plus a `variableValueEquals` query (4 lines) | Native `ProcessInstanceBuilder.referenceId/referenceType` and `processInstanceReferenceId` query |
| Old and new values in history (GP-36 link) | `HistoricDetailQuery.variableUpdates` | Same, **after raising the history level to FULL** (see adapter need N-1) |

**Verdict on area 2: even.** Updating a parent from a child works the same way on both engines. Operaton is better at adding an activity without disturbing the running one. Flowable is better at native instance links.

## Area 3: state shared across instances (GP-48, GP-49)

- **No engine keeps cross-instance state.** On both engines the ledger is a product table, written in the engine's own transaction (`inEngineTransaction`) together with `completeExternal` or `bpmnErrorExternal`.
- **GP-48 concurrency.** 30 one-day requests ran against a balance of 20, with 8 concurrent workers. Both engines granted exactly 20 and refused 10, the balance never went negative, and there were 0 retries. A replayed debit was a no-op (idempotency table). The year-end timer-start cycle carried balances forward with a cap and re-armed for the next year on both engines.
- **GP-49 partial batch failure.** 10 items: 7 paid and 3 failed with different reason codes. Each failure went to its own desk, round 2 re-sent exactly items [7, 8, 9], and 13 per-item results were correlated.
  - Neither the adapter nor either engine's correlation API can address "the branch for item i" by business key, so delivery is the same native composition on both engines:
    - find the waiting execution, through `createExecutionQuery().messageEventSubscriptionName`;
    - read its `loopCounter`, through `getVariable`;
    - find the branch scope execution, through `variableValueEquals` on the local `loopCounter`;
    - then, in one transaction, call `setVariablesLocal` and `messageEventReceived(name, executionId)`.
  - **Operaton detail.** Local variables set on the *catch* execution are lost when the event fires (`PropertyNotFoundException: status`). They must go on the branch scope execution. Flowable kept them on either execution, but the same scope-level code is used for both.

**Verdict on area 3: even.** The capability is the same on both engines, and so is the product code.

## Other findings

- **F-1 Conditional events (GP-42).**
  - Operaton evaluates the non-interrupting conditional boundary event on every variable change.
  - Flowable 8.0.0 **does not**: the revised-sanction branch started only after an explicit `RuntimeService.evaluateConditionalEvents(pid)`. The product must call it after every change that can affect a condition.
- **F-2 Deemed outcomes (GP-44), statutory lapses (D-LAND-ACQUISITION), objection windows (GP-46).** These use standard interrupting boundary timers and event gateways, and are identical on both engines. A late officer action on the withdrawn task is rejected.
- **F-3 Record retention (GP-52).** Retention is modelled as a live wait state after closure. Reopen works by message, a history lookup by business key finds a weeded file, and `HistoryService.deleteHistoricProcessInstance` removes it on both engines. Engine history-cleanup and TTL features were not used.
- **F-4 Flowable external-worker jobs are exclusive by default.**
  - With several external jobs in **one** instance (a parallel multi-instance), an acquire of 10 jobs or of 1 job returns **nothing**. The second process-instance lock fails, and the whole acquire rolls back silently, with no exception and no adapter give-up.
  - Measured by the GP-47 probe (`probeDefaultMiFetch10`): Flowable 0, Operaton 3.
  - Fix: `flowable:exclusive="false"` on the task (GP-47, GP-55, D-STORES-VERIFICATION).
  - Operaton external tasks are not jobs and are not affected.
- **F-5 Bulk recompute (GP-47).** 300 candidates as parallel multi-instance external work, each completed together with a product row in one transaction (the `inEngineTransaction` call): 14.3 s on Operaton and 22.4 s on Flowable. Flowable is about 1.6× slower here: each completion also runs its continuation job (AFTER_COMMIT) as a separate transaction.
- **F-6 Child lifecycle (D-BANK-GUARANTEE).**
  - The BG instrument (`gp41-bg`, reused unchanged) runs as a call-activity child that inherits the contract number as its business key. Bank messages reach the child by that key.
  - If the BG is encashed, the contract keeps running and a late release request is refused.
  - Cancelling the contract removes the child, on both engines.
- **F-7 Number types.** `x + 1` in an `expr` task turns an `Integer` into a `Long` on both engines. Product code must compare numerically.

## Adapter needs (reported; this module edited nothing outside itself)

- **N-1 (Flowable adapter).** It leaves Flowable at its default history level, `audit`, which records no variable-update details. The Operaton adapter uses FULL.
  - To compare the engines rather than the adapter settings, `FlowableNative.create` raises it to FULL after the build, through the impl class `ProcessEngineConfigurationImpl.setHistoryLevel`.
  - Parity fix: set `HistoryLevel.FULL` in `FlowableEngineAdapter`.
- **N-2 (EngineAdapter).** Add timer operations, used by 7 items:
  - `timers(instance)`;
  - `rescheduleTimer(jobId, Instant)`;
  - `recalculateTimer(jobId)`. On Flowable this would wrap the model-read composition.
  - Also add `suspend` / `activate`, plus a documented safe re-date-on-resume order per engine (GP-45).
- **N-3 (EngineAdapter).** Add correlation to one waiting branch (by a local variable such as `loopCounter`) with local result variables (GP-46, GP-49, D-LAND-ACQUISITION). Today `correlateMessage(businessKey)` is ambiguous as soon as more than one branch waits.
- **N-4 (EngineAdapter).** Add `subInstances`, `superInstance` and `setVariable(instance, …)`, needed for live child-to-parent updates. Add linked-instance references: native on Flowable, by variables on Operaton.
- **N-5 (Flowable adapter / corpus rules).** Document F-4. Any parallel multi-instance external task needs `flowable:exclusive="false"`, or the adapter could treat an empty acquire against existing jobs as an error.
- **N-6 (Flowable adapter).** Expose `evaluateConditionalEvents` (F-1), or evaluate it after variable-changing calls.
- **N-7 (engineId).** The Flowable adapter reports `flowable-8.0.0.0`, because `ProcessEngine.VERSION` has four parts.

## Limits

- Statutory periods use calendar days and months computed by the product. Business-day calendars (GP-06) were not part of this scope.
- GP-54 merge was not tested, because the validation found no sourced stage for it. Engine history TTL and cleanup jobs were not tested.
- Timings are single-run figures on one Windows host, with the job executors off: timers fire through `executeDueJobs()`. They show relative cost, not throughput.
- Flowable's history level was raised in the test hooks (N-1). Without that, the history checks in GP-42, GP-43, GP-53 and D-CONTRACT-VARIATION would fail on Flowable for configuration reasons only.


## E16-c: GP × engine

### E16-c summary

| Engine | engineId | database | PASS | PASS-WITH-WORKAROUND | FAIL | UNSUPPORTED | workaround lines |
|---|---|---|---|---|---|---|---|
| operaton | operaton-2.2.0 | PostgreSQL (Testcontainers postgres:17-alpine, server 17.11) | 10 | 5 | 0 | 0 | 43 |
| flowable | flowable-8.0.0.0 | PostgreSQL (Testcontainers postgres:17-alpine, server 17.11) | 6 | 9 | 0 | 0 | 100 |

### E16-c matrix

| ID | Name | Operaton | Flowable | Operaton native APIs | Flowable native APIs | WA lines O / F | scenario ms O / F (engine boot ms O / F) |
|---|---|---|---|---|---|---|---|
| GP-41 | Security-instrument (BG) lifecycle: verify, extend or encash before expiry, release | **PASS** | **PASS-WW** | `RuntimeService.setVariable`, `ManagementService.recalculateJobDuedate`, `ManagementService.setJobDuedate` | `RuntimeService.setVariable`, `RepositoryService.getBpmnModel`, `ManagementService.rescheduleTimeDateJob(expression)`, `ManagementService.rescheduleTimeDateJob` | 0 / 18 | 2312 / 3164 (18571 / 17830) |
| GP-42 | Cumulative-threshold re-sanction (10 % over AA) while work continues | **PASS** | **PASS-WW** | `ProcessInstanceQuery.superProcessInstanceId`, `HistoryService.createHistoricDetailQuery().variableUpdates` | `ProcessInstanceQuery.superProcessInstanceId`, `RuntimeService.evaluateConditionalEvents`, `HistoryService.createHistoricDetailQuery().variableUpdates` | 0 / 3 | 1095 / 2250 (3111 / 2935) |
| GP-43 | Live contract amendment: EoT re-arms the LD timer, child updates parent, extra item added | **PASS** | **PASS-WW** | `ProcessInstanceQuery.superProcessInstanceId`, `ProcessInstanceQuery.subProcessInstanceId`, `RuntimeService.setVariable`, `ManagementService.recalculateJobDuedate`, `RuntimeService.createProcessInstanceModification().startBeforeActivity`, `HistoryService.createHistoricDetailQuery().variableUpdates` | `ProcessInstanceQuery.superProcessInstanceId`, `ProcessInstanceQuery.subProcessInstanceId`, `RuntimeService.setVariable`, `RepositoryService.getBpmnModel`, `ManagementService.rescheduleTimeDateJob(expression)`, `RuntimeService.createChangeActivityStateBuilder().moveSingleActivityIdToActivityIds`, `HistoryService.createHistoricDetailQuery().variableUpdates` | 0 / 19 | 1791 / 2658 (3146 / 3550) |
| GP-44 | Deemed outcome on timeout (RTI s7(2) deemed refusal opens the appeal) | **PASS** | **PASS** | none | none | 0 / 0 | 596 / 817 (3267 / 2756) |
| GP-45 | Pausable statutory clock (RTI s7(3) fee period excluded) with remaining-time resume | **PASS-WW** | **PASS-WW** | `RuntimeService.suspendProcessInstanceById`, `ManagementService.setJobDuedate`, `RuntimeService.activateProcessInstanceById` | `RuntimeService.suspendProcessInstanceById`, `ManagementService.rescheduleTimeDateJob`, `RuntimeService.activateProcessInstanceById` | 9 / 10 | 418 / 800 (2482 / 3076) |
| GP-46 | Third-party objection window (RTI s11 / LA s15): per-party message or window close | **PASS-WW** | **PASS-WW** | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | 12 / 12 | 1197 / 2068 (3850 / 3074) |
| GP-47 | Provisional result -> paid challenge window -> bulk recompute -> final | **PASS** | **PASS-WW** | none | none | 0 / 1 | 18187 / 25380 (2969 / 2082) |
| GP-48 | Ledger shared across instances and years (leave account) under concurrency | **PASS** | **PASS** | none | none | 0 / 0 | 7297 / 7197 (2783 / 2273) |
| GP-49 | Partial batch failure: per-item result, routing by reason code, resend only failures | **PASS-WW** | **PASS-WW** | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | 14 / 14 | 1807 / 2046 (3627 / 2000) |
| GP-50 | Return to the originating seat under the same identity (treasury objection loop) | **PASS** | **PASS** | none | none | 0 / 0 | 1409 / 2504 (2732 / 1881) |
| GP-51 | Hearing series with dates set by the court; brief deadline follows a reschedule | **PASS** | **PASS-WW** | `ManagementService.recalculateJobDuedate` | `RepositoryService.getBpmnModel`, `ManagementService.rescheduleTimeDateJob(expression)` | 0 / 18 | 1364 / 1808 (3971 / 2755) |
| GP-52 | Record lifecycle after closure: retention, reopen, review, weed | **PASS** | **PASS** | `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey`, `HistoryService.deleteHistoricProcessInstance` | `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey`, `HistoryService.deleteHistoricProcessInstance` | 0 / 0 | 1292 / 1363 (3121 / 2206) |
| GP-53 | Physical artefact (measurement book) custody alongside the digital flow | **PASS** | **PASS** | `HistoryService.createHistoricDetailQuery().variableUpdates` | `HistoryService.createHistoricDetailQuery().variableUpdates` | 0 / 0 | 909 / 1316 (3239 / 2309) |
| GP-54 | Case split / part / link: part files as children, live parent update, linked file | **PASS-WW** | **PASS** | `ProcessInstanceQuery.superProcessInstanceId`, `ProcessInstanceQuery.subProcessInstanceId`, `RuntimeService.setVariable`, `ProcessInstanceQuery.variableValueEquals` | `ProcessInstanceQuery.superProcessInstanceId`, `ProcessInstanceQuery.subProcessInstanceId`, `RuntimeService.setVariable`, `ProcessInstanceBuilder.referenceId/referenceType`, `ProcessInstanceQuery.processInstanceReferenceId/Type` | 4 / 0 | 1549 / 1515 (3893 / 2541) |
| GP-55 | Periodic verification campaign (GFR 213) that opens discrepancy cases | **PASS-WW** | **PASS-WW** | `TaskService.createTaskQuery().getExecutionId`, `RuntimeService.getVariable`, `RuntimeService.setVariablesLocal`, `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey` | `TaskService.createTaskQuery().getExecutionId`, `RuntimeService.getVariable`, `RuntimeService.setVariablesLocal`, `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey` | 4 / 5 | 1214 / 1447 (3842 / 2093) |

### E16-c workarounds

| ID | Engine | Workaround | Lines |
|---|---|---|---|
| GP-41 | flowable | no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to ManagementService.rescheduleTimeDateJob (evaluated against the job's execution) | 18 |
| GP-42 | flowable | conditional boundary event is not evaluated on variable change: the product must call RuntimeService.evaluateConditionalEvents(processInstanceId) after every change that can affect a condition | 3 |
| GP-43 | flowable | no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to ManagementService.rescheduleTimeDateJob (evaluated against the job's execution) | 18 |
| GP-43 | flowable | no 'start an activity' operation: the running activity gp43_execution is moved to {itself, gp43_extraItem} and therefore re-created (its task gets a new id) | 1 |
| GP-45 | operaton | remaining time (due - pausedAt) and net elapsed time are computed and kept by the product; neither engine keeps a paused-clock balance | 6 |
| GP-45 | operaton | re-dating the suspended timer and activating cannot share one transaction; re-date while suspended, then activate in a second transaction | 3 |
| GP-45 | flowable | remaining time (due - pausedAt) and net elapsed time are computed and kept by the product; neither engine keeps a paused-clock balance | 6 |
| GP-45 | flowable | a suspended timer cannot be re-dated (no effect or error); activate first and re-date in a second transaction, which leaves a window in which the overdue timer can fire | 4 |
| GP-46 | operaton | per-party correlation: the product finds the waiting execution whose loopCounter is the party and delivers to that execution (RuntimeService.messageEventReceived(name, executionId)); the adapter's correlateMessage(businessKey) is ambiguous with several branches waiting | 12 |
| GP-46 | flowable | per-party correlation: the product finds the waiting execution whose loopCounter is the party and delivers to that execution (RuntimeService.messageEventReceived(name, executionId)); the adapter's correlateMessage(businessKey) is ambiguous with several branches waiting | 12 |
| GP-47 | flowable | external-worker jobs are exclusive by default; with exclusive jobs a fetch of several jobs of ONE instance returns nothing (second process-instance lock fails, acquire rolled back), so the multi-instance task carries flowable:exclusive="false" (Flowable-only attribute) | 1 |
| GP-49 | operaton | per-item correlation: the product finds the waiting branch by its loopCounter and delivers to that execution, with the result as LOCAL variables of that branch (setVariablesLocal on the branch execution found by its local loopCounter + messageEventReceived, one transaction) | 14 |
| GP-49 | flowable | per-item correlation: the product finds the waiting branch by its loopCounter and delivers to that execution, with the result as LOCAL variables of that branch (setVariablesLocal on the branch execution found by its local loopCounter + messageEventReceived, one transaction) | 14 |
| GP-51 | flowable | no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to ManagementService.rescheduleTimeDateJob (evaluated against the job's execution) | 18 |
| GP-54 | operaton | no reference field on a process instance: the link is two variables (linkRefId, linkRefType) queried with variableValueEquals | 4 |
| GP-55 | operaton | per-branch result: the product writes physicalQty/bookQty as LOCAL variables of the branch (RuntimeService.setVariablesLocal on the task's execution) before completing, so concurrent completions never overwrite each other | 4 |
| GP-55 | flowable | per-branch result: the product writes physicalQty/bookQty as LOCAL variables of the branch (RuntimeService.setVariablesLocal on the task's execution) before completing, so concurrent completions never overwrite each other | 4 |
| GP-55 | flowable | external-worker jobs are exclusive by default; the multi-instance 'open case' task carries flowable:exclusive="false" so several branches' jobs can be fetched together | 1 |

### E16-c features and variants

| ID | Features | Vendor tokens (same on both engines) |
|---|---|---|
| GP-41 | message correlation by business key; event-based gateway (message + timer); timeDate timer from a variable; timer re-armed by the model (event-gateway loop); timer re-armed on the running instance (native); extension/expiry race | none |
| GP-42 | expression service task (aggregate); exclusive gateway on aggregate; non-interrupting conditional boundary event; call activity with in/out mapping; history of variable updates | gp42-cumulative.bpmn: expr(${execution.setVariable('cumulative', cumulative + billAmount)}); gp42-cumulative.bpmn: expr(${execution.setVariable('revisionPending', true)}); gp42-cumulative.bpmn: callio(in=cumulative,sanctioned;out=revisedSanction); gp42-cumulative.bpmn: expr(${execution.setVariable('sanctioned', revisedSanction)}); gp42-cumulative.bpmn: expr(${execution.setVariable('revisionPending', false)}) |
| GP-43 | non-interrupting message event sub-process; call activity in/out mapping; boundary timeDate timer; parent variable update from a running child (native); timer re-armed on the running instance (native); instance modification: start an extra activity (native); history of variable updates | gp43-contract.bpmn: callio(in=amendType,proposedCompletion,extraAmount,completionDate;out=approved,approvedCompletion,approvedExtra); gp43-contract.bpmn: expr(${execution.setVariable('completionDate', approvedCompletion)}); gp43-contract.bpmn: expr(${execution.setVariable('contractValue', contractValue + approvedExtra)}) |
| GP-44 | interrupting boundary timer (P30D); outcome as an event in history; event-based gateway appeal window | none |
| GP-45 | interrupting boundary timeDate timer; instance suspension (native); timer due date set on resume (native); remaining-time arithmetic | none |
| GP-46 | parallel multi-instance sub-process (loopCardinality); event-based gateway per party; per-branch message delivery (native, by loopCounter); timeDate window | none |
| GP-47 | timer-gated window; one instance per challenge with fee callback; parallel multi-instance external work (bulk); product result table in the engine transaction | gp47-answer-key.bpmn: ext(recompute;nonexclusive); probe-mi-external.bpmn: ext(probe-mi) |
| GP-48 | external work + product ledger in the engine transaction; BPMN error boundary on external work; 8 concurrent workers on one ledger row; idempotent debit; timer start event (yearly cycle) | gp48-leave.bpmn: ext(leave-debit); gp48-yearend.bpmn: ext(leave-carry-forward) |
| GP-49 | parallel multi-instance sub-process; per-item message delivery to one branch (native); local variable per branch (native); gateway routing by reason code; loop back with a smaller batch; product item table | gp49-dbt.bpmn: ext(dbt-send); gp49-dbt.bpmn: ext(dbt-close-round) |
| GP-50 | message correlation by business key; loop across a system boundary; expression counter | gp50-objection-loop.bpmn: ext(treasury-submit); gp50-objection-loop.bpmn: expr(${execution.setVariable('objectionCount', objectionCount + 1)}) |
| GP-51 | message-supplied timer dates; event-gateway loop (model re-arm of the hearing timer); boundary timer re-armed on the running instance (native); parallel branches; adjournment loop | none |
| GP-52 | long timer after closure; reopen by message during retention; review loop; history lookup after end (business key); history deletion (weeding) | none |
| GP-53 | gateway on a custody flag; interrupting message boundary on a sub-process (loss); history of custody changes | none |
| GP-54 | non-interrupting message event sub-process; call activity in/out mapping; parent update from running child (native); sub-instance query (native); linked sibling instance (native reference / variables); gate on open parts | gp54-main.bpmn: expr(${execution.setVariable('openParts', openParts + 1)}); gp54-main.bpmn: callio(in=fileNo,partSubject;out=partDecision); gp54-main.bpmn: expr(${execution.setVariable('openParts', openParts - 1)}) |
| GP-55 | timer start event (yearly cycle); parallel multi-instance verification; local variables per branch (native); conditional case creation by external work; cases outlive the campaign | gp55-verification.bpmn: ext(stock-register); gp55-verification.bpmn: ext(open-discrepancy;nonexclusive) |

## E17-c: domain × engine

### E17-c summary

| Engine | engineId | database | PASS | PASS-WITH-WORKAROUND | FAIL | UNSUPPORTED | workaround lines |
|---|---|---|---|---|---|---|---|
| operaton | operaton-2.2.0 | PostgreSQL (Testcontainers postgres:17-alpine, server 17.11) | 4 | 2 | 0 | 0 | 16 |
| flowable | flowable-8.0.0.0 | PostgreSQL (Testcontainers postgres:17-alpine, server 17.11) | 2 | 4 | 0 | 0 | 53 |

### E17-c matrix

| ID | Name | Operaton | Flowable | Operaton native APIs | Flowable native APIs | WA lines O / F | scenario ms O / F (engine boot ms O / F) |
|---|---|---|---|---|---|---|---|
| D-STORES-VERIFICATION | Annual stock verification (GFR 213) -> discrepancy cases -> survey board -> write-off / recovery | **PASS-WW** | **PASS-WW** | `TaskService.createTaskQuery().getExecutionId`, `RuntimeService.getVariable`, `RuntimeService.setVariablesLocal`, `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey` | `TaskService.createTaskQuery().getExecutionId`, `RuntimeService.getVariable`, `RuntimeService.setVariablesLocal`, `HistoryService.createHistoricProcessInstanceQuery().processInstanceBusinessKey` | 4 / 5 | 2864 / 3375 (2575 / 2028) |
| D-LAND-ACQUISITION | RFCTLARR: s4 SIA -> s11 -> s15 objections -> s19 -> s23 award -> s31, with statutory lapses | **PASS-WW** | **PASS-WW** | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | `RuntimeService.createExecutionQuery().messageEventSubscriptionName`, `RuntimeService.getVariable`, `RuntimeService.createExecutionQuery().variableValueEquals (local)`, `RuntimeService.setVariablesLocal`, `RuntimeService.messageEventReceived(messageName, executionId)` | 12 / 12 | 2773 / 2609 (4673 / 2347) |
| D-BANK-GUARANTEE | Contract with performance security as a long-lived child: extension, encashment, release, cancel | **PASS** | **PASS** | `ProcessInstanceQuery.superProcessInstanceId` | `ProcessInstanceQuery.superProcessInstanceId` | 0 / 0 | 2815 / 3094 (3434 / 3165) |
| D-CONTRACT-VARIATION | Running works contract: EoT (LD timer moves), extra item (CE level), LD levy and waiver | **PASS** | **PASS-WW** | `ProcessInstanceQuery.superProcessInstanceId`, `ManagementService.recalculateJobDuedate`, `HistoryService.createHistoricDetailQuery().variableUpdates` | `ProcessInstanceQuery.superProcessInstanceId`, `RepositoryService.getBpmnModel`, `ManagementService.rescheduleTimeDateJob(expression)`, `HistoryService.createHistoricDetailQuery().variableUpdates` | 0 / 18 | 2082 / 1923 (4470 / 1847) |
| D-AUDIT-PARA | ATN due in 3 months: reminder at 2, escalation at 3, return by audit on the same para | **PASS** | **PASS** | none | none | 0 / 0 | 1653 / 2120 (3594 / 1682) |
| D-COURT-CASE | Litigation with court-set hearings, reschedule, stay (suspends the impugned action), appeal, compliance | **PASS** | **PASS-WW** | `ManagementService.recalculateJobDuedate`, `RuntimeService.suspendProcessInstanceById`, `RuntimeService.activateProcessInstanceById` | `RepositoryService.getBpmnModel`, `ManagementService.rescheduleTimeDateJob(expression)`, `RuntimeService.suspendProcessInstanceById`, `RuntimeService.activateProcessInstanceById` | 0 / 18 | 3063 / 2890 (3977 / 2775) |

### E17-c workarounds

| ID | Engine | Workaround | Lines |
|---|---|---|---|
| D-STORES-VERIFICATION | operaton | per-branch verification result written as LOCAL variables of the branch (setVariablesLocal on the task's execution) before completion | 4 |
| D-STORES-VERIFICATION | flowable | per-branch verification result written as LOCAL variables of the branch (setVariablesLocal on the task's execution) before completion | 4 |
| D-STORES-VERIFICATION | flowable | multi-instance external task needs flowable:exclusive="false" (exclusive external-worker jobs of one instance cannot be fetched together) | 1 |
| D-LAND-ACQUISITION | operaton | per-landholder objection: delivered to the landholder's branch found by loopCounter (RuntimeService.messageEventReceived on that execution) | 12 |
| D-LAND-ACQUISITION | flowable | per-landholder objection: delivered to the landholder's branch found by loopCounter (RuntimeService.messageEventReceived on that execution) | 12 |
| D-CONTRACT-VARIATION | flowable | no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to ManagementService.rescheduleTimeDateJob (evaluated against the job's execution) | 18 |
| D-COURT-CASE | flowable | no recalculate operation: timer expression read from RepositoryService.getBpmnModel and passed to ManagementService.rescheduleTimeDateJob (evaluated against the job's execution) | 18 |

### E17-c features and variants

| ID | Features | Vendor tokens (same on both engines) |
|---|---|---|
| D-STORES-VERIFICATION | timer start event (yearly); parallel multi-instance verification with local results (native); case started per discrepancy by external work; committee multi-instance (all sign); stock ledger in the engine transaction | d-stores-verification.bpmn: ext(stock-register); d-stores-verification.bpmn: ext(open-discrepancy;nonexclusive); d-stores-discrepancy.bpmn: ext(stock-ledger-adjust) |
| D-LAND-ACQUISITION | statutory windows as interrupting boundary timers (12 months, calendar); per-landholder objection window (MI + event gateway); per-branch message delivery (native); lapse outcomes as end events | none |
| D-BANK-GUARANTEE | call activity (child BG, business key inherited); parallel execution + DLP timer; BG timers re-armed by the model; message correlation to the child by business key; parent cancel removes the child | d-bg-contract.bpmn: callbk; d-bg-contract.bpmn: callio(in=bgExpiry,reminderAt,bgAmount;bk) |
| D-CONTRACT-VARIATION | non-interrupting message event sub-process; approval chain as child with in/out mapping; gateway on the variation size (5 % / 90 days); LD boundary timer re-armed natively; history of variable updates | d-works-contract.bpmn: callio(in=varType,days,amount,contractValue,proposedCompletion;out=approved,approvedCompletion,approvedAmount,approvalLevel); d-works-contract.bpmn: expr(${execution.setVariable('completionDate', approvedCompletion)}); d-works-contract.bpmn: expr(${execution.setVariable('contractValue', contractValue + approvedAmount)}); d-works-contract.bpmn: expr(${execution.setVariable('ldWaived', true)}) |
| D-AUDIT-PARA | non-interrupting boundary timers P2M / P3M (calendar months) on a sub-process; return loop with counter; timers removed when the cycle completes | d-audit-para.bpmn: expr(${execution.setVariable('returnCount', returnCount + 1)}) |
| D-COURT-CASE | hearing dates from messages / recorded orders; event-gateway re-arm of the hearing timer; brief deadline re-armed natively; suspension of another instance (stay); appeal loop; compliance deadline | none |

PASS-WW = PASS-WITH-WORKAROUND. Native APIs are those recorded at run time per item (`nativeApis` in the JSON).
