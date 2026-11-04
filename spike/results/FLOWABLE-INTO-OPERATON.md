# Bringing every Flowable strength into Sangam on Operaton 2.2.x

Date: 2026-11-05. Status: **analysis only**. Nothing here is implemented or tested. Every effort band is an **estimate [I]**.

**Decision being executed:** ADR-D-1 = Operaton 2.2.x is the base engine (scorecard.md; SANGAM.md XI.10.2). ADR-D-14: Sangam is universal and fully open source, with every premium feature free. ADR-D-15: Tier 1 is PostgreSQL, Oracle, SQL Server and MySQL (SANGAM.md X.0c).

**Abbreviations used in citations:**
- `FL:` = `reference/flowable-engine/modules/` (Flowable 8.1-SNAPSHOT, local `main`; 8.0.0 is close but this is **not checked against the 8.0.0 tag**).
- `OP:` = `reference/operaton/` (2.3.0-SNAPSHOT; 2.2.0 is close, same caveat).
- Reports, all in `spike/results/`:
  - `E1-E11` = `E1-E11-report.md`;
  - `E2-E7` = `E2-E7-report.md`;
  - `E6-E9` = `E6-E9-report.md`;
  - `E16a`, `E16b`, `E16c` = `E16-E17-{a,b,c}-report.md`;
  - `E12-15` = `E12-E15-report.md`;
  - `PL-F` / `PL-O` = `patch-ledger-{flowable,operaton}.md`;
  - `PREM` = `PREMIUM-AND-UNIVERSAL-SCOPE.md`;
  - `GPV` = `GP-coverage-validation.md`.
- `SANGAM` = `SANGAM.md`. Part I §1.1/§1.2 is at lines 213–263, II §3 at 1072–1112, VIII.9 at 3841, X.0c at 4226, X.1 at 4236–4309, XI.10 at 6399–6475.

**Route codes (as asked):**
1. Operaton public extension point: `ProcessEnginePlugin`, `BpmnParseListener`, `CmmnTransformListener`, custom `ActivityBehavior`, `JavaDelegate`/connector, `HistoryEventHandler`, `IncidentHandler`, custom serializer, command interceptor, job handler, DMN hit-policy handler.
2. Sangam product layer, outside the engine.
3. Port of Flowable code (Apache-2.0). The class is named. Keep the Apache headers, and add a Sangam `NOTICE` attribution. **The local Flowable checkout has `LICENSE` but no root `NOTICE` file (checked).**
4. Operaton engine patch, kept in Sangam's fork with a patch ledger and an upstream PR.
5. Separate service or UI.
- **X** = deliberately not replicated in that form. The reason is given.

**Stages:** C = full pilot, D = production readiness, E = expansion (SANGAM VIII.9; X.1a CF-9 moved R31 to C).

**A porting caveat that applies to every route-3 item [F/I].** Flowable runs a flat agenda over `BpmnModel`. Operaton runs the PVM (`ActivityImpl`, atomic operations, execution tree) (SANGAM I §1.1).
- Flowable **engine-core** code (behaviours, commands, agenda operations) therefore cannot be copied verbatim into Operaton. Only its logic and tests can be reused.
- Route 3 is realistic only for self-contained classes: hit policies, HTTP/mail delegates, worker clients, event-pipeline interfaces and the auto-layout.
- **Never port `ProcessDiagramLayoutFactory`**: it carries the unfixed XXE CVE-2026-91197 (E12-15).

---

## A. Common to both engines (short)

| # | Capability | Operaton 2.2 | Flowable 8 | Evidence |
|---|---|---|---|---|
| A-1 | BPMN 2.0 core: tasks, gateways, sub-process, call activity, MI, timers, message/signal/error/escalation, boundary and event sub-process | yes | yes | E1 17-scenario corpus passes on both, except the CMMN history label (E1-E11) |
| A-2 | CMMN 1.1 core: stage, human/process/case/decision task, milestone, sentry, required/repetition/manual-activation rules | yes (`OP: engine/.../cmmn/handler/DefaultCmmnElementHandlerRegistry.java` l.72–79) | yes | E1 WF3 case; GP-21 (E16b) |
| A-3 | DMN 1.3 decision tables, DRD required decisions, hit policies UNIQUE/FIRST/ANY/RULE ORDER/COLLECT (+aggregators) | yes (`OP: engine-dmn/.../hitpolicy/DefaultHitPolicyHandlerRegistry.java` l.34–42; `DmnDecisionRequirementsGraph`) | yes (`FL: flowable-dmn-engine/.../hitpolicy/`) | GP-01, GP-19, GP-26 |
| A-4 | External work: fetch/acquire-and-lock with a lock duration; complete, fail with retries/back-off, BPMN error, release; REST plus a Java client | yes | yes | PL-O Q3; PL-F Q3 |
| A-5 | Running-instance migration API plus batch execution | `MigrationPlanBuilder` | `ProcessMigrationService` | GP-20 PASS on both (E16a) |
| A-6 | Token modification / change state | `ProcessInstanceModificationBuilder` | `ChangeActivityStateBuilder` | GP-02, GP-43 |
| A-7 | Suspend/activate, bulk delete, signals, message correlation by business key | yes | yes | GP-08, GP-14, GP-40 |
| A-8 | Row-level `TENANT_ID_`, tenant-scoped deployments | yes | yes | GP-37 |
| A-9 | Identity links, candidate users/groups, delegation/resolve, due dates, comments/attachments | yes | yes | GP-17, GP-18 |
| A-10 | Job executor: retries, dead-letter/incident, async continuations, exclusive jobs | yes | yes | GP-33 |
| A-11 | History levels none…full, history queries, variable-update detail at FULL, history-cleanup API | yes | yes | GP-36, GP-52 |
| A-12 | Native SQL task query (`createNativeTaskQuery`) | yes | yes | E2-6 (E2-E7) |
| A-13 | Embedded JVM, Spring Boot 4 starter, REST API | yes | yes | E15 (E12-15) |
| A-14 | Product rows in the engine transaction (Q1), both needing the same explicit-rollback fix | W-2 | F5 | PL-O, PL-F |
| A-15 | Optimistic locking only, no root lock: 0 lost updates, OLE storms on a hot root | yes | yes | E6 (E6-E9) |
| A-16 | Apache-2.0, MyBatis 3.5.19, the same DB families (PG, Oracle, MSSQL, MySQL, MariaDB, DB2, H2) | yes | yes (plus CockroachDB) | E10; PREM §4.1 |
| A-17 | LDAP identity plugin, Spring Security, CDI | `engine-plugins/identity-ldap`, `engine-cdi` | `flowable-ldap`, `flowable-spring-security`, `flowable-cdi` | module lists |
| A-18 | JUEL expressions; transient variables; call BPMN→CMMN (Operaton `caseRef`, Flowable `CaseServiceTask`) | yes | yes | module/model lists |

**A count: 18.**

## B. In Operaton only: what we keep (short)

| # | Keep | Evidence |
|---|---|---|
| B-1 | Link events, signal end, message throw, compensation event sub-process, process-level conditional start | E11 7/8 works (E1-E11) |
| B-2 | Automatic conditional-event evaluation on variable change (incl. `variableName`/`variableEvents`) | GP-42 (E16c F-1); `OP: BpmnParse.java` l.3876–3881 |
| B-3 | Compensation with wait-state handlers | GP-13 Operaton 12/12 cells, Flowable FK failure (E16a) |
| B-4 | Modification `startBefore/After` that keeps the running task id; restart from history | GP-43 (E16c area 2); `RestartProcessInstanceBuilder` |
| B-5 | `recalculateJobDuedate`, `setJobDuedate` (keeps the job id), and a safe re-date-then-activate order | E16c area 1 (Flowable needs 18 lines per item) |
| B-6 | `calledElementTenantId`: the child runs in the callee's tenant | GP-32 / D-REFERRAL (E16b #1); `OP: BpmnParse.java` l.3977 |
| B-7 | External tasks are not jobs: parallel MI fetch works; native `extendLock` and `activityInstanceId` | E16a #8, E16c F-4; PL-O Q3 |
| B-8 | Synchronous continuation after an external completion (one transaction) | PL-F F11; E2-E7 F-2 |
| B-9 | `TaskService` handles case tasks natively | PL-O design notes vs PL-F F7 |
| B-10 | Ordered assignment history (`HistoricIdentityLinkLog`) | GP-17 (E16a #10) |
| B-11 | Fine-grained authorization model, kept for admin APIs only (not for reads, see D-14) | E2-E7 §2.4 |
| B-12 | Full FEEL (feel-scala), DMN history, DRD | SANGAM I §1.1 |
| B-13 | History pipeline (`HistoryEventHandler`), removal time, cleanup windows, TTL | `OP: engine/.../history/handler/HistoryEventHandler.java` |
| B-14 | Batch framework (seed/monitor jobs, many batch types) | SANGAM I §1.1; `BatchEntity` |
| B-15 | Job priorities, priority-range pools, opt-in SKIP LOCKED for job acquisition | PL-O "SKIP LOCKED" section |
| B-16 | I/O mapping on any activity; connect plugin (HTTP/SOAP); Spin JSON/XML | `OP: engine-plugins/connect-plugin`, `spin-plugin`, `connect/` |
| B-17 | Camunda 7.24 API and schema compatibility, `camunda:` namespace, OpenRewrite recipe | E15 (E12-15) |
| B-18 | Quarkus extension, Helm chart, webapps-neo preview (history, batches, migrations, metrics) | E15; PREM §1.4; `OP: quarkus-extension`, `webapps-neo` |
| B-19 | Liquibase changelog shipped | `OP: engine/src/main/resources/org/operaton/bpm/engine/db/liquibase/operaton-changelog.xml` |
| B-20 | Condition-evaluation API, task state / `lastUpdated` / follow-up date, user operation log | `RuntimeService.createConditionEvaluation` (l.2542); `Task.getTaskState` (l.197) |
| B-21 | Flat runtime rows for long-lived instances (Flowable's `ACT_RU_ACTINST` grew by 175 rows over 36 cycles) | GP-10 (E16a #5) |
| B-22 | Release cadence: 19 GA in 24 months, monthly patches; 0 engine CVEs (Flowable has an unfixed XXE) | E13, E12 |
| B-23 | R1 throughput about 1.5× and a lower tail latency | E9 (low confidence) |
| B-24 | Business-key inheritance, assigned-task inclusion in candidate queries (adapter), JUnit/AssertJ process assertions | E16c F-6; SANGAM I §1.1 Testing |

**B count: 24.**

---

## C. In Flowable (OSS or Enterprise) but missing or weaker in Operaton

Columns:
- **Fl.** = Flowable location.
- **Op. state** = Operaton today.
- **Ev.** = measured evidence.
- **Rt** = route (primary first).
- **Feas.** = feasibility.
- **Eff.** = effort band [I].
- **R** = Sangam requirement.
- **St** = stage.

### C.1 BPMN elements and extensions (OSS)

| ID | Item | Fl. (OSS) | Op. state | Ev. | Rt | How | Feas. (reason / blocker) | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-01 | **Ad-hoc sub-process** | `FL: flowable-engine/.../bpmn/behavior/AdhocSubProcessActivityBehavior.java`; `RuntimeService.executeActivityInAdhocSubProcess` | **Absent**: no `adHoc` in `OP: BpmnParse.java` (grep 0); deploy fails `ENGINE-09005` | E11; GP-21 sub-check (E16b #5) | **4** (+3 logic only) | New parse path + `AdHocSubProcessActivityBehavior` on PVM (enabled-activity query, `completionCondition`, `cancelRemainingInstances`), plus `RuntimeService` API; upstream PR. Interim: CMMN discretionary / manual activation (GP-21 passes) | **Medium**. **Blocker:** validation fails in `BpmnParse` before any parse listener runs, so no plugin route exists | L | R34, R72 (agent sub-process) | D (C only if a pack needs it; XI.10.2 "Should") |
| FC-02 | **Event-registry BPMN events**: start, receive-event task, send-event task, boundary/catch | `SendEventTaskActivityBehavior.java`, `ReceiveEventTaskActivityBehavior.java`, `eventregistry/BpmnEventRegistryEventConsumer.java`, `EventRegistryEventDefinitionParseHandler.java` (all `FL: flowable-engine/...`) | **Absent**: only API message correlation; 0 Kafka/JMS classes in `OP` (grep) | none (SANGAM W11) | **2** (+1) | Sangam event gateway: inbox (R38) → `createMessageCorrelation`/`startProcessInstanceByMessage`; send = outbox (R4) row from a delegate. A `sangam:eventType` attribute on message events is read by a `BpmnParseListener` | High | L | R38, R83, R4 | C (pilot channels) → E |
| FC-03 | **Channels Kafka / JMS / RabbitMQ**, inbound pipeline (deserializer, key and tenant detectors, payload extractor), `.channel`/`.event` models | `FL: flowable-event-registry-spring/.../{kafka,jms,rabbit}/`; `flowable-event-registry-api/.../InboundEventKeyDetector.java`, `InboundEventTenantDetector.java`; `flowable-event-registry-model/.../KafkaInboundChannelModel.java` | **Absent** | none | **5** (+3 for the pipeline interfaces only) | Sangam event-gateway service per R83. Port the pipeline SPI shapes (`InboundEventKeyDetector`, `InboundEventTenantDetector`, `ChannelModel`); reimplement the adapters without the Spring coupling. The tenant comes from the channel binding (R38) | High | L | R83, R71 | C (one pilot channel) → E |
| FC-04 | **Event correlation parameters and unique start** (`storeAsUniqueReferenceId`, `manualSubscription`) | `FL: flowable-bpmn-converter/.../BpmnXMLConstants.java` l.277–279; `RegisterProcessInstanceStartEventSubscriptionCmd.java` | **Absent** (no unique business-key constraint by default; PREM M-3) | GP-15 (E16a #9) | **2** | R38 `uniqueStart`: a unique index on (tenant, definition key, business key) in a product table, checked in the inbox transaction | High | M | R38 | C |
| FC-05 | **Variable-listener events** (boundary / intermediate) | `FL: flowable-bpmn-model/.../VariableListenerEventDefinition.java`; `BoundaryVariableListenerEventActivityBehavior.java` | **Equivalent exists**: conditional events with `variableName`/`variableEvents` (`OP: BpmnParse.java` l.3876–3881) | GP-42 (Operaton evaluates automatically) | **1** | Import mapping, Flowable `variableListener` → Operaton conditional event. Document it in the R44 profile | High | S | R34, R44 | C |
| FC-06 | **HTTP task** (BPMN and CMMN) | `FL: flowable-engine/.../bpmn/http/DefaultBpmnHttpActivityDelegate.java`; `flowable-http-common/.../BaseHttpActivityDelegate.java`; `flowable-cmmn-engine/.../http/DefaultCmmnHttpActivityDelegate.java` | **Partial**: connect-plugin HTTP connector (`OP: connect/http-client`) | none | **1** (+3 for request/response handler shapes) | Sangam HTTP connector as a delegate / external-task worker with the R27 egress allow-list and R36 operation identity | High | M | R71, R27, R36 | C |
| FC-07 | **Mail task** | `FL: flowable-engine/.../bpmn/mail/BpmnMailActivityDelegate.java`; `flowable-mail` | **Absent** (connect has only HTTP/SOAP) | none | **1** | Mail connector worker (R71); DLT/notification service (R60) for SMS | High | S | R71, R60 | C |
| FC-08 | **Camel task** | `FL: flowable-camel/.../CamelBehavior.java` | **Absent** | none | **1** | Optional connector module calling Apache Camel (Apache-2.0) | High | M | R71 | E |
| FC-09 | **Mule task** | **Not present** in the Flowable 8.x tree (no `*Mule*` class, grep). Whether it was removed in docs: not verified | n/a | none | n/a | Nothing to port; R71 covers generic integration | — | — | R71 | n/a |
| FC-10 | **Shell task** | `FL: flowable-engine/.../ShellActivityBehavior.java` | **Absent** | none | **5** | Out-of-process command/script worker under the lease protocol. **Not in-engine** (R6/D-8) | High | S | R6 | D |
| FC-11 | **External worker for CMMN** | `FL: flowable-cmmn-engine/.../ExternalWorkerTaskActivityBehavior.java`; `flowable-cmmn-model/.../ExternalWorkerServiceTask.java` | **Absent**: the CMMN registry has only human/process/case/decision tasks (`DefaultCmmnElementHandlerRegistry` l.72–79); `ExternalTaskEntity` is tied to a process execution | PL-F notes that CMMN external jobs are not fetched | **2** (4 if native is demanded) | Pattern: CMMN `processTask` → one-step BPMN external task (works today). Native: a patch adding case-execution external tasks | Medium. **Blocker for native:** the external-task entity model is process-only | M (pattern S; native L) | R45 | E |
| FC-12 | **Triggerable service task** | `FL: flowable-engine/.../delegate/TriggerableJavaDelegate.java`; `flowable:triggerable` | **Equivalent exists**: a signallable custom `ActivityBehavior` (`signal()`), receive task, external task | none | **1** | Sangam `TriggerableBehavior` base class set through a `BpmnParseListener` on `sangam:triggerable` | High | S | R34 | C |
| FC-13 | **Skip expressions** | `FL: flowable-engine/.../bpmn/helper/SkipExpressionUtil.java`; `DynamicBpmnService.changeSkipExpression` | **Absent** | none | **1** | A `BpmnParseListener` wraps each activity's behaviour; the wrapper evaluates FEEL and leaves via the default outgoing flows. Opt-in per definition, audited (R42) | Medium (wrapping boundary-event and MI behaviours needs care) | M | new | D |
| FC-14 | **Future / async Java delegates** | `FL: flowable-engine/.../delegate/FutureJavaDelegate.java` l.39–62; `MapBasedFlowableFutureJavaDelegate.java` | **Absent** (`JavaDelegate` is synchronous) | none | **2** | Sangam rule: all I/O runs as external work under the lease (R9). In-engine async futures are **not** reproduced, because they would bypass L2/R36 | High | S | R9, R36 | C |
| FC-15 | **Dynamic BPMN injection** (user task, embedded / parallel sub-process into one instance) | `FL: InjectUserTaskInProcessInstanceCmd.java`, `InjectEmbeddedSubProcessInProcessInstanceCmd.java`, `InjectParallelUserTaskCmd.java` | **Absent** (modification only works within the existing model) | none | **2** | Derive a model variant with `model-api`, deploy it (R43, reviewed), migrate that one instance (Operaton migration + modification) | Medium (per-instance definitions inflate the repository; needs R43 governance) | L | R49, new | E |
| FC-16 | **Runtime definition patching without redeploy** (`DynamicBpmnService.changeServiceTaskClassName`, `changeUserTaskAssignee`…, `ACT_PROCDEF_INFO`) | `FL: flowable-engine/.../DynamicBpmnService.java`; `ProcessDefinitionInfoCache.java` | **Absent** | none | **2** | Deliberately replaced by an R43-governed redeploy plus migration. Silent runtime patching conflicts with R43 hash-pinned packages | High | S | R43 | D |
| FC-17 | **Change state across call-activity boundaries** (move to parent / into a sub-process instance) | `FL: ChangeActivityStateBuilder.java` | **Partial**: modification within one instance only | GP-43 | **2** | Composition: cancel the activity instance plus `startBeforeActivity` in the other instance, in one product command, with R49 checks | Medium | M | R49 | D |
| FC-18 | **Migration builder variants**: 1→N and N→1 mappings, JSON migration document, pre/post upgrade script or delegate, historic instance migration | `FL: flowable-engine/.../migration/ActivityMigrationMapping.java` l.51–55; `ProcessInstanceMigrationBuilder.java` l.85–109; `ProcessInstanceMigrationDocumentConverter.java` | **Partial**: 1:1 plans with validation and batch | GP-20 | **2** | Sangam migration document (JSON) → Operaton plan; 1→N/N→1 via a post-migration modification; pre/post hooks as batch steps; dry-run counts (R48) | Medium | L | R48 | D |
| FC-19 | **CMMN case migration** | `FL: flowable-cmmn-api/.../CmmnMigrationService.java`, `CaseInstanceMigrationBuilder.java` | **Absent** | none | **2** (run-off), 4 (native) | Run-off and new-instance cut-over (R45 policy); native case migration only on proven demand | **Low (native)**. **Blocker:** no CMMN migration model in Operaton at all; a large patch | XL native / S run-off | R45 | E |
| FC-20 | **Runtime activity-instance table** (`ACT_RU_ACTINST`) | `FL: flowable-engine/.../entity/ActivityInstanceEntity.java` | **Equivalent API**: `RuntimeService.getActivityInstance` (derived) plus `ACT_HI_ACTINST` | PL-F F8 (Flowable needs one query); GP-10 (Flowable table growth) | **1** | `HistoryEventHandler` projects open activity instances into the R63/R65 read model. **Do not** add the runtime table (B-21) | High | S | R63, R65 | C |
| FC-21 | **Multi-instance variable aggregation** | `FL: flowable-bpmn-model/.../VariableAggregationDefinition.java` (also in CMMN) | **Absent** | GP-49, GP-55 per-branch local-variable workarounds (E16c) | **1** | An end listener on the MI inner activity writes the per-item result to a product aggregate table in the same transaction; it is exposed as a collection variable on MI completion | Medium (avoid OLE on the parent variable) | M | new | D |
| FC-22 | **Terminate end `terminateAll` / `terminateMultiInstance`** | `FL: flowable-bpmn-model/.../TerminateEventDefinition.java` l.37–47 | **Absent** (terminates the scope only) | none | **1** | Custom terminate behaviour set by a parse listener (`sangam:terminateAll`) | Medium | S | R34 | E |

### C.2 Timers, jobs, history, scripting, variables, DB (OSS)

| ID | Item | Fl. | Op. state | Ev. | Rt | How | Feas. | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-23 | **Business calendar per timer** (`businessCalendarName`) | `FL: flowable-engine/.../cfg/DefaultInternalJobManager.java` l.322–327; `TimerEventDefinition.calendarName` | **Weaker**: one calendar per type (`OP: .../jobexecutor/TimerDeclarationType.java` l.24–29) | **GP-06** PASS-WW, 30 lines, engine-wide replacement (E16a) | **1** (+4) | Plugin registers Sangam `date`/`duration`/`cycle` calendars (`MapBusinessCalendarManager`) that read a calendar tag from the expression (e.g. `cal:mh-gov:P3D`; `BusinessCalendar.resolveDuedate(String, …)` receives the text). Patch: an `operaton:businessCalendarName` attribute with an upstream PR | High (impl-package calendar API; re-check on upgrade) | M | R31, R79 | C |
| FC-24 | **Async executor**: separate async and timer acquisition threads, global acquire lock, reset-expired thread, post-commit hand-off | `FL: flowable-job-service/.../asyncexecutor/AcquireTimerJobsRunnable.java`, `AcquireAsyncJobsDueRunnable.java` l.114–178, `ResetExpiredJobsRunnable.java`; `cmd/AcquireJobsWithGlobalAcquireLockCmd.java` | **Partial**: one `SequentialJobAcquisitionRunnable`, SKIP LOCKED option, priorities | PL-O SKIP LOCKED section | **4** (+1 config) | Config now: SKIP LOCKED on, priorities. Patch: post-commit hand-off of new jobs and a separate timer acquisition. Optional global acquire lock for DBs without SKIP LOCKED. R39 partitions stay blocked (A-6) | Medium (touches the job executor core) | L | R2, R28 | C (config) → D (patch) |
| FC-25 | **Async history / history jobs** | `FL: flowable-job-service/.../DefaultAsyncHistoryJobExecutor.java`, `HistoryJobEntity.java` (default use in 8.0: not verified) | **Absent** (synchronous history) | none | **1** | A `HistoryEventHandler` writes history events to the outbox (R4); a projector builds history/analytics stores (SANGAM I §4.6). Sync mode stays for strict audit | High | M | R4, R29, R42 | C → D |
| FC-26 | **History configuration per process / activity** | `FL: flowable-engine/.../history/AbstractHistoryManager.java` (`isHistoryEnabledForActivity`; attribute name not verified) | **Partial**: a global level plus `customHistoryLevels` (`OP: ProcessEngineConfigurationImpl.java` l.621) | E9: FULL vs AUDIT < 8 % | **1** | A custom `HistoryLevel` that filters events by definition/activity metadata from the R43 package | High | S | R29 | D |
| FC-27 | **Secure JS sandbox** | `FL: flowable-secure-javascript/.../SecureJavascriptConfigurator.java`, `impl/SecureScriptClassShutter.java` (Rhino) | **Absent**: no JS engine (GraalJS is test scope, E10) | E10 | **5** / X for Rhino | Out-of-process script worker: container, seccomp, cgroups (R6/D-8). **Rhino and GraalJS are not ported** (CVE-class sandbox escape, SANGAM I §1.1) | High | M | R6 | C → D |
| FC-28 | **JSON variables** | `FL: flowable-variable-service/.../types/JsonType.java` (Jackson 3) | **Equivalent exists**: Spin JSON (`OP: engine-plugins/spin-plugin/.../JsonValueSerializer.java`, Jackson 2) | GP-26 DMN COLLECT shape differs (E16b #6) | **1** | Enable the spin-plugin; the Sangam `json` type follows the R33 FEEL type contract and R11 limits | High | S | R33, R11 | C |
| FC-29 | **Long text variables** (`longString`) | `FL: flowable-variable-service/.../types/LongStringType.java` | **Absent**: `TEXT_ varchar(4000)` (PG/MySQL), `nvarchar(4000)` (MSSQL), **`NVARCHAR2(2000)` on Oracle** (`OP: engine/.../db/create/activiti.*.create.engine.sql` l.237) | **GP-38** PSQLException at 5,590 chars (E16b #3) | **1** | Pre-serializer (`customPreVariableSerializers`, `ProcessEngineConfigurationImpl` l.519) stores strings above the per-DB limit as bytes and returns a `StringValue`. Not queryable by value (the same on Flowable) | High | S | R29, R54 | C |
| FC-30 | **MySQL millisecond precision** | `FL: flowable-engine/src/main/resources/org/flowable/db/create/flowable.mysql.create.*.sql` (`datetime(3)`, PREM M-1) | **Weaker**: 66 second-precision columns | PL-O MySQL (1)–(3): lease re-offered 200 ms early, timer 300 ms early | **4** (+2 interim) | Patch the MySQL create/upgrade scripts to `datetime(3)`/`timestamp(3)` (upstream PR). Interim: P-1 as a Liquibase change set after every create/upgrade | High | S | R84 | C |
| FC-31 | **Native sibling / reference link** (`referenceId`/`referenceType` + query) | `FL: flowable-engine/.../runtime/ProcessInstanceBuilder.java` l.93–98 | **Absent** | **GP-54** PASS-WW, 4 lines (E16c) | **2** | Product `INSTANCE_LINK` table (type, source, target, tenant) written in `inEngineTransaction`; R19-authorised queries | High | S | new | C |
| FC-32 | **Entity-link service** (parent/child/root across BPMN, CMMN and tasks, with history) | `FL: flowable-entitylink-service-api/.../EntityLinkService.java` | **Partial**: `superProcessInstanceId`, `rootProcessInstanceId`, `superCaseInstanceId` | GP-54, E16c N-4 | **1** (+2) | Execution/case listeners fill the FC-31 link table with hierarchy types; one query API | High | M | new | D |
| FC-33 | **Callee tenant handling**: `fallbackToDefaultTenant`, `overrideProcessDefinitionTenantId`, `DefaultTenantProvider` | `FL: CallActivity.java` / `ProcessInstanceBuilder.fallbackToDefaultTenant`; `flowable-engine-common/.../DefaultTenantProvider.java` | **Partial**: `calledElementTenantId` exists and is **better** (B-6); no fallback to shared definitions | GP-32 | **2** | The product resolves the definition (tenant → shared) before start or in a `calledElementTenantId` expression, with R19 tenant checks | High | S | R19 | C |
| FC-34 | **DB-per-tenant routing** | `FL: flowable-engine-common/.../multitenant/TenantAwareDataSource.java`; `flowable-engine/.../multitenant/MultiSchemaMultiTenantProcessEngineConfiguration.java` | **Absent** (row-level only) | none | **2** | Sangam engine-per-tenant registry (one Operaton engine per tenant DB), per-tenant outbox and relay (A-5), per-tenant upgrades (R32) | Medium (**blocker:** `ClockUtil` is JVM-wide, so all engines in a JVM share one clock, W-7; job-executor cost per engine) | L | R4 (A-5), R32 | D |
| FC-35 | **Change-tenant tooling** | `FL: flowable-engine-common-api/.../tenant/ChangeTenantIdBuilder.java`; `ChangeTenantIdBuilderImpl.java` (semantics not verified beyond the API) | **Absent** | none | **2** | Product batch: redeploy into the target tenant, migrate instances, rewrite product rows; no direct engine-table updates | Medium | M | new | E |

### C.3 Worker API, SDKs, starters, forms, content, app, IDM, tooling (OSS)

| ID | Item | Fl. | Op. state | Ev. | Rt | How | Feas. | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-36 | **External-worker REST API** | `FL: flowable-external-job-rest/.../acquire/ExternalWorkerAcquireJobResource.java` etc. | **Equivalent exists**: REST external tasks plus long polling (`OP: engine-rest/.../FetchAndLockHandlerImpl.java`) | PL-O Q3 | **2** | Sangam worker API (VI.3 states, lockVersion, attemptId, OFFERED/ack) over Operaton | High | L | R9, R16, R20 | C |
| FC-37 | **Official worker clients in 5 languages** (Java, JS/TS, Python, .NET, Go) | github.com/flowable/flowable-external-client-{java,js,python,.net,golang} (E15) | **Java only** (`OP: clients/java/client`) | E15 workers 1 vs 2 | **3** (+5) | Port the client structure (Apache-2.0, keep NOTICE) re-targeted to the Sangam API. Tier 1 per ADR-D-4: Java, .NET, PHP (new, no Flowable client), Python, TS; Go is Tier 2 | High | L | R18 | C |
| FC-38 | **Spring Boot starters per engine + actuator** (`ProcessEngineEndpoint`, `FlowableInfoContributor`) | `FL: flowable-spring-boot/flowable-spring-boot-starters/*` (13 starters); `.../actuate/endpoint/ProcessEngineEndpoint.java` | **Partial**: one starter, health indicators only (`OP: spring-boot-starter/.../actuator/JobExecutorHealthIndicator.java`, `ProcessEngineHealthIndicator.java`) | none | **2** | A Sangam starter adds actuator endpoints (engine, jobs, leases, outbox lag), an info contributor and Micrometer/OTel (R13) | High | M | R13 | C |
| FC-39 | **Forms API** | `FL: flowable-form-api/.../FormService.java` (API/model only; the form engine is not in OSS 8) | **Partial**: form keys, Camunda Forms (form-js) | none | **2** | R47 forms contract (schema, server-side validation, versioning) | High | L | R47 | C |
| FC-40 | **Content API** | `FL: flowable-content-api/.../ContentService.java` (API only) | **Partial**: task attachments | none | **5** | R74 document service (S3/Blob/FS, versions, checksums, scan hook) | High | L | R74 | C → D |
| FC-41 | **App engine** (app deployments bundling BPMN/CMMN/DMN/event/channel/form) | `FL: flowable-app-engine/.../AppEngineConfiguration.java`; `flowable-app-engine-api/.../AppDefinition.java` | **Partial**: multi-resource deployments, process applications | none | **2** | R43 executable package (hash-pinned bundle) is the app unit | High | M | R43 | C |
| FC-42 | **IDM engine with privileges, privilege mappings and tokens** | `FL: flowable-idm-api/.../Privilege.java`, `PrivilegeMapping.java`, `Token.java`; `flowable-idm-engine/.../PrivilegeEntity.java`, `TokenEntity.java` | **Partial**: users/groups/tenants plus authorizations; no privilege or token tables | E2 (engine authorization kept off) | **2** | Sangam identity and authorization (R19 VI.4, R50, R67) | High | M | R19, R50, R67 | C |
| FC-43 | **Global typed event dispatcher + DB event log** | `FL: flowable-engine-common-api/.../FlowableEventDispatcher.java` | **Partial**: execution/task listeners, history events, Spring event bridge | none | **1** | Plugin adds global parse listeners plus a `HistoryEventHandler` → typed Sangam events on the outbox | High | M | R4, R13, R42 | C |
| FC-44 | **Debug agenda** (base of Inspect) | `FL: flowable-engine/.../agenda/DebugFlowableEngineAgenda.java` | **Absent** | none | **5** | R80 debugger: in sandbox tenants, a parse listener sets `asyncBefore` breakpoints; the UI steps jobs, fires timers, sends messages and edits variables. Impossible to enable on a production profile | Medium (asyncBefore changes transaction boundaries) | L | R80 | D |
| FC-45 | **Auto-layout and diagram image generators** (BPMN/CMMN/DMN) | `FL: flowable-bpmn-layout/.../BpmnAutoLayout.java`; `flowable-image-generator`, `flowable-cmmn-image-generator`, `flowable-dmn-image-generator` | **Absent** (rendering in bpmn-js) | E12 XXE in `ProcessDiagramLayoutFactory` | **5** (+3 optional `BpmnAutoLayout`) | bpmn-js rendering in the UI. Server-side layout only if needed. **Do not port `ProcessDiagramLayoutFactory`** | High | S | R69 | E |
| FC-46 | **Pluggable validator sets** (process/case validation, warnings vs errors) | `FL: flowable-process-validation`, `flowable-case-validation` | **Partial**: parse-time errors only | E11 (Flowable rejects valid BPMN; Operaton validation is coarse) | **1** | `BpmnParseListener` + pre-deploy Sangam validator producing the R44 profile and R43 closure check | High | M | R44, R43 | C |
| FC-47 | **Groovy static script engine** | `FL: flowable-groovy-script-static-engine` | Absent (JSR-223 if on the class path) | E10 | **5** | Script worker only (D-8); not carried in the engine | High | S | R6 | E |
| FC-48 | **JMX** | `FL: flowable-jmx` | Absent | none | **2** | Replaced by OTel/Micrometer (R13); not ported | High | S | R13 | C |
| FC-49 | **OSGi** | `FL: flowable-osgi` | Absent | none | **X** | Out of the product envelope (IX.6) | — | — | — | n/a |
| FC-50 | **CXF web-service task** | `FL: flowable-cxf` | **Equivalent exists**: SOAP connector (`OP: connect/soap-http-client`) | none | **1** | SOAP connector in the R71 catalogue | High | S | R71 | E |
| FC-51 | **CockroachDB support** | SANGAM I §1.1 ("Same + CockroachDB"); Flowable CI not checked | Absent | none | **4** | Dialect patch (R17 lists it as experimental only) | Medium (OLE-heavy engine on a serializable DB: not measured) | L | R17 | E |
| FC-52 | **Task/instance lifecycle fields**: task `state`, `inProgressStartTime/By`, `claimTime/claimedBy`, `suspendedTime`; instance `owner`, `assignee`, `businessStatus`, `predefineProcessInstanceId`; historic task log | `FL: flowable-task-service-api/.../TaskInfo.java`; `ProcessInstanceBuilder.java` | **Partial**: `getTaskState`, `lastUpdated`, `followUpDate`, identity-link log | GP-17 | **1** (+2) | Task listeners and a history handler record claim and in-progress times in the R42/R65 read model; business status as a product field | High | S | R42, R65 | C |

### C.4 DMN (OSS)

| ID | Item | Fl. | Op. state | Ev. | Rt | How | Feas. | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-53 | **Decision services** | `FL: flowable-dmn-model/.../DecisionService.java`; `flowable-dmn-api/.../DmnDecisionService.java`, `DecisionServiceExecutionAuditContainer.java` | **Absent** in `OP: engine-dmn/engine` (grep 0) | none | **5** | Through the R8 DMN SPI (Drools / jDMN cover decision services as standard DMN; verify in the TCK run, SANGAM II §3.3) | Medium (a second DMN engine to maintain) | L | R8 | D |
| FC-54 | **Hit policies PRIORITY and OUTPUT ORDER** | `FL: flowable-dmn-engine/.../hitpolicy/HitPolicyPriority.java`, `HitPolicyOutputOrder.java`, `OutputOrderComparator.java` | **Absent** (registry l.34–42); the model enum has them | none | **1** (+3) | Custom `DmnHitPolicyHandler`s registered via `DefaultDmnTransformer.setHitPolicyHandlerRegistry` (l.99) from a plugin; port the ordering logic | High | S | R8 | C |

### C.5 CMMN (OSS)

| ID | Item | Fl. | Op. state | Ev. | Rt | How | Feas. | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-55 | **Event listeners**: timer, user, generic, signal, variable, reactivation (plus `IntentEventListener`, purpose not verified) | `FL: flowable-cmmn-model/.../TimerEventListener.java`, `UserEventListener.java`, `GenericEventListener.java`, `SignalEventListener.java`, `VariableEventListener.java`, `ReactivateEventListener.java` | **Absent**: registration commented out (`DefaultCmmnElementHandlerRegistry` l.81–82); `EventListenerActivityBehavior.created` is a TODO | **GP-21**: the timer listener deploys but never fires (E16b #4) | **1** (+4) | Replace the registry (`CmmnTransformer.setCmmnElementHandlerRegistry`, l.76) with Sangam handlers. Timer listener = a custom `JobHandler` (`customJobHandlers`, l.426) on the case execution. User/generic = a product "occur" API. Patch upstream | Medium. **Blocker:** relies on impl classes; timer jobs for case executions need new job-configuration code | L | R45 | E (deploy-time reject at C, see D-08) |
| FC-56 | **Plan-item lifecycle extensions**: async-active, wait_repetition, unavailable, available condition, lifecycle listeners, completion-neutral, parent-completion rule, exit types, sentry `onEvent` trigger mode | `FL: flowable-cmmn-api/.../PlanItemInstanceState.java` l.24–59; `CompletionNeutralRule.java`, `ParentCompletionRule.java`; `PlanItemInstanceLifecycleListener.java` | **Absent**: CMMN 1.1 state machine only | none | **4** | Patch the case-execution state machine; upstream PR. Only on proven demand (R45 "retire-safe CMMN") | **Low**. **Blocker:** deep change to the hard-wired Operaton CMMN lifecycle; upstream acceptance not verified | XL | R45 | E |
| FC-57 | **Repetition with collections / max instance count / counter; completion and auto-complete conditions** | `FL: RepetitionRule.java`; `flowable-cmmn-model/.../VariableAggregationDefinition.java` | **Partial**: repetition by condition only | none | **4** | Patch the repetition handling | Medium | L | R45 | E |
| FC-58 | **CMMN task types**: service, script, HTTP, external worker, send event, case page | `FL: flowable-cmmn-model/.../ServiceTask.java`, `ScriptServiceTask.java`, `HttpServiceTask.java`, `ExternalWorkerServiceTask.java`, `SendEventServiceTask.java`, `CasePageTask.java` | **Absent** (four task types only) | none | **2** | Model pattern: processTask → BPMN (works today); custom handlers later | Medium | M | R45 | E |
| FC-59 | **Case reactivation** (including historic) | `FL: flowable-cmmn-api/.../reactivation/CaseReactivationBuilder.java`; `ReactivateHistoricCaseInstanceCmd.java` | **Absent** | GP-22 (reopen = linked new instance on both) | **2** | Linked new case (FC-31 link), with history read | High | S | R45 | E |

**Shared services, for completeness:**
- **Batch service** (`FL: flowable-batch-service-api/.../BatchService.java`): Operaton's framework is stronger (B-14). Nothing to bring.
- **Identity links:** common (A-9).
- **Entity links:** FC-32.
- **Flowable's shared-services architecture** (`SCOPE_TYPE_` services across engines) is an internal design. It is not imported; Operaton already runs BPMN, CMMN and DMN in one engine.

### C.6 Flowable Enterprise (from PREM §1.3)

| ID | Item (PF-id) | Source (PREM) | Op. state | Rt | How | Feas. | Eff. | R | St |
|---|---|---|---|---|---|---|---|---|---|
| EC-01 | **Work** end-user app, case workspace (PF-33) | documentation.flowable.com/latest/user/work/work-introduction | Legacy Tasklist; neo `Tasks` (preview) | **5** | Sangam universal work UI on webapps-neo: inbox, case workspace | High | XL | R76 | C (inbox) → E |
| EC-02 | **Rich forms** designer + runtime (PF-34) | forum.flowable.org/t/…/3420 [S] | form-js | **5** | Form designer + renderer under R47 | High | L | R47 | C |
| EC-03 | **Pages** (low-code UI) (PF-35) | …/user/design/introduction | none | **5** | R77 page model bound to R78 and R25 | Medium | XL | R77 | E |
| EC-04 | **Design** modeller: BPMN, CMMN, DMN, forms, pages, apps, version history (PF-36) | same | `operaton-modeler`, 0 releases, no CMMN editor (E15) | **5** | Web modeller on bpmn-js/dmn-js/form-js. CMMN editor: bpmn.io cmmn-js maintenance status **not verified** | Medium | XL | R69 | C (BPMN/DMN/forms) → E |
| EC-05 | Design **Git, deployment approval, test models** (PF-37) | 2026.1 release notes | none | **5** | Git sync, R43 approval, R80 tests | High | L | R69, R80, R43 | D → E |
| EC-06 | **Data objects / dictionary / ERD** (PF-38) | …/develop/be/data-models [S] | none | **5** | R78 typed entities (DB- or REST-backed) | Medium | XL | R78 | D → E |
| EC-07 | **Service registry** (PF-39) | Flowable capabilities blog [S] | none | **2** | R71 declarative registry with capability classes (R36) | High | L | R71 | C → E |
| EC-08 | **SLA models** (PF-40) | 2025.2 / 2026.1 notes | timers only | **2** | R79 SLA service over timers + FC-23 calendars + pause/resume (D-32) | High | L | R79 | C (AC-06 subset) → D |
| EC-09 | **Full audit trails** (PF-41) | capabilities blog | history + user op log | **1** | `HistoryEventHandler` → R42 audit chain | High | M | R42 | C |
| EC-10 | **Business dashboards / reports** (PF-42) | blog; 2026.1 | none | **5** | R65 analytics on a separate read model | High | XL | R65 | D → E |
| EC-11 | **Control** operations (PF-43) | …/user/control/dashboards [S] | legacy Cockpit CE; neo partial | **5** | R63 console on webapps-neo | High | XL | R63 | C → D |
| EC-12 | **Hub**: assisted migration, job-health dashboard (PF-44) | 2026.1 notes | neo `Migrations`, `Batches` (preview) | **5** | R63 + R48 migration UI (FC-18) | Medium | L | R63, R48 | D |
| EC-13 | **Inspect** debugger (PF-45) | …/user/inspect [S] | none | **5** | R80 (FC-44) | Medium | L | R80 | D |
| EC-14 | **Agents / AI / chat** (PF-46, Agentic Case Platform) | 2026.1 notes | none | **5** | R72 agent task (on FC-01 ad-hoc), R14 MCP rules, R37 journal | Medium (depends on FC-01 and R37) | XL | R72, R14, R37 | E |
| EC-15 | **Engage** conversational (PF-47) | product name only [F]; features **[U]** | none | **5** | R81 channel adapters | Medium (scope unverified) | L | R81 | E |
| EC-16 | **Mobile** (PF-48) | name only; **[U]** | none | **5** | R76 responsive + S15 field app | Medium (scope unverified) | L | R76 | E |
| EC-17 | **Multi-tenant administration** (PF-49) | 3.16 notes [S] | Admin + tenant ids | **5** | A01 tenant onboarding UI | High | M | R19 | C |
| EC-18 | **Content / documents / output templates** (PF-50) | work intro; 2026.1 | attachments | **5** | R74 (FC-40) + template generation | High | L | R74 | C → D |
| EC-19 | **Enterprise Cloud** SaaS (PF-51) | flowable.com/products | — | **X** | Sangam is self-hosted (R56); R70 gives the operator tooling | — | — | R56, R70 | n/a |
| EC-20 | **Fine-grained operator permissions + operator audit** (PF-52) | 3.12 notes [S] | authorizations + op log | **2** | R19/R26 permissions + R42 audit on every console action | High | M | R19, R26, R42 | C |
| EC-21 | **LEAP** model-incompatibility analyser (E15) | documentation.flowable.com/latest/howto/howto/howto-migration (commercial; OSS availability **[U]**) | OpenRewrite recipe for Java only | **5** | R48 assessment tool: BPMN/CMMN/DMN import classification | Medium (scope unverified) | M | R48 | E |

**C count:** 59 OSS items (FC-01…FC-59) + 21 Enterprise items (EC-01…EC-21) = **80**.

---

## D. Operaton weaknesses to eliminate

| ID | Weakness | Evidence (file / report) | Rt | Fix | St |
|---|---|---|---|---|---|
| D-01 | **E8 DST runaway**: `R/PT1H` fires ≥ 21 times at the autumn fold | E8 (E1-E11); A-12. Root cause **not verified**. Candidates: the start is stored as local time without an offset; `DurationHelper.add` uses `GregorianCalendar` in the JVM zone (`OP: engine/.../calendar/DurationHelper.java` l.149–179) | **4** (+2) | Patch: UTC arithmetic, keep the offset in the stored repeat, `lastFiredInterval`, upstream PR. Interim: engine JVM in UTC, bounded job execution, product cycle calculation (R31) | C |
| D-02 | JVM zone cached in a static at class load | `OP: .../calendar/DateTimeUtil.java` l.44 `JVM_DEFAULT_DATE_TIME_ZONE` | **4** | Same patch as D-01; until then, fix the JVM zone at start-up (UTC) | C |
| D-03 | **MySQL second precision** (patch P-1) | PL-O MySQL (1)–(3); `OP: .../create/activiti.mysql.create.engine.sql` l.55, 109 (`datetime`) | **4** (+2) | FC-30 | C |
| D-04 | **MySQL utf8mb3** engine tables (no 4-byte characters) | PL-O MySQL (1); `activiti.mysql.create.engine.sql` l.23 `CHARSET=utf8` | **4** (+2) | Patch the scripts to `utf8mb4`/`utf8mb4_bin` (key length ≤ 3072 bytes checked); interim conversion change set | C |
| D-05 | MariaDB driver silently switches the engine to the MariaDB dialect on a MySQL server | PL-O "MariaDB Connector/J" | **2** | Pin `databaseType` explicitly; start-up check that the driver, server and schema agree; upstream issue | C |
| D-06 | MySQL: no business-key unique constraint; `jdbcStatementTimeout` ignored in MariaDB batches | PREM M-3, M-4 | **2** | Sangam change set adds the R38 unique index; R2/R3 timeout tests with the shipped driver | C |
| D-07 | **Text variable limit**: 4,000 characters (PG/MySQL/MSSQL) and **2,000 on Oracle** (`NVARCHAR2(2000)`) | GP-38 (E16b #3); `activiti.oracle.create.engine.sql` l.237 (Oracle limit **found here, not measured**) | **1** | FC-29 | C |
| D-08 | **CMMN timer listener never fires** (deploys silently) | E16b #4; `DefaultCmmnElementHandlerRegistry` l.81–82 | **1** | At once: the R44 validator rejects any CMMN model with an event listener. Then FC-55 | C (reject) / E (feature) |
| D-09 | **Ad-hoc sub-process rejected** | E11 `ENGINE-09005` | **4** | FC-01 | D |
| D-10 | **Global `ClockUtil`** (static, JVM-wide) | PL-O W-7, I-6; `OP: .../util/ClockUtil.java` l.39–63 | **2** (+4) | One engine per JVM; leases on the DB clock (A-1, F-6). Patch a per-engine `Clock` SPI with an upstream PR (needed before FC-34 runs several engines in one JVM) | C (contain) → D (patch) |
| D-11 | **Business calendar per engine/type only** | GP-06 (E16a #1); `TimerDeclarationType.java` l.24 | **1** | FC-23 | C |
| D-12 | **DMN default binding = latest**: old instances get the new rule | GP-19 (E16a #6) | **1** | A parse listener sets `decisionRefBinding=deployment` (and the call-activity binding) when absent; the R43 closure check flags `latest` | C |
| D-13 | **History-TTL enforcement**: a deployment without TTL fails (`ENGINE-12018`); generic TTL cleanup conflicts with R61 | PL-O W-3; `ProcessEngineConfigurationImpl.java` l.883 (`enforceHistoryTimeToLive = true`) | **1** (+2) | A parse listener injects the TTL from the R29/R61 retention class at deploy; government records are never TTL-deleted (R61 overrides R29) | C |
| D-14 | **Built-in authorization lacks deny-wins** (a user grant beats a group revoke; a specific grant beats a broader revoke) | E2-E7 §2.4, F-10 | **2** | Engine authorization off for reads; the VI.4 predicate (A-3/A-13) is the only read path | C |
| D-15 | `TaskQuery` cannot take the predicate; native queries bypass engine authorization | E2-E7 §2.4 Q2 | **2** | `AuthorizedQueryBuilder` + ArchUnit ban on raw engine queries (R19) | C |
| D-16 | **Bus factor** (one maintainer writes 72–80 % of human commits) / **no LTS** (about 6-month minors) | E13 (E12-15) | **2** | Sangam fork with a patch ledger and upstream-first PRs; a provider contract; Sangam's own LTS and patch policy (R68); track each minor | D |
| D-17 | **External-task fencing missing**: only `workerId` is checked; complete after lease expiry is accepted | PL-O Q3; `OP: .../cmd/HandleExternalTaskCmd.java` l.92–94 | **2** (+4) | Productise the W-8 lease table in `OPERATION_LEDGER` (A-14); optional upstream PR exposing a lock version | C |
| D-18 | **Unlock without an owner check** | `OP: .../cmd/UnlockExternalTaskCmd.java` l.39–41 (`externalTask.unlock()`, no validation) | **2** (+4) | The engine REST is not exposed; the Sangam API checks owner + version; upstream PR to add a worker check | C |
| D-19 | **Concurrent fetch**: the engine silently IGNOREs OLE on external tasks; no SKIP LOCKED in the external-task fetch | PL-O W-9; `OP: .../cmd/FetchExternalTasksCmd.java` l.114–153; E9 B-2 (17 % failures) | **4** (+2) | Patch `ExternalTask.xml` SKIP LOCKED + a flag to throw instead of ignore (removes the internal I-8 dependency); interim W-9 per-topic fetch lock | C |
| D-20 | No OFFERED / UNKNOWN_OUTCOME / CANCEL_REQUESTED / held states; no per-topic lease policy | PL-O Q3 table | **2** | VI.3 lease layer + A-8 default class (c) | C |
| D-21 | Engine-native expiry can re-offer before the product applies A-8 (**measured double payment**) | E2-E7 E3-hz, F-4 | **2** | A-10 `ExpiryPolicy` (W-10) in every fetch path | C |
| D-22 | **Q1 rollback defect**: MyBatis skips the rollback, then `setAutoCommit(true)` commits the product rows | PL-O W-2 | **2** (+4) | Keep the explicit rollback listener in every `inEngineTransaction`; upstream PR to roll back the connection in the standalone transaction context | C |
| D-23 | **Large MI n² completion** (GP-09: 1,732 of 5,000 done in 180 s) | E16a GP-09 (cause **not verified**) | **2** (+4 later) | Modelling rule: one instance per item or chunked sub-processes; R64 batch start. Profile the engine at D | C (rule) / D (engine) |
| D-24 | **Hot-root OLE storm**, no root lock (30–75 % unrecovered after 5 retries) | E6; A-11 | **1** | Root-row `FOR UPDATE` command interceptor (`customPreCommandInterceptorsTxRequired`, `ProcessEngineConfigurationImpl.java` l.377) + retry (R3/VI.1) | C |
| D-25 | CMMN history records a completed task as "terminated" | E1 (E1-E11) | **1** (+4) | Mapping in the R42 audit projection via a `HistoryEventHandler`; upstream bug report | C |
| D-26 | Exiting a blocking CMMN processTask leaves the called BPMN process running | E16b #2 (6 lines) | **1** (+4) | Case-execution listener cancels sub-instances on exit; upstream PR | C |
| D-27 | The MI compensation handler gets no `loopCounter` | E16a #7 (D-TENDER) | **4** (+2) | Patch compensation to carry the MI item; interim product EMD ledger | D |
| D-28 | Local variables set on the catch execution are lost when the event fires | E16c area 3 | **2** | Adapter rule: write to the branch-scope execution (N-3) | C |
| D-29 | `R3/<start>/P1M` runs 4 times (Flowable 3) | E16a #3 (D-PENSION) | **2** (+4) | R31 fixes the semantics; the validator rewrites or rejects; patch if declared a bug | C |
| D-30 | Missed cycles are not replayed after downtime (both engines) | E16b GP-30 | **2** | R31 catch-up policy and a product missing-period check | C |
| D-31 | `P1M` from the 31st drifts to the 28th (both engines) | E16a #4 | **2** (+4) | R31 anchored calendar cadence (compute from the original start) | D |
| D-32 | Resume does not move deadlines; re-date + activate in one transaction → `ENGINE-03005` | E16b GP-40; E16c GP-45 | **2** | Pause/resume service: paused-clock balance, re-date while suspended (tx 1), then activate (tx 2) (R79) | C |
| D-33 | Early messages are not buffered (`MismatchingMessageCorrelationException`) | E16a #9 | **2** | R38 inbox | C |
| D-34 | No bulk task-completion API | E16a GP-08 | **2** | R64 batch | C |
| D-35 | Correlation to one waiting MI branch is not addressable | E16c N-3 (GP-46, GP-49) | **2** | Branch-addressed correlation in the Sangam API | C |
| D-36 | `TaskQuery` AND of candidate user + groups throws; `candidateUser` expands `ACT_ID_*` groups | PL-O W-4; `TaskQueryImpl.java` l.362–366 | **2** | OR query / product predicate | C |
| D-37 | Jackson pin conflict (feel-scala) and 5 HIGH OSV findings at 2.22.1 | PL-O W-1; E12 | **2** | BOM 2.22.3 (done in the ledger), dependency policy, SBOM (R68) | C |
| D-38 | Spring jars are a compile dependency of operaton-engine | E10; PL-O LIC-1 | **2** (+4) | Exclude and test; upstream PR to make them optional | C |
| D-39 | No metrics or tracing (W6) | SANGAM I §1.2 W6 | **1** | Plugin: command interceptor, job and history hooks → OTel/Micrometer (R13) | C |
| D-40 | Long polling is not cluster-aware (W4) | SANGAM W4 (not re-verified); `FetchAndLockHandlerImpl` in engine-rest | **2** | Sangam worker API + relay wake-up (R1) | C |
| D-41 | Synchronous inline history (W5) | SANGAM W5 | **1** | FC-25 | C → D |
| D-42 | AngularJS webapps are EOL; webapps-neo is a preview | PREM §0, §1.4; LIC-6 | **5** | Sangam UI on neo (R63, R76) | C |
| D-43 | Modeller unreleased; no CMMN editor | E15 | **5** | EC-04 | C → E |
| D-44 | Java-only worker client (W18) | E15 | **3** | FC-37 | C |
| D-45 | No event-broker integration (W11) | SANGAM W11; grep 0 | **5** | FC-02/FC-03 | C → E |
| D-46 | In-process JSR-223 scripting with a weak sandbox (W17) | SANGAM W17 | **5** | R6 script worker; the R44 validator rejects script tasks in Release 1 | C |
| D-47 | Java serialization of variables still exists (W14) | `ProcessEngineConfigurationImpl.java` l.4305 | **1** | Keep `javaSerializationFormatEnabled=false`; reject at the API and in the validator | C |
| D-48 | DMN evaluation rate 54/s vs Flowable 353/s | E16b GP-26 (cause **not verified**) | **2** | Profile; R8 DMN SPI if confirmed | D |
| D-49 | Slower under contention on Oracle/SQL Server; slower task creation | E6; E16b GP-39 (FULL vs AUDIT asymmetry, **low confidence**) | **1** | FC-26 per-definition history level; re-measure at equal levels (R22) | D |
| D-50 | Docs disagree on supported DB versions; no minor-to-minor upgrade guide | PREM §4.1; E14 | **2** | Pin from Sangam CI; Sangam upgrade guides (R32); report upstream | D |
| D-51 | XXE class in parsers (both engines) | E12; R24; `ProcessEngineConfigurationImpl.java` l.789 `enableXxeProcessing` | **2** | R24 hardened factory, DOCTYPE rejected at deploy, flag kept false | C |
| D-52 | BPMN constructs missing in both engines: complex gateway at runtime, multiple/parallel-multiple events, standard loop, data stores, executable message flows | SANGAM II §3.2 | **4** | Patches by MoSCoW (R34/R44) | E |
| D-53 | Structural legacy: `LegacyBehavior`, a 5,000-line mutable configuration, ThreadLocal context (W7, W13, W15) | SANGAM I §1.2 | **2** | **Contain, not eliminate**: a typed Sangam configuration facade, no virtual-thread pinning inside commands, no new tree shapes | D |
| D-54 | Hand-written per-DB SQL scripts (W16) | SANGAM W16; B-19 | **2** | R12: Liquibase is the single source, with the P-1/utf8mb4 change sets | C |
| D-55 | Authorization count query slow at 10k tasks × 300 groups (product SQL, not engine) | E2-E7 F-9; A-16; E16b GP-39 | **2** | Membership-table join, split wildcard branch, approximate counts | C |
| D-56 | REPEATABLE READ refused on MySQL (`ENGINE-12019`): a guard, kept | PL-O MySQL (4) | **2** | R84 per-session READ COMMITTED start-up check | C |

**D count: 56.**

---

## E. Summary

### E.1 Counts per route (primary route only)

| Route | C: OSS (FC) | C: Enterprise (EC) | C total | D |
|---|---|---|---|---|
| 1 Operaton extension point | 21 | 1 | 22 | 12 |
| 2 Product layer | 21 | 3 | 24 | 31 |
| 3 Port of Flowable code | 1 (FC-37) | 0 | 1 | 1 (D-44) |
| 4 Engine patch (fork + ledger + upstream PR) | 6 (FC-01, 24, 30, 51, 56, 57) | 0 | 6 | 8 (D-01, 02, 03, 04, 09, 19, 27, 52) |
| 5 Separate service / UI | 8 | 16 | 24 | 4 |
| X / n/a | 2 (FC-09 n/a; FC-49 X) | 1 (EC-19 X) | 3 | 0 |
| **Total** | **59** | **21** | **80** | **56** |

Route 3 also appears as a **secondary** route on FC-01 (logic), FC-03 (pipeline SPI), FC-06, FC-45 and FC-54.

### E.2 Counts per stage (first delivery stage)

| Stage | FC | EC | C total | D |
|---|---|---|---|---|
| C | 30 | 10 | 40 | 47 |
| D | 12 | 5 | 17 | 8 |
| E | 15 | 5 | 20 | 1 |
| n/a | 2 | 1 | 3 | 0 |

### E.3 Low feasibility or blocked items (explicit)

**Low feasibility:**
1. **FC-19 Native CMMN case migration.** Operaton has no CMMN migration model; it needs a very large patch (XL). **The planned route is run-off (route 2, High)**, so the capability is delivered without native migration.
2. **FC-56 CMMN plan-item lifecycle extensions.** These need a deep patch of Operaton's hard-wired CMMN state machine (XL), and upstream acceptance is not verified. Deferred to E, on demand only (R45).

**Medium feasibility with a named blocker:**
3. **FC-01 Ad-hoc sub-process.** `BpmnParse` rejects the element before any parse listener runs, so only a patch works.
4. **FC-11 Native CMMN external worker.** The external-task entity is process-only. The pattern route works today.
5. **FC-24 Job hand-off and separate timer acquisition.** Engine-core patch.
6. **FC-34 DB-per-tenant.** It depends on D-10 (the JVM-wide `ClockUtil`); in the meantime, one JVM per engine or a shared clock.
7. **FC-55 CMMN event listeners.** Needs impl classes (`CmmnTransformer.setCmmnElementHandlerRegistry`) and new case-execution timer-job code.
8. **D-19 Concurrent fetch.** It depends on the internal OLE-listener contract (I-8) until the patch lands.
9. **D-01 / D-02 DST runaway.** The root cause is not verified, so the patch scope is not yet fixed.
10. **D-16 Bus factor / no LTS.** Organisational. Engineering can only mitigate it.
11. **D-53 Structural legacy.** It cannot be eliminated without replacing Operaton's core, only contained.
12. **EC-04 CMMN editor** (cmmn-js status not verified); **EC-15, EC-16, EC-21** (feature scope unverified, [U]).

### E.4 Can anything not be brought in at all?

**No Flowable capability is impossible to deliver.** Every item in C has at least one route at High or Medium feasibility. For FC-19 that route is run-off rather than native migration.

What will **not** come over in Flowable's own form, by decision or by architecture:
- **Rhino secure-JS sandbox, in-engine Groovy and the shell task (FC-27, 47, 10).** R6/D-8 forbids in-engine scripts. They are replaced by the out-of-process script worker.
- **Runtime definition patching without redeploy (FC-16).** It conflicts with R43 hash-pinned packages. It is replaced by a governed redeploy plus migration.
- **In-engine async futures (FC-14).** They would bypass the L2 lock and R36 operation identity. External work is used instead.
- **OSGi (FC-49) and Enterprise Cloud SaaS (EC-19).** Outside the product envelope (IX.6, R56).
- **Flowable's agenda core, first-class `ACT_RU_ACTINST` and shared-services architecture.** These are engine internals and cannot be grafted onto Operaton's PVM. Their user-visible benefits come through a read model (FC-20) and the link table (FC-31/32).
- **`ProcessDiagramLayoutFactory`.** Not ported: unfixed XXE CVE-2026-91197.
- **Flowable's vendor support and official training.** Not product features; R68 covers the support lifecycle.
- **The Mule task (FC-09).** Not present in the Flowable 8.x source, so there is nothing to port.

On the Operaton side, **D-53 (structural legacy) and D-16 (bus factor) can only be contained, not reduced to zero.** Every other weakness in D has a concrete route.

**Limits of this analysis:**
- Source was read at the SNAPSHOT heads, not at the release tags.
- Effort bands are estimates [I].
- Items marked "not verified" or [U] need a check before planning.
- No code was built or run for this document.
