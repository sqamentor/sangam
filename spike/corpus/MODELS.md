# Corpus models: how to drive them

The models implement SANGAM.md XI.3. Load them with `org.sangam.spike.corpus.Corpus`:

```java
byte[] bpmn = Corpus.bpmn("wf1-bill", "flowable");            // the variant if one exists, else the shared file
Map<String, byte[]> res = Corpus.resources("operaton",         // keys are engine-neutral: "wf1-bill.bpmn", ...
        "wf1-bill.bpmn", "wf1-delegation-of-powers.dmn");
adapter.deploy(tenantId, "wf1", res);
```

The process, decision or case **id** always equals the model name, so it is also the key you start with.
Engine-specific files and configuration (for example Operaton's history-time-to-live enforcement) are described in `VARIANTS.md`.
Conventions:

- Every decision variable is a String set when the user task is completed. An unset variable makes the gateway throw on both engines.
- Candidate groups come from the standard `potentialOwner` and mean **seats/posts** (IX.5). The person who holds the seat claims the task.
- Amounts are rupees as a Java number (`Long`), never a String.

## WF-1 Bill / payment, `wf1-bill` (variants: `wf1-bill.operaton.bpmn`, `wf1-bill.flowable.bpmn`) + `wf1-delegation-of-powers.dmn` (shared)

Pack P1, AC-01…AC-03. Deploy the BPMN and the DMN **in one deployment**.

**Start variables:**
- `billId` (String)
- `amount` (Long, INR)
- `beneficiary` (String)
- `budgetHead` (String, e.g. `"2059-MAINT"` or `"4059-CAPITAL"`)
- `initiatorSeat` (String group: the seat that revises the bill)
- `checkerGroup` (String group: the eligible checker seat)

**Flow:**
1. `wf1_start` → `wf1_reviewMergeGw` → user task **`wf1_checkerReview`** "Checker review" (candidate group `${checkerGroup}`).
2. Completing it requires `checkerDecision`:
   - `forward` → DMN task `wf1_delegationOfPowers`;
   - `return` → **`wf1_reviseBill`** "Revise bill" (group `${initiatorSeat}`), which loops back to the checker;
   - anything else → end **`wf1_rejectedEnd`** "Rejected".
3. The DMN `wf1-delegation-of-powers` (hit policy FIRST) reads `amount` and `budgetHead` and sets the process variables `approverLevel` and `maxAmount`:

   | Rule | Condition | `approverLevel` | `maxAmount` |
   |---|---|---|---|
   | R1 | `budgetHead == "4059-CAPITAL"` and amount > 50 lakh | `SECRETARY` | 1000000000 |
   | R2 | ≤ 1 lakh | `SECTION_OFFICER` | 100000 |
   | R3 | ≤ 10 lakh | `DDO` | 1000000 |
   | R4 | ≤ 1 crore | `HOD` | 10000000 |
   | R5 | > 1 crore | `SECRETARY` | 1000000000 |

4. Gateway `wf1_approverLevelGw` routes each of the four levels to user task **`wf1_approve`** "Approve", whose candidate group is the level name (`SECTION_OFFICER`, `DDO`, `HOD` or `SECRETARY`). Any other value goes to `wf1_noAuthorityEnd`.
5. Completing Approve requires `approverDecision`:
   - `approve` → external task **`wf1_payment`**, topic **`payment`**, → end **`wf1_paidEnd`** "Paid";
   - `return` → `wf1_reviseBill`;
   - anything else → `wf1_rejectedEnd`.

**Test points:**
- Amounts 100000 / 100001 / 1000000 / 1000001 / 10000000 / 10000001 hit the band edges.
- `budgetHead="4059-CAPITAL"` with 6000000 must give `SECRETARY` (R1 wins over R4 under FIRST).
- Start without `amount` to see the no-hit divergence (VARIANTS.md).

## WF-2 File movement, `wf2-file` (shared: `wf2-file.bpmn`)

Pack P2, AC-04.

**Start variables** (all String groups):
- `initiatorSeat`
- `l1Group`, `l2Group`, `l3Group`
- `sectionAGroup`, `sectionBGroup`
- `disposalGroup`

Optional: `fileId`, `subject`.

**Flow:**
1. `wf2_start` "Note created" → **`wf2_l1Review`** (group `${l1Group}`, decision `l1Decision`):
   - `forward` → L2;
   - `return` → **`wf2_reviseNote`** (group `${initiatorSeat}`) → back to L1.
2. **`wf2_l2Review`** (`${l2Group}`, `l2Decision`):
   - `forward` → L3;
   - `return` → L1.
3. **`wf2_l3Review`** (`${l3Group}`, `l3Decision`):
   - `forward` → parallel fork `wf2_commentsFork`;
   - `return` → L2.
4. The fork opens **`wf2_commentsSectionA`** (`${sectionAGroup}`) and **`wf2_commentsSectionB`** (`${sectionBGroup}`). Both join at `wf2_commentsJoin`.
5. **`wf2_finalDisposal`** (`${disposalGroup}`) → end **`wf2_disposedEnd`** "Disposed".

`lNDecision` must be exactly `forward` or `return`. There is no default flow, so any other value throws.

**AC-04 path** ("up 3, return 2, resubmit"): L1 forward, L2 forward, L3 return, L2 return, L1 forward, L2 forward, L3 forward, then both comments, then disposal.

For the join-concurrency test (VI.1), complete the two comment tasks concurrently.

## WF-3 Licence, `wf3-licence` (variants: `wf3-licence.operaton.bpmn`, `wf3-licence.flowable.bpmn`)

Pack P4, AC-06.

**Start variables:**
- `applicationId` (String)
- `applicantId` (String)
- `licenceType` (String)

These are informational. The groups are fixed names: `licence-scrutiny`, `licence-escalation`, `licence-inspector`, `licence-approver`.

**Flow:**
1. `wf3_start` → **`wf3_scrutiny`** "Scrutiny". Its non-interrupting boundary timer **`wf3_scrutinySlaTimer`** (`PT72H`) opens **`wf3_slaEscalation`** "SLA escalation", which leads to `wf3_escalationEnd` and only ends that token. Scrutiny stays open while the escalation is handled.
2. Completing Scrutiny requires `scrutinyDecision`:
   - `clarify` → message catch **`wf3_awaitApplicantResponse`**, message name **`applicantResponse`**. Correlate it by business key or process instance; the token then goes back to Scrutiny, and a new 72 h timer starts.
   - `accept` → **`wf3_inspection`**;
   - anything else → **`wf3_rejectedEnd`**.
3. Inspection → **`wf3_approval`**. Completing Approval requires `approvalDecision`:
   - `approve` → external task **`wf3_esign`**, topic **`esign`**, → external task **`wf3_digilockerPush`**, topic **`digilocker-push`**, → end **`wf3_issuedEnd`** "Licence issued";
   - anything else → `wf3_rejectedEnd`.

**Timer tests (E8):** move the engine clock past 72 h and run due jobs. Exactly one escalation task should appear per Scrutiny entry.

## WF-3 Licence case, `wf3-licence-case` (shared: `wf3-licence-case.cmmn`)

CMMN parity for XI.3. Case id **`wf3-licence-case`**. No variables are required.

The case plan `wf3c_casePlanModel` contains stage plan item `wf3c_pi_stage` (stage `wf3c_stage` "Licence processing", autoComplete), which holds:

1. Human task **`wf3c_pi_scrutiny`** (definition `wf3c_scrutiny`, "Scrutiny"). It is active at once.
2. Human task **`wf3c_pi_inspection`** (definition `wf3c_inspection`, "Inspection"). It becomes active when sentry `wf3c_sentry_scrutinyDone` (Scrutiny `complete`) is satisfied.
3. Milestone **`wf3c_pi_approved`** (definition `wf3c_approved`, "Approved"). It is reached when sentry `wf3c_sentry_inspectionDone` (Inspection `complete`) is satisfied.

When the milestone is reached, the stage and then the case complete.

The human tasks have **no candidate groups**, because those need extensions (VARIANTS.md). Find them by case instance id.

**Pass:** the case completes, and the history shows Scrutiny → Inspection → Approved, in that order, on both engines.

## GAP set (CF-13 / R34, experiment E11)

These are minimal models, one per element. No variables are needed unless stated. Each model has a *probe* user task after the element under test, so the experiment can assert "the token got past it". E11 records each element as: works / needs extension / needs engine patch.

| Model id (file) | Element under test | How to drive it | Pass |
|---|---|---|---|
| `gap-link-events` (`gap-link-events.bpmn`) | Link throw `gap_link_throw` → link catch `gap_link_catch`, link name `LinkB` | Start | Instance waits in `gap_link_probe` |
| `gap-signal-end` + `gap-signal-end-catcher` (`gap-signal-end.bpmn`, two processes) | Signal end event `gap_sigEnd_end`, signal name `gapSignal` | Deploy the file, start `gap-signal-end` | That instance has ended, **and** one `gap-signal-end-catcher` instance waits in `gap_sigEnd_probe` |
| `gap-message-end` (`gap-message-end.bpmn`) | Message end event `gap_msgEnd_end`, message `gapMessageEnd`, no implementation | Start | Deploys and ends. Record whether anything was sent or a hook exists |
| `gap-message-throw` (`gap-message-throw.bpmn`) | Message intermediate throw `gap_msgThrow_throw`, message `gapMessageThrow`, no implementation | Start | Instance waits in `gap_msgThrow_probe`. Record what the throw did |
| `gap-conditional-start` (`gap-conditional-start.bpmn`) | Process-level conditional start `gap_condStart_start`, `${feePaid == true}` | Engine-specific trigger (Operaton: `createConditionEvaluation().setVariable("feePaid", true).evaluateStartConditions()`); Flowable: record which API exists, if any | One instance waits in `gap_condStart_probe`; `feePaid=false` starts none |
| `gap-compensation-event-subprocess` (`gap-compensation-event-subprocess.bpmn`) | Compensation event sub-process `gap_comp_handler` inside sub-process `gap_comp_work` | Start, then complete `gap_comp_decide`. This reaches the compensation throw `gap_comp_throw` (activityRef `gap_comp_work`) | Task `gap_comp_undo` "Undo reservation" appears; completing it ends the instance |
| `gap-adhoc-subprocess` (`gap-adhoc-subprocess.bpmn`) | Ad-hoc sub-process `gap_adhoc_sub` with tasks `gap_adhoc_taskA` and `gap_adhoc_taskB` | Start; start the inner tasks on demand (Flowable: `executeActivityInAdhocSubProcess`); set `adhocDone=true` and complete one | Sub-process completes; instance waits in `gap_adhoc_probe` |
| `gap-io-mapping` (variants: `gap-io-mapping.operaton.bpmn`, `gap-io-mapping.flowable.bpmn`) | I/O mapping on external task `gap_io_mappedTask`, topic `io-mapping`: in `feeBase ← ${amount}`, out `ioResult ← workerResult` | Start with `amount` (Long); fetch-and-lock `io-mapping`; complete with `workerResult` | The worker sees `feeBase == amount`; then `ioResult == workerResult` and the instance waits in `gap_io_probe`. Also record the documented semantic differences (VARIANTS.md V-05) |
