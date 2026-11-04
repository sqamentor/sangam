# E1, E8, E10, E11: Flowable 8.0.0 vs Operaton 2.2.0

Run on 2026-11-04 on the dev host (Windows 11, 8 CPU, 32 GB, Docker 29), JDK 21.0.10 (Microsoft build), PostgreSQL 17.11
(`postgres:17-alpine`, Testcontainers 1.21.4). An H2 pre-run came first. Module: `spike/experiments` (see its README).
Command: `.\mvnw.cmd -q -pl experiments verify` (exit 0, 13.5 min).
Raw results: `E1.json`, `E8.json`, `E10.json`, `E11.json` in this folder.

Each engine runs in its own surefire execution (own JVM, own test class path) with the same test classes.
E8 also runs one JVM per time zone.
Every engine call goes through the `EngineAdapter` (`product-api`). Calls outside it go through the experiments' `EngineHooks`: history reads, GAP triggers, the bounded job runner for E8, and the no-validation deploy. These calls are listed in the JSON.

## E1: corpus coverage without engine patches

**Pass counts (scored scenarios; the 2 informational divergence runs are not scored):**

| | Flowable 8.0.0 | Operaton 2.2.0 |
|---|---|---|
| Scenarios passed | **17 / 17** | **16 / 17** |
| Engine patches needed | 0 | 0 |

### Scenario results

| Scenario | Flowable | Operaton |
|---|---|---|
| WF1 approve, 100000 → SECTION_OFFICER | pass | pass |
| WF1 approve, 100001 → DDO | pass | pass |
| WF1 approve, 1000000 → DDO | pass | pass |
| WF1 approve, 1000001 → HOD | pass | pass |
| WF1 approve, 10000000 → HOD | pass | pass |
| WF1 approve, 10000001 → SECRETARY | pass | pass |
| WF1 approve, 6000000 4059-CAPITAL → SECRETARY (R1 under FIRST) | pass | pass |
| WF1 return-and-revise (checker return, approver return, two revisions, then payment) | pass | pass |
| WF1 checker rejects | pass | pass |
| WF1 approver rejects | pass | pass |
| WF2 AC-04: forward 3, return 2, comments A+B join, dispose | pass | pass |
| WF2 return to initiator, then the full path | pass | pass |
| WF2 parallel comments completed concurrently (2 threads) | pass (0 conflicts) | pass (1 `OptimisticLockingException`, retried) |
| WF3 scrutiny, SLA timer, clarify message, second SLA, inspection, approval, eSign + DigiLocker external work | pass | pass |
| WF3 scrutiny rejects (the pending SLA timer is cancelled) | pass | pass |
| WF3 accept, inspection, approval rejects | pass | pass |
| WF3 CMMN case (startCase, Scrutiny, Inspection, milestone, case completes, history order) | pass | **fail** (see below) |
| *info:* WF1 without `amount` | DMN task throws `PropertyNotFoundException: Cannot resolve identifier 'amount'` | ends at `wf1_noAuthorityEnd` |
| *info:* WF1 with `amount = null` | gateway throws `Cannot resolve identifier 'approverLevel'` | ends at `wf1_noAuthorityEnd` |

Every payment, eSign and DigiLocker step fetched with `lockVersion` ≥ 1 and an `attemptId`. A completion with `lockVersion − 1` was rejected with `StaleLeaseException`, and so was one from a non-owner. The completion with the right version then succeeded, on both engines.

WF-3 SLA: exactly one escalation per Scrutiny entry, `[1, 1]`, on both engines. None at 71 h, one at 73 h, and still one 100 h later.

The DMN result type differs: `maxAmount` is a `Double` on Flowable (`100000.0`) and a `Long` on Operaton (`100000`). VARIANTS.md already records this.

**Operaton CMMN fail, exactly what happens.** The case behaves correctly at run time:
- Scrutiny comes first.
- Inspection becomes active after the sentry.
- The milestone is reached and the case completes.

But Operaton's CMMN history (`HistoricCaseActivityInstance`) records **Inspection as `terminated`, not `completed`**, although `TaskService.complete` was called on it. The recorded end order is still Scrutiny → Inspection → Approved.
The pass criterion (MODELS.md: "the history shows Scrutiny → Inspection → Approved") is applied strictly here, with each item recorded as completed, so the scenario fails. With a lenient reading ("ended in that order") it would pass.
Flowable records all three as `completed`.

### Elements and features that needed engine-specific handling (E1)

| Item | Flowable | Operaton |
|---|---|---|
| V-01: DMN call from BPMN (`wf1_delegationOfPowers`) | variant file | variant file |
| V-02: external task `payment` | variant file | variant file |
| V-03: external task `esign` | variant file | variant file |
| V-04: external task `digilocker-push` | variant file | variant file |
| Engine configuration | DMN and CMMN configurators registered | `enforceHistoryTimeToLive=false` (otherwise ENGINE-12018 on every shared model) |
| Job execution | async executor off; jobs run by `executeDueJobs` | job executor off; `ClockUtil` is JVM-wide |
| Run time: after `completeExternal` the instance moves on only through an **async job** (`executeDueJobs` needed; seen 10 times: payment ×8, esign, digilocker-push) | yes | no (synchronous) |
| **Total, engine behaviour** | **7** (4 variant elements + 2 configuration + 1 run time) | **6** (4 + 2 + 0) |

Variant files used: `wf1-bill.<engine>.bpmn` and `wf3-licence.<engine>.bpmn`.
Shared files: `wf1-delegation-of-powers.dmn`, `wf2-file.bpmn` and `wf3-licence-case.cmmn`.

A case task's `taskDefinitionKey` is the humanTask id on Flowable (`wf3c_scrutiny`) and the plan item id on Operaton (`wf3c_pi_scrutiny`). This was already known (ledgers).

**Adapter defect found (not an engine limit; not fixed, I don't own the adapter).**
- What fails: `FlowableEngineAdapter.correlateMessage(tenant, "applicantResponse", businessKey, …)` matches **0** executions.
- Why: it uses `ExecutionQuery.processInstanceBusinessKey(bk)`. In Flowable's `Execution.xml` that compares the execution's *own* `BUSINESS_KEY_`, unless `includeChildExecutionsWithBusinessKeyQuery` is set. The message catch is a child execution, whose business key is null.
- Fix: one line, `processInstanceBusinessKey(businessKey, true)`.
- Workaround in E1: correlate with `businessKey = null`, which is safe because exactly one execution was waiting. It is recorded as a workaround in `WF3-FULL-SLA-CLARIFY-ISSUE`.
- The adapter-flowable tests have no correlation test, which is why this was not caught.

## E11: BPMN gap set

Classification rule:
- **works**: it deploys through the adapter and the runtime assertion passes, with no extra code (measured).
- **needs extension**: possible through public extension points or the product layer (listeners, a model rewrite before deploy, a product-side trigger), with no engine source change (assessed).
- **needs engine patch**: needs new engine parse or behaviour code (assessed).
- **unsupported**: no realistic route.

Rows other than "works" are assessments based on the measured failure.
On Flowable, a rejected model was also deployed with `disableBpmnValidation()` as a diagnostic.

| Element (model) | Flowable 8.0.0: deploy | Flowable: runtime | **Flowable class** | Operaton 2.2.0: deploy | Operaton: runtime | **Operaton class** |
|---|---|---|---|---|---|---|
| Link throw/catch (`gap-link-events`) | **rejected**: `flowable-intermediate-catch-event-no-eventdefinition` (the 8.0.0 model has no `LinkEventDefinition`). Without validation it deploys, but the instance just ends and the probe is never reached | — | **needs extension** (link pair → sequence flow rewrite before deploy) | OK | probe reached | **works** |
| Signal end event (`gap-signal-end`) | OK | thrower ends, **0 catcher instances** (behaves as a none end). A signal broadcast through the API does start 1 catcher | **needs extension** (end listener → `signalEventReceived`) | OK | thrower ends, 1 catcher at probe | **works** |
| Message end, no implementation (`gap-message-end`) | OK | ends; nothing sent; no implementation attribute (hook would be a listener) | **works** (per criterion) | OK | ends; no-op; sending would need `operaton:class`/`expression`/`type=external` on the event | **works** (per criterion) |
| Message intermediate throw (`gap-message-throw`) | **rejected**: `flowable-throw-event-invalid-eventdefinition`. Without validation: passes through to the probe correctly | — | **needs extension** (send/service task, or deploy without validation) | OK | passes through to probe (no-op) | **works** |
| Process-level conditional start (`gap-conditional-start`) | **rejected**: `flowable-start-event-invalid-event-definition`. Without validation it deploys, but there is **no API** to evaluate start conditions (`evaluateConditionalEvents` is per running instance only) | — | **needs extension** (product layer evaluates the condition and starts the process) | OK | `feePaid=false` → 0 instances, `true` → 1 at probe | **works** |
| Compensation event sub-process (`gap-compensation-event-subprocess`) | **rejected**: `flowable-event-subprocess-invalid-start-event-definition` (only error/timer/message/signal). Without validation: the throw does nothing, `gap_comp_undo` never appears and the instance ends | — | **needs engine patch** (or a model change to a compensation boundary event) | OK | `gap_comp_undo` appears; completing it ends the instance | **works** |
| Ad-hoc sub-process (`gap-adhoc-subprocess`) | OK | both tasks enabled; started via `executeActivityInAdhocSubProcess`; `adhocDone=true` completes it, B cancelled, probe reached | **works** | **rejected**: `ENGINE-09005 Invalid destination 'gap_adhoc_sub' of sequence flow` (`adHocSubProcess` not parsed; no switch to skip validation) | — | **needs engine patch** (new parse path + behaviour; CMMN discretionary items are the native substitute) |
| I/O mapping on external task (`gap-io-mapping`, variant V-05) | OK | worker sees **only** `feeBase`; after completion `ioResult=R-42`, `workerResult`/`other` **not** copied | **works** | OK | worker sees `amount` + `feeBase`; after completion `ioResult`, `workerResult` and `other` all present, `feeBase` gone (local) | **works** |

| Count | Flowable | Operaton |
|---|---|---|
| works | 3 | 7 |
| needs extension | 4 | 0 |
| needs engine patch | 1 | 1 |
| unsupported | 0 | 0 |

## E8: `R/PT1H` cycle across DST (T22)

Setup:
- Inline model: a user task with a non-interrupting boundary `timeCycle R/PT1H`; each fire creates one `e8_tick` task.
- The clock moves with `setClock` in 5-minute steps (also 1-minute steps for Berlin); due jobs run after each step.
- One JVM per zone (`-Duser.timezone`).
- Job runner: the adapter's `executeDueJobs` steps, capped at 20 job executions per step (runaway guard).

Pass = exactly one fire per elapsed hour, each inside its own interval.

| Window | Expected | Flowable 8.0.0 | Operaton 2.2.0 |
|---|---|---|---|
| Asia/Kolkata, 24 h from 00:00 IST (no DST), 5-min steps | 24 | **24**, all on the hour, 60 min apart: **pass** | **24**, same: **pass** |
| Europe/Berlin autumn fold 2026-10-25 (03:00 CEST → 02:00 CET at 01:00Z), 6 h, 5-min steps | 6 | **runaway: 21** by 02:00 CEST (00:00Z). The guard stopped the window there | **runaway: 21**, same point |
| Same, 1-min steps | 6 | **runaway: 21** | **runaway: 21** |
| Europe/Berlin spring gap 2026-03-29 (02:00 CET → 03:00 CEST at 01:00Z), 6 h, 5-min steps | 6 | **6**: 01:00 CET, then 03:00, 04:00, 05:00, 06:00, 07:00 CEST (60 min apart): **pass** | **6**, same: **pass** |
| Same, 1-min steps | 6 | 6: pass | 6: pass |
| *info:* single jump over the window + one job run (Kolkata / fold / gap) | — | 1 fire (missed fires collapse into 1) | 1 fire |

**Autumn fold detail, the same on both engines.**
- The first fire at 01:00 CEST (23:00Z) is correct.
- At 02:00 CEST (00:00Z) the timer fires repeatedly within one step: 20 fires in that step, up to the cap.
- The stored repeat expressions differ: Flowable stores `R/2026-10-24T22:00:00Z/PT1H` (UTC); Operaton stores `R/2026-10-25T00:00:00/PT1H`, a **local time with no offset**.
- Without the cap, the adapters' own bounds (100 rounds on Flowable, 10,000 executions on Operaton per call) let one `executeDueJobs` spin for minutes. That is how it was first seen.

**T22 result: FAIL on both engines** at the autumn fold. It passes at the spring gap and in the no-DST zone.
Mitigation candidates (not tested):
- run the engine JVM in UTC;
- use a product-side cycle calculation.

A harness note found on the way: Operaton caches the JVM default zone at class initialisation (`DateTimeUtil.JVM_DEFAULT_DATE_TIME_ZONE`, static final). Switching `TimeZone.setDefault` inside a running JVM shifted Operaton's cycles by 30 minutes, because they stayed on the IST grid. That is why each zone runs in its own JVM.

## E10: platform fit (lite)

| Item | Flowable 8.0.0 | Operaton 2.2.0 |
|---|---|---|
| JDK used | Microsoft OpenJDK 21.0.10+7-LTS | same |
| Starts, deploys and runs a process on JDK 21 + PostgreSQL 17 | **yes** | **yes** |
| JDK 25 | **not tested** (not installed; not installed on purpose) | **not tested** |
| LIC-1 GraalJS (`org.graalvm.polyglot:js`, `org.graalvm.js:*`) on the runtime class path | absent | absent (operaton-engine declares it test-scope only; the adapter also excludes it) |
| LIC-2 `ojdbc*` | absent | absent |
| LIC-3 `mysql-connector*` | absent | absent |
| LIC-4 `db2jcc*` | absent | absent |
| Jackson (adapter runtime, `dependency:list -DincludeScope=runtime`) | **Jackson 3**: `tools.jackson.core` databind/core 3.0.1, plus `com.fasterxml.jackson.core:jackson-annotations` 2.20. No Jackson 2 databind | **Jackson 2**: databind/core 2.22.1, annotations 2.22, `jackson-module-scala_2.13` 2.22.1 (from FEEL 1.22.1) |
| Spring, transitive (runtime) | **spring-beans + spring-core 7.0.3**, via `flowable-dmn-engine`. The adapter excludes spring-beans only from `flowable-engine`, so the ledger's "no Spring on the class path" is not true at runtime | spring-beans + spring-core 7.0.10 (compile dependency of operaton-engine) |
| Spring context | none | none |
| Other notes | `jcl-over-slf4j` **and** `commons-logging` 1.3.5 both on the runtime path (duplicate `org.apache.commons.logging` classes) | `commons-logging` 1.3.5 only |
| MyBatis | 3.5.19 | 3.5.19 |
| JSR-223 script engines available | juel only | juel, feel-scala (no JavaScript) |

The checks ran on:
- the test class path of each engine JVM (classes and jars);
- `experiments/target/e10/tree-*.txt` and `runtime-*.txt`, the outputs of `dependency:tree` and `dependency:list` for each adapter and for this module.

**Class path conflict when both adapters share one Maven module.** Maven mediates `jackson-annotations` to 2.20, Flowable's version. Operaton's databind 2.22.1 then fails at engine build with `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`.
Fixed in `experiments/pom.xml` by importing `jackson-bom` 2.22.1, and the engines run in separate JVMs.
Profiles `-Pflowable` / `-Poperaton` give each adapter's own mediation.

## Preliminary score: "Coverage 30 %" bucket (E1 + E11)

Method (stated so it can be challenged):
- **E1 rate** = scored E1 scenarios passed / scored scenarios (informational runs excluded). No engine patch was needed on either engine, so no patch deduction applies here; patches are the 25 % bucket. The engine-specific handling counts (7 vs 6) are almost equal and are not deducted.
- **E11 rate** = (works × 1 + needs extension × 0.5 + needs engine patch × 0 + unsupported × 0) / 8.
- **Coverage** = 30 × (0.5 × E1 rate + 0.5 × E11 rate). E1 and E11 have equal weight.

| | E1 rate | E11 rate | **Coverage (of 30)** |
|---|---|---|---|
| Flowable 8.0.0 | 17/17 = 1.000 | (3 + 4 × 0.5)/8 = 0.625 | **24.4** |
| Operaton 2.2.0 | 16/17 = 0.941 | (7 + 0)/8 = 0.875 | **27.2** |

Sensitivity:
- With the lenient CMMN reading (Operaton E1 17/17), Operaton scores **28.1**.
- With "needs extension" worth 0, Flowable scores 30 × (0.5 + 0.5 × 3/8) = **20.6**.
- In every variant, Operaton leads this bucket by about 3–7.5 points, driven by E11. E1 is near parity.

E8 (fold runaway on both engines) belongs to the failure-test bucket and is not scored here.

## Problems and limits

1. **Flowable adapter `correlateMessage` by business key is broken** for any message catch in a child execution (see E1). One-line fix: `processInstanceBusinessKey(bk, true)`. Owner: adapter-flowable.
2. **Flowable adapter ledger:** "no Spring on the class path" is not true at runtime. spring-beans/core 7.0.3 still come in through `flowable-dmn-engine`. `jcl-over-slf4j` and `commons-logging` are both present.
3. **Both adapters:** `executeDueJobs` has no guard against a timer that keeps refiring. At the autumn fold this means 100 rounds per call on Flowable and 10,000 executions per call on Operaton. With a real job executor it would be a hot loop.
4. **Not covered here:**
   - the eSign/DigiLocker simulators (external work was completed directly);
   - Oracle and SQL Server;
   - JDK 25 (not installed);
   - minor-version upgrade with active instances and the LIC-11 licence inventory (E10 full).
5. E11 classifications other than "works" are assessments. None of the extensions was built.
