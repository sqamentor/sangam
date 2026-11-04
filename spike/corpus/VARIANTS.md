# Corpus variants (evidence for E1, portability)

Every model under `src/main/resources/models/` is shared by both engines unless this file lists it.
A model gets an engine variant **only** when an element cannot be written in standard
BPMN 2.0.2 / DMN 1.3 / CMMN 1.1 so that the engine executes it. A variant comes as a pair:
`<name>.operaton.<ext>` and `<name>.flowable.<ext>`. Apart from the rows below, the two files of a
pair are identical: same element ids (enforced by `CorpusTest.variantPairsDeclareTheSameElementIds`)
and same standard XML.

Engine versions: **Operaton 2.2.0** and **Flowable 8.0.0**. Attribute and element names were read from
the reference source clones (`reference/flowable-engine` 8.1.0-SNAPSHOT, `reference/operaton` 2.3.0-SNAPSHOT)
and from the 8.0.0 `flowable-bpmn-converter` jar.

**Smoke check (2026-11-04).** Every file was deployed and run on the **released** engines: `flowable-engine` 8.0.0 with the DMN and CMMN configurators, and `operaton-engine` 2.2.0, both embedded on H2 in-memory.
- The harness was a throw-away scratch project outside the repository, so it is not part of the build.
- The results are in "Smoke results" at the end of this file.
- They are a pre-check only, not the E1/E11 result. E1/E11 must rerun on PostgreSQL through the adapters.

## Summary

| | Count |
|---|---|
| Variant pairs (logical models with two files) | **3**: `wf1-bill`, `wf3-licence`, `gap-io-mapping` |
| Variant files | **6** |
| Elements that differ (rows below) | **5**: 1 DMN call, 3 external tasks, 1 I/O mapping |
| Shared models | 10: `wf2-file.bpmn`, `wf1-delegation-of-powers.dmn`, `wf3-licence-case.cmmn`, and 7 `gap-*.bpmn` |

Each variant file also declares exactly one vendor namespace on `<definitions>`: Operaton
`xmlns:operaton="http://operaton.org/schema/1.0/bpmn"` and Flowable `xmlns:flowable="http://flowable.org/bpmn"`.
Shared files declare no vendor namespace. `CorpusTest.vendorNamespacesOnlyInTheirOwnVariant` enforces both rules.

## Element variants

| ID | Files | Element id | What it is | Why it cannot be shared | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|---|---|---|---|
| **V-01** | `wf1-bill.operaton.bpmn` / `wf1-bill.flowable.bpmn` | `wf1_delegationOfPowers` | DMN call from BPMN (delegation of powers) | BPMN 2.0.2 `businessRuleTask` has only `implementation` (a URI). There is no standard attribute that names a DMN decision, and no standard mapping of the decision result to variables. The engines also differ in **element type**: in Flowable, `businessRuleTask` is the rules task (`flowable:ruleVariablesInput` / `flowable:rules` / `flowable:class`), not DMN. Flowable calls DMN from a `serviceTask`. They differ in **result shape** too. Operaton stores the result only as the local variable `decisionResult` of the (scope) activity. With a single hit, Flowable writes each output column as a process variable | `<businessRuleTask operaton:decisionRef="wf1-delegation-of-powers">` with `<operaton:inputOutput>`: `<operaton:outputParameter name="approverLevel">${decisionResult.getSingleResult().approverLevel}</operaton:outputParameter>` and `<operaton:outputParameter name="maxAmount">${decisionResult.getSingleResult().maxAmount}</operaton:outputParameter>`. No `operaton:resultVariable` / `operaton:mapDecisionResult`. **Use the method call**: `decisionResult` is a `java.util.List` (`DmnDecisionResult`), so the property form `${decisionResult.singleResult…}` goes to the List EL resolver as an index and fails with `NumberFormatException: For input string: "singleResult"` (seen in the smoke check) | `<serviceTask flowable:type="dmn">` with `<flowable:field name="decisionTableReferenceKey"><flowable:string>wf1-delegation-of-powers</flowable:string></flowable:field>` and `<flowable:field name="decisionTaskThrowErrorOnNoHits"><flowable:string>false</flowable:string></flowable:field>`. Outputs `approverLevel` and `maxAmount` become process variables (single result, hit policy FIRST) |
| **V-02** | `wf1-bill.operaton.bpmn` / `wf1-bill.flowable.bpmn` | `wf1_payment` | External task, topic `payment` | BPMN 2.0.2 `serviceTask@implementation` is a URI (default `##WebService`). There are no standard pull-worker or topic semantics | `<serviceTask operaton:type="external" operaton:topic="payment"/>` | `<serviceTask flowable:type="external-worker" flowable:topic="payment"/>` |
| **V-03** | `wf3-licence.operaton.bpmn` / `wf3-licence.flowable.bpmn` | `wf3_esign` | External task, topic `esign` | Same as V-02 | `<serviceTask operaton:type="external" operaton:topic="esign"/>` | `<serviceTask flowable:type="external-worker" flowable:topic="esign"/>` |
| **V-04** | `wf3-licence.operaton.bpmn` / `wf3-licence.flowable.bpmn` | `wf3_digilockerPush` | External task, topic `digilocker-push` | Same as V-02 | `<serviceTask operaton:type="external" operaton:topic="digilocker-push"/>` | `<serviceTask flowable:type="external-worker" flowable:topic="digilocker-push"/>` |
| **V-05** | `gap-io-mapping.operaton.bpmn` / `gap-io-mapping.flowable.bpmn` | `gap_io_mappedTask` | Input/output mapping on an activity (GAP set). It sits on an external task, topic `io-mapping`, the only activity type where Flowable offers I/O mapping (call activities aside) | BPMN 2.0.2 `ioSpecification` / `dataInputAssociation` are not executed as variable mappings by either engine. Each engine has its own extension. **Their semantics differ.** Operaton: `inputParameter` creates a **local** variable on the activity, and `outputParameter` is evaluated when the activity ends; the completion variables are also written as usual. Flowable: in-parameters are evaluated when the job is **acquired** and **replace** the variable set sent to the worker, without creating an execution variable; out-parameters **replace** the default "copy every completion variable" behaviour. Flowable has no I/O mapping on user, script or plain service tasks (a GAP finding for E11) | `<serviceTask operaton:type="external" operaton:topic="io-mapping">` with `<operaton:inputOutput>`: `<operaton:inputParameter name="feeBase">${amount}</operaton:inputParameter>` and `<operaton:outputParameter name="ioResult">${workerResult}</operaton:outputParameter>` | `<serviceTask flowable:type="external-worker" flowable:topic="io-mapping">` with `<flowable:externalWorkerInParameter sourceExpression="${amount}" target="feeBase"/>` and `<flowable:externalWorkerOutParameter source="workerResult" target="ioResult"/>` |

### Known behaviour differences that remain inside V-01

| Case | Operaton | Flowable | Effect in `wf1-bill` |
|---|---|---|---|
| `amount` variable **absent** | No hit. `getSingleResult()` is null, so the output mapping sets `approverLevel` = null and `maxAmount` = null. The gateway default ends the instance at `wf1_noAuthorityEnd` (smoke-checked) | The DMN task **fails**: `PropertyNotFoundException: Cannot resolve identifier 'amount'`, because the JUEL rewrite needs the variable to exist (smoke-checked) | Divergence. Always start `wf1-bill` with `amount` |
| `amount` = **null** | Same as absent: ends at `wf1_noAuthorityEnd` (smoke-checked) | No hit, so no variable is set (`decisionTaskThrowErrorOnNoHits=false`). The gateway then fails: `Unknown property used in expression: ${approverLevel == 'SECTION_OFFICER'}` (smoke-checked) | Divergence. E1 records both cases |
| Type of `maxAmount` | `java.lang.Long` (for example `100000`; smoke-checked). Output `typeRef="number"` is not an Operaton type, so the identity transformer keeps the FEEL integer | `java.lang.Double` (for example `100000.0`; smoke-checked), because `typeRef="number"` converts to Double | Compare `maxAmount` numerically, never with `equals` on the boxed type |
| Variable scope of the outputs | Written by output mapping to the parent (process) scope | Written with `execution.setVariable` (process scope) | Same visible result |

## Not model variants: engine configuration the adapters must set

These items do not change the model XML, so they have no rows above, but E1 must count them as configuration workarounds.

| Item | Engine | What happens with the corpus as written | Adapter setting |
|---|---|---|---|
| History time to live is enforced | Operaton 2.2.0 (`ProcessEngineConfigurationImpl.enforceHistoryTimeToLive = true` by default) | Every process, decision and case without `operaton:historyTimeToLive` **fails to deploy** (smoke-checked: `ENGINE-12018 History Time To Live (TTL) cannot be null` for `wf2-file.bpmn`, `wf1-delegation-of-powers.dmn` and `wf3-licence-case.cmmn`). The corpus does not carry the attribute on purpose: it would be a vendor extension in every shared file | Set a default `historyTimeToLive` (for example `"P180D"`), or set `enforceHistoryTimeToLive=false` |
| DMN and CMMN engines | Flowable 8.0.0 | The DMN task (V-01) and `wf3-licence-case.cmmn` need the DMN and CMMN engines next to the process engine | Register the DMN and CMMN engine configurators |
| Deployment resource names | both | The loaded file may be `wf1-bill.flowable.bpmn`, but both engines should see `wf1-bill.bpmn` | Use `Corpus.resources(engine, "wf1-bill.bpmn", ...)`, which keys by the engine-neutral name |
| Transaction boundaries | both | No `asyncBefore` / `asyncAfter` (Operaton) or `flowable:async` is used, so each command runs until the next wait state (user task, external task, message catch, timer) | None. Add async only as a recorded variant |

## Format findings (shared files, no variant needed)

| ID | Engine | Finding | Rule applied in the corpus |
|---|---|---|---|
| **F-01** | Flowable 8.0.0 | `DmnXMLConverter.getTargetNameSpace` reads only the **first StAX event** after the XML declaration to choose the XSD (DMN 1.3, 1.2 or 1.1). If an XML comment comes before `<definitions>`, it finds no namespace and validates against the **DMN 1.1** XSD. Deployment then fails with `SAXParseException: cvc-elt.1.a: Cannot find the declaration of element 'definitions'` (smoke-checked). Operaton is not affected | In DMN files, nothing may come between the XML declaration and the root element; comments go inside `<definitions>`. Enforced by `CorpusTest.dmnFilesHaveNothingBeforeTheRootElement` |

## Standard constructs chosen on purpose instead of extensions (shared)

| Need | Standard construct used | Operaton parse path | Flowable parse path | Caveat |
|---|---|---|---|---|
| Candidate group from a variable (`checkerGroup`, approver level, seats) | `<potentialOwner><resourceAssignmentExpression><formalExpression>group(${var})</formalExpression>…` | `BpmnParse.parsePotentialOwnerResourceAssignment` → `addCandidateGroupIdExpression` | `UserTaskXMLConverter.PotentialOwnerParser` → `candidateGroups` (evaluated as an expression at runtime) | Do not use commas inside the expression (both engines split the text on commas). No prefix means group on both engines |
| Gateway conditions | `<conditionExpression xsi:type="tFormalExpression">${x == 'v'}</conditionExpression>` (JUEL) | JUEL | JUEL | An **unset** variable throws on both engines. Always set the decision variables (`checkerDecision`, `l1Decision`, …) when completing the task |
| SLA timer | `<boundaryEvent cancelActivity="false">` + `<timerEventDefinition><timeDuration xsi:type="tFormalExpression">PT72H</timeDuration>` | standard | standard | — |
| Applicant clarification | `<message name="applicantResponse"/>` + `intermediateCatchEvent/messageEventDefinition@messageRef` | correlated by message name | correlated by message name | — |
| Ad-hoc completion | `completionCondition` `${execution.getVariable('adhocDone') == true}` | — | — | `execution.getVariable` avoids an unknown-property error while the variable is unset |

## Not used, because only an extension could express them

- CMMN human-task candidate groups (`flowable:candidateGroups`, `operaton:candidateGroups` on `humanTask`). `wf3-licence-case.cmmn` therefore has unassigned tasks; query them by case instance.
- `operaton:historyTimeToLive` on process, decision and case (see the configuration table above).
- Form keys, task listeners, execution listeners, async continuations, and `operaton:resultVariable` / `operaton:mapDecisionResult`.
- Implementations on the message end and message throw events (GAP set). The models test the engines' behaviour **without** an implementation.

## FEEL / JUEL compatibility (the DMN is shared)

How each engine evaluates `wf1-delegation-of-powers.dmn`:

- **Operaton 2.2.0** uses FEEL by default for input expressions, input entries and output entries (`DefaultDmnEngineConfiguration`: `defaultInputExpressionExpressionLanguage`, `defaultInputEntryExpressionLanguage` and `defaultOutputEntryExpressionLanguage` default to the FEEL namespace).
- **Flowable 8.0.0** has no FEEL engine for decision tables. It **rewrites** each input entry into JUEL: `ELInputEntryExpressionPreParser` produces `#{<inputExpression> <entry>}`, and an entry that does not start with `==`, `!=`, `<`, `>`, `>=` or `<=` gets ` == ` prepended. An empty entry or `-` matches everything (`RuleEngineExecutorImpl`). Each output entry becomes `#{<entry>}` (`ELOutputEntryExpressionPreParser`). An entry that already contains `${…}` / `#{…}` is passed through unchanged.

The common subset used, which means the same thing on both engines:

| Construct | Example in the table | FEEL (Operaton) | JUEL rewrite (Flowable) |
|---|---|---|---|
| Comparison unary test | `<= 100000`, `> 10000000` | unary test | `#{amount <= 100000}` |
| String literal unary test | `"4059-CAPITAL"` | equality | `#{budgetHead == "4059-CAPITAL"}` |
| Any | `-` | matches all | matches all |
| String output | `"SECTION_OFFICER"` | string | `#{"SECTION_OFFICER"}` → String |
| Integer output | `100000` | number | `#{100000}` → Long, then `typeRef="number"` → Double |

Constructs to **avoid** in shared DMN, and why:

| Construct | FEEL (Operaton) | Flowable |
|---|---|---|
| `== x`, `!= x` | Not in the DMN 1.3 FEEL unary-test grammar, which uses a plain literal for equality and `not(x)` for inequality. Whether the Operaton FEEL engine accepts these as an extension was not verified, so do not rely on it | Valid (JUEL) |
| `not("A")` | Valid | Becomes `#{v == not("A")}` → EL error |
| Ranges `[1..10]`, `]1..10[` | Valid | Becomes `#{v == [1..10]}` → EL parse error |
| Lists `"A","B"` | Valid (disjunction) | Becomes `#{v == "A","B"}` → EL parse error |
| `${…}` / `#{…}` inside an entry | FEEL parse error | Passed through as raw JUEL |
| Single-quoted strings `'A'` | Not FEEL | Valid JUEL |
| Dates, `date("…")`, FEEL functions | Valid | Not FEEL. Flowable has its own `fn_date`, `fn_now` and similar rewrites |
| `typeRef` other than `string` / `boolean` / `date` | Operaton knows `integer`, `long`, `double`; anything else (including DMN's `number`) logs a warning and uses identity | Flowable knows `number` (→ Double); others are not converted |
| String-typed numbers (`amount = "50000"`) | FEEL does not coerce, so a number test does not match | JUEL coerces, so it matches. **Always pass `amount` as a Java number** |

BPMN expressions (`${…}` in conditions, candidate groups, I/O mapping) are JUEL on both engines.
Both expose the `execution` bean, so `${execution.getVariable('x')}` is portable.

## Smoke results (2026-11-04, released engines, embedded H2, scratch harness outside the repo)

Configuration:
- Operaton: `enforceHistoryTimeToLive=false`.
- Flowable: DMN and CMMN configurators registered.
- Both: job executor off; jobs and timers executed by hand.

**Pre-check only; E1/E11 are the measured results.**

| Model | Operaton 2.2.0 | Flowable 8.0.0 |
|---|---|---|
| `wf1-bill` + DMN | Deploys. Bands correct: 50000→SECTION_OFFICER, 100001→DDO, 5000000→HOD, 6000000/`4059-CAPITAL`→SECRETARY (R1 under FIRST), 20000000→SECRETARY. The Approve candidate group equals the level. The return loop, reject and `payment` external task all work; the instance ends | Same results, with `maxAmount` as Double (see V-01 table) |
| `wf2-file` | AC-04 path (L1 forward, L2 forward, L3 return, L2 return, L1 forward, L2 forward, L3 forward, then both comments in parallel, then disposal) runs and ends. Candidate groups as expected | Same |
| `wf3-licence` | Clarify, then message `applicantResponse` correlated, back to Scrutiny. Timer fired, so `wf3_slaEscalation` runs next to Scrutiny (non-interrupting). Accept, inspection, approve, `esign`, `digilocker-push`; the instance ends | Same |
| `wf3-licence-case` (CMMN) | Scrutiny, then Inspection, then milestone completed, then case COMPLETED | Scrutiny, then Inspection, then 1 milestone, then case ended |
| `gap-link-events` | Works (probe reached) | **Deploy rejected**: `flowable-intermediate-catch-event-no-eventdefinition` (link catch not recognised) |
| `gap-signal-end` | Works: the thrower ends and 1 catcher instance is started | Deploys and ends, but **no catcher instance** is started (signal end behaves as a none end) |
| `gap-message-end` | Deploys and ends (no implementation, so a no-op) | Deploys and ends (none-end behaviour) |
| `gap-message-throw` | Works: passes through as a no-op and reaches the probe | **Deploy rejected**: `flowable-throw-event-invalid-eventdefinition` |
| `gap-conditional-start` | Works: `createConditionEvaluation()` with `feePaid=false` starts 0 instances; with `true`, 1 instance at the probe | **Deploy rejected**: `flowable-start-event-invalid-event-definition` |
| `gap-compensation-event-subprocess` | Works: the throw runs `gap_comp_undo`; completing it ends the instance | **Deploy rejected**: `flowable-event-subprocess-invalid-start-event-definition` (only error/timer/message/signal) |
| `gap-adhoc-subprocess` | **Deploy rejected**: `Invalid destination 'gap_adhoc_sub' of sequence flow` (`adHocSubProcess` is not parsed) | Works: `getEnabledActivitiesFromAdhocSubProcess` lists both tasks; with `executeActivityInAdhocSubProcess` and `adhocDone=true` the instance reaches the probe |
| `gap-io-mapping` (V-05) | Works. The worker saw `amount` and `feeBase`. Afterwards `ioResult=R-42`, the worker variables `workerResult` and `other` were also copied, and `feeBase` is gone (it was local) | Works. The worker saw **only** `feeBase`. Afterwards `ioResult=R-42`, and `workerResult` and `other` were **not** copied |
