# sangam-flowable-ports (WS-PORTS)

Every Flowable strength from `spike/results/FLOWABLE-INTO-OPERATON.md` (list C) that is not owned by another
workstream, implemented on Operaton 2.2.0 as a dependency plus plugins (ADR-D-16). Base package `org.sangam.ports`.
Engine source patches: **0**; patch-route items through public setters: P-1 … P-3 ([PATCHES.md](PATCHES.md)).

Build: `cd sangam && mvnw.cmd -q -B -DskipTests -pl sangam-flowable-ports -am install` (compiles; 87 source files).
Phase rule: code complete, tests come in the testing phase (no Testcontainers run).

## Wiring (summary)

```java
PortsSettings ps = new PortsSettings();
ps.setDialect(dialect); ps.setOutbox(new Outbox(dialect));
SangamPortsPlugin ports = new SangamPortsPlugin(ps, null /* DebuggerService only in sandbox profiles */);
cfg.getProcessEnginePlugins().add(engineModulePlugin);   // WS-ENGINE first
cfg.getProcessEnginePlugins().add(ports);                // then the ports
```
Services that need no engine hook are plain classes built by the server from `ProcessEngine`, `Dialect` and
`Transactions` (use `EngineTx.transactions(engine)` so product rows commit with engine state, Q1).

## File-by-file coverage (requirement ids)

Paths under `src/main/java/org/sangam/ports/`.

| File | Covers |
|---|---|
| `SangamPortsPlugin.java`, `PortsSettings.java`, `PortsContext.java` | ADR-D-16 registration of every engine-side port below |
| `PortsSchema.java` | DDL (dialect tokens) of the ports tables: EVT_CHANNEL_DEF, EVT_EVENT_DEF, EVT_LOG, INSTANCE_LINK, CONTENT_ITEM, CONTENT_VERSION (kept by integrator decision: different purpose from ATTACHMENT), FORM_DEF, APP_DEPLOYMENT, PORT_BATCH*, MI_AGGREGATE, TASK_LIFECYCLE, INSTANCE_BUSINESS_STATUS, HRM_*, DYN_INJECTION, SLA_INSTANCE, JOB_ACQUIRE_LOCK, DEBUG_BREAKPOINT (R12 source for WS-ENGINE's Liquibase). Unique start and privileges/tokens are other modules' tables (integrator decision) |
| `support/EngineTx.java` | Q1 engine-transaction access |
| `support/SecureXml.java` | D-51/R24 hardened parser (DOCTYPE rejected) for every model the ports read or rewrite |
| `support/Json.java`, `support/Sql.java` | D-47 (no default typing), A-15 per-dialect limit |
| `adhoc/SangamBpmnParse.java`, `SangamBpmnParseFactory.java` | **FC-01, D-09**, R34, R72 prerequisite; patch P-1 |
| `adhoc/AdHocSubProcessActivityBehavior.java`, `AdHocSupport.java`, `CompleteAdHocSubProcessCmd.java`, `AdHocSubProcessService.java` | FC-01 ordering, completionCondition, cancelRemainingInstances, enabled-activity query, execute, complete |
| `events/model/EventDefinition.java`, `ChannelDefinition.java` | **FC-02, FC-03** `.event`/`.channel` models (Flowable JSON compatible), R38 tenant binding |
| `events/pipeline/InboundEventPipeline.java`, `OutboundEventSerializer.java` | FC-03 pipeline SPI (deserializer, key/tenant detector, payload extractor, serializer) |
| `events/EventRegistry.java` | FC-02/03 versioned, hash-pinned definitions (R43); version list, **retire / restore / delete per version, refused while events are in flight** (`InFlightException`: unprocessed inbox rows or undelivered outbox rows for the key) |
| `history/HistoryQueryService.java` | history query API (backs `HistoryPort`): process instances, cases, activity instances, open activities, tasks from the read model; variables and decisions from Operaton history; filtered, paged, count-capped (A-16), tenant + VI.4 predicate on every list and count |
| `support/Paging.java` | page request/result, dialect `OFFSET/FETCH`, capped counts (A-16) |
| `events/InboundEventProcessor.java` | FC-03 inbound into core `Inbox` (R38), idempotent |
| `events/EventDispatcher.java` | FC-02 event-to-process and event-to-case start, correlation; **FC-04** unique start on WS-INTEGRATION's `UNIQUE_START` via core `UniqueStartPort`; **D-33** buffering; D-35 local-variable (branch) correlation for events |
| `events/OutboundEventService.java`, `SendEventDelegate.java`, `EventRegistryParseListener.java` | FC-02 send-event task / throw events via outbox (R4, R36 deterministic message id), `sangam:eventType` |
| `events/EventLog.java` | **FC-43** DB event log (`EVT_LOG`) |
| `events/channel/ChannelAdapters.java`, `HttpOutboundAdapter.java`, `WebhookInboundEndpoint.java`, `KafkaAdapters.java`, `JmsAdapters.java`, `RabbitAdapters.java`, `ChannelPublisher.java`, `InboundChannelManager.java` | **FC-03, D-45, R83, R71**: HTTP/webhook (HMAC), Kafka, JMS, RabbitMQ adapters (optional deps), relay publisher, consumer lifecycle; air-gapped egress allow-list |
| `dynamic/DynamicInjectionService.java` | **FC-15** inject user task / embedded (dynamic) sub-process into one instance: derived model, deploy, migrate, start (R43/R49 policy hook) |
| `dynamic/CrossInstanceStateChangeService.java` | **FC-17** change state across call-activity boundaries (R49) |
| `migration/MigrationDocument.java`, `MigrationDocumentService.java` | **FC-18, R48** JSON document, 1:1/N:1/1:N, hooks, variables, dry run, batch handler (EC-12 back end) |
| `cmmn/SangamCmmnElementHandlerRegistry.java`, `SangamEventListenerActivityBehavior.java`, `CmmnTimerJobHandler.java`, `CmmnEventListenerService.java` | **FC-55, D-08 feature** timer/user/generic event listeners; patch P-2 |
| `cmmn/PortsCmmnTransformListener.java` | FC-55 marks; **FC-56** (lifecycle-listener part) |
| `cmmn/CmmnTaskPatterns.java` | **FC-11** (pattern), **FC-58** service / HTTP / external-worker / send-event CMMN tasks via process task |
| `cmmn/CaseLifecycleService.java` | **FC-59** case reactivation (incl. historic), **FC-19** run-off / cut-over |
| `history/PortsHistoryEventHandler.java`, `HistoryEventMapper.java` | **FC-32** entity links, FC-43 in-process typed bus feed, FC-25 read-model feed (optional) |
| `history/HistoryProjector.java` | **FC-25** CQRS read model (exactly-once checkpoint), **FC-20** open activity instances, D-41 |
| `history/EngineEventBus.java` | **FC-43** global typed dispatcher (in-process listeners) |
| `links/InstanceLinkService.java` | **FC-31** reference links, **FC-32** entity links query (root, descendants), process/case involvement |
| `task/TaskEventDispatcher.java` | task listeners on more events (claim, unclaim, delegate, resolve, owner, due date, priority, suspend, ...), **FC-52** |
| `task/TaskLifecycleService.java` | **FC-52** claimTime/claimedBy, inProgressStart, suspendedTime, instance businessStatus (R42/R65) |
| `task/TaskEnhancementService.java` | sub-tasks, task identity links (participant, watcher, custom), instance participants (A-9 plus) |
| `variables/MultiInstanceAggregation.java` | **FC-21** MI variable aggregation without parent-variable OLE (D-23 friendly) |
| `variables/VariableListenerRegistry.java` | **FC-05** code-level variable listeners |
| `bpmn/BpmnExtensionsParseListener.java`, `TriggerableServiceTaskBehavior.java`, `SkipExpressionSupport.java`, `TerminateExtendedBehavior.java`, `SangamBpmn.java` | **FC-12, FC-13, FC-22** (handed over by WS-ENGINE) |
| `bpmn/SangamModelValidator.java` | **FC-46** pluggable pre-deploy validator (D-09, D-51, D-52, FC-13 rules, Flowable leftovers, custom rules; R44) |
| `dmn/PriorityHitPolicies.java` | **FC-54** PRIORITY and OUTPUT ORDER (handed over by WS-ENGINE) |
| `dmn/DecisionServiceEvaluator.java` | **FC-53** DMN decision services, native on Operaton's DMN engine |
| `content/FileSystemDocumentStore.java`, `S3DocumentStore.java`, `ContentService.java`, `OutputTemplateRenderer.java` | **FC-40, EC-18, R74** versioned documents, SHA-256, scan hook, S3-compatible (SigV4, no SDK), output templates |
| `forms/FormDefinition.java`, `FormValidator.java`, `FormJsConverter.java`, `SangamFormService.java` | **FC-39, EC-02 (runtime), R47** versioned forms, server-side validation, outcomes, variable-scoped task forms, start forms, form-js/Flowable import |
| `app/AppBundle.java`, `AppDeploymentService.java` | **FC-41, R43** app bundles (zip-slip/bomb guarded), hash-pinned, all-or-nothing deployment incl. forms/events/channels |
| `batch/ProductBatchService.java`, `StandardBatchHandlers.java` | batch service (backs `OpsPort` batches), **D-34** bulk task completion, bulk correlation/cancel; **pause / resume**, **four-eyes approval** (`FourEyesPolicy`, approver ≠ creator, approve/reject, approval inbox), paged list |
| `tenancy/TenantEngineRegistry.java` | **FC-34** DB-per-tenant routing (engine per tenant, shared job executor, routing DataSource); D-10 limit |
| `tenancy/TenantDefinitionResolver.java` | **FC-33** tenant → shared → default fallback (R19 hook) |
| `tenancy/ChangeTenantService.java` | **FC-35** change tenant (copy definition, cut over instance, product-row rewriters) |
| `jobs/FilteredAcquireJobsCmd.java`, `SplitJobExecutors.java`, `AsyncJobHandOffInterceptor.java` | **FC-24** separate timer executor, global acquire lock, post-commit hand-off; patch P-3 |
| `sla/SlaService.java` | **EC-08, R79** SLA clocks, warning/breach events, SLA pause/resume (D-32 SLA half); aggregates (backs `InsightsPort.sla`): **breaches** (paged), **at-risk** (warned or due within a window), **per-definition stats** (total/running/paused/met/warned/breached, avg/max elapsed, breach rate) |
| `debug/DebuggerService.java` | **FC-44, EC-13, R80** debugger back end, sandbox tenants only |
| `idm/IdmFacade.java` | **FC-42** Flowable IDM-style privilege/mapping/token API, stateless, delegating to WS-SECURITY (`SEC_PRIVILEGE`, `SEC_PRIVILEGE_MAPPING`, `SEC_API_TOKEN`) through core `IdentityExtrasPort` |
| `worker/FlowableWorkerCompat.java` | **FC-36** Flowable external-worker REST parity (translation onto ARCHITECTURE §4) |
| `modelimport/FlowableModelImporter.java` | **FC-05** import mapping, **EC-21** incompatibility analyser |
| `../sangam-core/.../core/ports/EventEnvelope.java`, `DocumentStore.java`, `UniqueStartPort.java`, `IdentityExtrasPort.java` | engine-neutral SPIs added to core (no existing core file changed); the last two avoid a module cycle (integration → ports) and a Spring Security dependency |

## Coverage map: every item of the gap list

Status: **Done** (implemented here, compiles), **Owned** (other workstream), **X** (not replicated by decision),
**Gap** (not delivered; reason given), **n/a**.

### C.1 BPMN (FC-01 … FC-22)

| ID | Item | Status | Where |
|---|---|---|---|
| FC-01 | Ad-hoc sub-process | Done (P-1) | `adhoc/*` |
| FC-02 | Event-registry BPMN events | Done | `events/*` |
| FC-03 | Channels Kafka/JMS/RabbitMQ, pipeline | Done | `events/pipeline/*`, `events/channel/*` |
| FC-04 | Correlation parameters, unique start | Done (table owned by WS-INTEGRATION) | `EventDispatcher` → `UniqueStartPort` → `UNIQUE_START` |
| FC-05 | Variable-listener events | Done | `modelimport/FlowableModelImporter`, `variables/VariableListenerRegistry` |
| FC-06 | HTTP task | Owned: WS-INTEGRATION (HTTP connector); importer maps to its worker topic | — |
| FC-07 | Mail task | Owned: WS-INTEGRATION | — |
| FC-08 | Camel task | Owned: WS-INTEGRATION (stage E) | — |
| FC-09 | Mule task | n/a (absent in Flowable 8) | — |
| FC-10 | Shell task | X in engine (R6/D-8); out-of-process command worker: WS-INTEGRATION | importer flags it |
| FC-11 | External worker for CMMN | Done (pattern); native = Gap P-5 (stage E) | `cmmn/CmmnTaskPatterns` |
| FC-12 | Triggerable service task | Done | `bpmn/TriggerableServiceTaskBehavior` |
| FC-13 | Skip expressions | Done | `bpmn/SkipExpressionSupport` |
| FC-14 | Future/async Java delegates | X (would bypass L2/R36; external work instead) | — |
| FC-15 | Dynamic BPMN injection | Done | `dynamic/DynamicInjectionService` |
| FC-16 | Runtime definition patching | X (conflicts with R43; redeploy + migration = FC-15/FC-18) | — |
| FC-17 | Change state across call activities | Done | `dynamic/CrossInstanceStateChangeService` |
| FC-18 | Migration builder variants, JSON document | Done (historic-instance migration: Gap, Operaton history bound to definition) | `migration/*` |
| FC-19 | CMMN case migration | Done as run-off/cut-over; native = Gap P-5 | `cmmn/CaseLifecycleService` |
| FC-20 | Runtime activity-instance table | Done as read model (runtime table not added, B-21) | `history/HistoryProjector` |
| FC-21 | MI variable aggregation | Done | `variables/MultiInstanceAggregation` |
| FC-22 | terminateAll / terminateMultiInstance | Done | `bpmn/TerminateExtendedBehavior` |

### C.2 Timers, jobs, history, scripting, variables, DB (FC-23 … FC-35)

| ID | Item | Status | Where |
|---|---|---|---|
| FC-23 | Business calendar per timer | Owned: WS-ENGINE `TaggedDurationBusinessCalendar` (ports' CMMN timer listener and SLA use it / `WorkCalendar`) | — |
| FC-24 | Async executor | Config route: WS-ENGINE; executor split, global lock, hand-off: Done (P-3) | `jobs/*` |
| FC-25 | Async history | Typed event stream: WS-ENGINE (`EngineEventSink`); CQRS read model: Done | `history/*` |
| FC-26 | History per process/activity | Owned: WS-ENGINE `PerDefinitionHistoryLevel` (per key); per-activity level requested (note 7) | — |
| FC-27 | Secure JS sandbox | X (Rhino/GraalJS not ported); script worker: WS-INTEGRATION | — |
| FC-28 | JSON variables | Owned: WS-ENGINE | — |
| FC-29 | Long text variables | Owned: WS-ENGINE | — |
| FC-30 | MySQL ms precision | Owned: WS-ENGINE (P-1 there) | — |
| FC-31 | Reference links | Done | `links/InstanceLinkService` |
| FC-32 | Entity links | Done | `history/PortsHistoryEventHandler`, `links/*` |
| FC-33 | Callee tenant fallback | Done | `tenancy/TenantDefinitionResolver` |
| FC-34 | DB-per-tenant | Done with the D-10 limit (real clock only when several engines share a JVM) | `tenancy/TenantEngineRegistry` |
| FC-35 | Change-tenant tooling | Done (cut-over; history stays in source tenant) | `tenancy/ChangeTenantService` |

### C.3 Worker API, SDKs, forms, content, app, IDM, tooling (FC-36 … FC-52)

| ID | Item | Status | Where |
|---|---|---|---|
| FC-36 | External-worker REST API | Worker API: WS-REST (ARCHITECTURE §4); Flowable parity translation: Done | `worker/FlowableWorkerCompat` |
| FC-37 | Worker clients in 5 languages | Owned: WS-SDK | — |
| FC-38 | Spring Boot starters + actuator | Owned: WS-REST (sangam-server) | — |
| FC-39 | Forms API | Done | `forms/*` |
| FC-40 | Content API | Done | `content/*`, core `DocumentStore` |
| FC-41 | App engine | Done | `app/*` |
| FC-42 | IDM privileges, tokens | Owned: WS-SECURITY (`PrivilegeService`, `ApiTokenService`); Flowable-style facade: Done | `idm/IdmFacade` → `IdentityExtrasPort` |
| FC-43 | Typed event dispatcher + DB event log | Outbox sink: WS-ENGINE; in-process bus + `EVT_LOG`: Done | `history/EngineEventBus`, `events/EventLog` |
| FC-44 | Debug agenda | Back end: Done; UI: WS-UI | `debug/DebuggerService` |
| FC-45 | Auto-layout / image generators | Owned: WS-UI (bpmn-js); `ProcessDiagramLayoutFactory` X (XXE) | — |
| FC-46 | Pluggable validators | WS-ENGINE parse listeners + Done here (pre-deploy validator) | `bpmn/SangamModelValidator` |
| FC-47 | Groovy static engine | X in engine (D-8); script worker: WS-INTEGRATION | — |
| FC-48 | JMX | X, replaced by OTel/Micrometer (WS-ENGINE `EngineMetrics`, WS-REST binding) | — |
| FC-49 | OSGi | X (outside the product envelope) | — |
| FC-50 | CXF web-service task | Owned: WS-INTEGRATION (SOAP connector) | — |
| FC-51 | CockroachDB | Gap: stage E dialect work (WS-ENGINE / persistence) | — |
| FC-52 | Task/instance lifecycle fields | Done | `task/*` |

### C.4 DMN, C.5 CMMN (FC-53 … FC-59) and shared services

| ID | Item | Status | Where |
|---|---|---|---|
| FC-53 | Decision services | Done (native, no second DMN engine) | `dmn/DecisionServiceEvaluator` |
| FC-54 | PRIORITY, OUTPUT ORDER | Done | `dmn/PriorityHitPolicies` |
| FC-55 | CMMN event listeners (timer fix) | Done (P-2); requires WS-ENGINE `rejectCmmnEventListeners=false` | `cmmn/*` |
| FC-56 | Plan-item lifecycle extensions | Partial: lifecycle listeners Done; state-machine extensions = Gap P-4 (stage E) | `cmmn/PortsCmmnTransformListener` |
| FC-57 | Repetition with collections, max count | Gap P-4 (stage E, engine state-machine patch) | — |
| FC-58 | CMMN task types | Done via process-task patterns (script task excluded, R6; case page = WS-UI) | `cmmn/CmmnTaskPatterns` |
| FC-59 | Case reactivation | Done | `cmmn/CaseLifecycleService` |
| — | Batch service | Done (product batches; Operaton batches stay for migration/modification) | `batch/*` |
| — | Identity links | Done (task types + instance involvement) | `task/TaskEnhancementService` |
| — | Shared-services architecture | X (internal design, gap list C.5 note) | — |

### C.6 Flowable Enterprise (EC-01 … EC-21)

| ID | Item | Status |
|---|---|---|
| EC-01 | Work app / case workspace | Owned: WS-UI |
| EC-02 | Rich forms designer + runtime | Designer: WS-UI; runtime + validation: Done (`forms/*`) |
| EC-03 | Pages (low code) | Owned: WS-UI (R77), stage E |
| EC-04 | Design modeller | Owned: WS-UI |
| EC-05 | Git, deployment approval, test models | Git/test models: WS-UI/WS-DEPLOY; approval gate: Done (`AppDeploymentService.ApprovalPolicy`) |
| EC-06 | Data objects / ERD (R78) | Gap for stage C: stage D/E, not routed to WS-PORTS; integrator to assign (proposed WS-REST + WS-UI) |
| EC-07 | Service registry | Owned: WS-INTEGRATION (R71) |
| EC-08 | SLA models | Done (`sla/SlaService`); instance pause/resume: WS-ENGINE |
| EC-09 | Full audit trails | Owned: WS-ENGINE (history → audit chain) |
| EC-10 | Business dashboards | Owned: WS-UI (R65; source tables `HRM_*` from here) |
| EC-11 | Control operations | Owned: WS-UI |
| EC-12 | Hub (migration, job health) | UI: WS-UI; migration dry run/document: Done (`migration/*`) |
| EC-13 | Inspect debugger | UI: WS-UI; back end: Done (`debug/*`) |
| EC-14 | Agents / AI | Stage E (R72); prerequisite FC-01 Done; owner proposed WS-PACKS/WS-INTEGRATION |
| EC-15 | Engage | Owned: WS-INTEGRATION (R81), stage E |
| EC-16 | Mobile | Owned: WS-UI |
| EC-17 | Multi-tenant administration | Owned: WS-UI + WS-SECURITY |
| EC-18 | Content / output templates | Done (`content/*`) |
| EC-19 | Enterprise Cloud | X (self-hosted, R56) |
| EC-20 | Operator permissions + audit | Owned: WS-SECURITY |
| EC-21 | LEAP analyser | Done (`modelimport/FlowableModelImporter` + validator rules) |

### D. Operaton weaknesses (D-01 … D-56)

| ID | Status |
|---|---|
| D-01, D-02, D-03, D-04, D-05, D-06, D-07 | Owned: WS-ENGINE |
| D-08 | Reject: WS-ENGINE; feature: Done here (FC-55) |
| D-09 | Done here (FC-01, P-1) |
| D-10 | Owned: WS-ENGINE (`OperatonClock`); ports contain it in `TenantEngineRegistry` (guard) |
| D-11 | Owned: WS-ENGINE (FC-23) |
| D-12, D-13 | Owned: WS-ENGINE |
| D-14, D-15 | Owned: WS-SECURITY |
| D-16 | Organisational (integrator: patch ledgers + upstream offers, both ledgers) |
| D-17, D-18, D-19, D-20, D-21, D-22, D-23, D-24 | Owned: WS-ENGINE |
| D-25, D-26 | Owned: WS-ENGINE |
| D-27 | Owned: WS-ENGINE (stage D engine patch) |
| D-28, D-29, D-30, D-31, D-32 | Owned: WS-ENGINE (SLA-clock half of D-32: `sla/SlaService`) |
| D-33 | Done here for events (`EventDispatcher` + core `Inbox` buffering); inbox loop: WS-INTEGRATION |
| D-34 | Done here (`batch/*`) |
| D-35 | WS-ENGINE (`correlateMessageToBranch`); events: Done here (local-scope correlation parameters) |
| D-36 | Owned: WS-ENGINE / WS-SECURITY |
| D-37, D-38, D-39 | Owned: WS-ENGINE (D-39 binding: WS-REST) |
| D-40 | Owned: WS-REST |
| D-41 | WS-ENGINE (stream) + Done here (read model) |
| D-42, D-43 | Owned: WS-UI |
| D-44 | Owned: WS-SDK |
| D-45 | Done here (FC-02/03) |
| D-46, D-47 | Owned: WS-ENGINE |
| D-48, D-49 | Owned: WS-ENGINE (D-48 stage D profiling) |
| D-50 | Organisational / WS-DEPLOY |
| D-51 | WS-ENGINE + `support/SecureXml`, validator here |
| D-52 | Validator rejects (here); runtime support = stage E engine patches (WS-ENGINE) |
| D-53, D-54, D-55, D-56 | Owned: WS-ENGINE (D-55 with WS-SECURITY/persistence) |

### FW. Flowable weaknesses (FW-01 … FW-47)

Register, evidence and patch requests: `spike/results/FLOWABLE-WEAKNESS-AUDIT.md`. Verdicts: **n/a** (not applicable to
the ports), **avoided** (avoided by design), **fixed** (was present, fixed here). Paths are relative to
`src/main/java/org/sangam/ports/`; line numbers as of 2026-11-05. FWT = scenario tests in the audit file, §5.

| FW | Weakness | Verdict | File:line | FWT |
|---|---|---|---|---|
| FW-01 | Engine XXE (CVE-2026-91197), unhardened parsers | avoided (+ size/depth guards) | `support/SecureXml.java:27,39,48,72`; `modelimport/FlowableModelImporter.java:74`; `bpmn/SangamModelValidator.java:90` | FWT-001, 002, 003, 004 |
| FW-02 | Jackson DoS / unbounded payloads | fixed | `support/Json.java:26-41`; `events/InboundEventProcessor.java:33,126`; `events/channel/WebhookInboundEndpoint.java:45` | FWT-005, 006, 052 |
| FW-03 | Correlation business-key bug (child execution) | avoided | `events/EventDispatcher.java:126` | FWT-007 |
| FW-04 | Async continuation after external completion | avoided | `jobs/AsyncJobHandOffInterceptor.java:71` (post-commit only); Operaton one-transaction completion | FWT-008 |
| FW-05 | Slow fetch scan | n/a (no fetch in the ports; engine D-19) | — | — |
| FW-06 | No extendLock | n/a (Operaton native) | — | — |
| FW-07 | No fencing token | fixed in the ports; **server part open: PR-1** (`sangam-server/.../web/FlowableWorkerCompatController.java:184-197`) | `worker/FlowableWorkerCompat.java:153,166` | FWT-009, 010, 011, 012 |
| FW-08 | Engine reset re-offers expired work before A-10 | avoided | `jobs/SplitJobExecutors.java:26` | FWT-013 |
| FW-09 | Compensation FK bug with wait-state handlers | avoided | `adhoc/AdHocSubProcessActivityBehavior.java:111`; `dynamic/DynamicInjectionService.java:72` | FWT-014, 015, 038 |
| FW-10 | Compensation event sub-process unsupported | n/a (Operaton native, B-1) | — | — |
| FW-11 | Link / signal end / message throw / conditional start rejected | n/a (Operaton native, B-1) | — | — |
| FW-12 | Conditional events not evaluated on change | avoided | `modelimport/FlowableModelImporter.java:284` (`mapVariableListener`) | FWT-016 |
| FW-13 | Exclusive external-worker jobs: parallel MI fetch returns 0 | avoided | `modelimport/FlowableModelImporter.java:56` (`exclusive` mapped 1:1); external tasks are not jobs | FWT-053 |
| FW-14 | Timer re-dating gaps | n/a (WS-ENGINE, D-32) | — | — |
| FW-15 | Change-state re-creates the running task | avoided | `dynamic/CrossInstanceStateChangeService.java:40-49`; `dynamic/DynamicInjectionService.java` (`startBeforeActivity`) | FWT-054 |
| FW-16 | Slow count under authorization | fixed | `history/HistoryQueryService.java:183,227,240,258`; `support/Paging.java:22,31,54,68` | FWT-017, 018, 019 |
| FW-17 | AUDIT history default, unordered assignment log | n/a (engine config) | — | — |
| FW-18 | `ACT_RU_ACTINST` growth | avoided | `history/HistoryQueryService.java` `openActivities` (FC-20 read model) | FWT-055 |
| FW-19 | `TaskService` refuses CMMN tasks | n/a (Operaton native, B-9) | — | — |
| FW-20 | No OSS UI / Helm / Quarkus | n/a (product UI, D-42/D-43) | — | — |
| FW-21 | OSS viability | n/a (decision record) | — | — |
| FW-22 | Lower throughput | n/a (Operaton is the engine) | — | — |
| FW-23 | Q1 rollback defect | avoided | `support/EngineTx.java:42-55` (engine commands only; listener in WS-ENGINE) | FWT-056 |
| FW-24 | Engine clock, not DB clock | n/a (shared, WS-ENGINE D-10) | — | — |
| FW-25 | DST autumn-fold runaway | n/a (shared, WS-ENGINE A-12) | — | — |
| FW-26 | Hot-root OLE storm | n/a (shared, WS-ENGINE D-24) | — | — |
| FW-27 | Model importer gaps (Activiti ns, dmn, scripts) | fixed | `modelimport/FlowableModelImporter.java:45,113,238`; `bpmn/SangamModelValidator.java:56` | FWT-020, 021, 022 |
| FW-28 | Poison inbound events redelivered for ever | fixed | `events/InboundEventProcessor.java:100,142` | FWT-023, 024, 025, 052 |
| FW-29 | Payload tenant detection / default-tenant mismatch | fixed | `events/pipeline/InboundEventPipeline.java:154,168`; `events/EventRegistry.java:359`; `events/EventDispatcher.java:232` | FWT-026, 027, 028, 029 |
| FW-30 | Reserved switch variables settable by events/workers | fixed | `events/pipeline/InboundEventPipeline.java:212`; `events/EventDispatcher.java:101`; `events/EventRegistry.java:335`; `worker/FlowableWorkerCompat.java:211` | FWT-030, 031 |
| FW-31 | Correlation broadcast without values | fixed | `events/EventDispatcher.java:119` | FWT-032 |
| FW-32 | Unique start claims `"null"` | fixed | `events/pipeline/InboundEventPipeline.java:125`; `events/EventDispatcher.java:154` | FWT-033 |
| FW-33 | Expression injection in injected specs | fixed | `dynamic/DynamicInjectionService.java:97,276` | FWT-034 |
| FW-34 | Injection × compensation, fragment refs, unbounded injections | fixed | `dynamic/DynamicInjectionService.java:72,180,236` | FWT-035, 036, 037, 038 |
| FW-35 | Ad-hoc sequential race | fixed | `adhoc/AdHocSubProcessService.java:89,117` | FWT-039 |
| FW-36 | Exclusive-job rule lost in split executors | fixed | `jobs/FilteredAcquireJobsCmd.java:68,77` | FWT-040 |
| FW-37 | Hand-off failure modes | fixed | `jobs/AsyncJobHandOffInterceptor.java:53,71` | FWT-041 |
| FW-38 | Debugger on non-sandbox data | fixed | `debug/DebuggerService.java:66,76,94,134` | FWT-042, 043 |
| FW-39 | Unbounded content uploads | fixed | `content/ContentService.java:57,200` | FWT-044 |
| FW-40 | Content by id without tenant check | fixed (server bridge also checks; PR-4) | `content/ContentService.java:77` | FWT-045 |
| FW-41 | Outbound HTTP: SSRF, redirects, timeouts, bodies | fixed | `events/channel/HttpOutboundAdapter.java:30,32,45,46,60` | FWT-046 |
| FW-42 | XML / expression event deserializers | avoided | `events/pipeline/InboundEventPipeline.java:92` | FWT-047 |
| FW-43 | Early messages not buffered | avoided | `events/InboundEventProcessor.java` (inbox before dispatch, D-33) | FWT-048 |
| FW-44 | Shell / in-engine script tasks | avoided | `modelimport/FlowableModelImporter.java:113` (`scriptTask`), `:220` (`shell`) | FWT-057 |
| FW-45 | App bundle archive attacks | fixed | `app/AppBundle.java:31,84,88` | FWT-049 |
| FW-46 | Change tenant by table update / `"null"` tenant | fixed | `tenancy/ChangeTenantService.java:27,58` | FWT-050 |
| FW-47 | Unauthenticated inbound webhooks | avoided | `events/channel/WebhookInboundEndpoint.java:60` | FWT-051 |

Totals: n/a 13, avoided 14, fixed 20. Every FWT-001 … FWT-057 is referenced above. The only open (c) part is the
`sangam-server` fallback of FW-07 (patch request PR-1); it is outside this module.

### Not replicated by decision (and why)

| Flowable capability | Reason |
|---|---|
| In-engine scripting: Rhino secure JS (FC-27), Groovy (FC-47), shell task (FC-10) | R6/D-8: scripts run out of process |
| Runtime model patching (FC-16, `DynamicBpmnService`) | Conflicts with R43 hash-pinned packages; FC-15 redeploy + migration instead |
| In-engine async futures (FC-14) | Would bypass the L2 lock and R36 operation identity |
| OSGi (FC-49), Enterprise Cloud (EC-19) | Outside the product envelope (IX.6, R56) |
| `ProcessDiagramLayoutFactory` (FC-45) | Unfixed XXE CVE-2026-91197 |
| JMX (FC-48) | Replaced by OTel/Micrometer |
| Runtime `ACT_RU_ACTINST`, agenda core, shared services | Engine internals; benefits delivered by FC-20 read model and FC-31/32 links |

## Integration notes

1. **Schema (WS-ENGINE).** Copy `PortsSchema` tables into the per-dialect Liquibase changelogs; `PortsSchema.create` is
   for development only.
1b. **Schema changes for sangam-persistence (2026-11 follow-up; dev `PortsSchema.create` has them):**
   - `EVT_CHANNEL_DEF`, `EVT_EVENT_DEF`: new columns `state {str64} NOT NULL` (`ACTIVE`/`RETIRED`; existing rows → `ACTIVE`),
     `retired_by {str}`, `retired_at {ts}`.
   - `PORT_BATCH`: new columns `reason {str}`, `four_eyes_flag {small} NOT NULL` (existing rows → 0), `approved_by {str}`,
     `approved_at {ts}`, `completed_at {ts}`; new index `PORT_BATCH_STATE (tenant_id, state, created_at)`; new states
     `PENDING_APPROVAL`, `PAUSED`, `REJECTED`.
   - `SLA_INSTANCE`: new column `definition_key {str}`; new index `SLA_INSTANCE_STATS (tenant_id, definition_key, sla_key, started_at)`.
   - New indexes `HRM_PI_QUERY ON HRM_PROCESS_INSTANCE (tenant_id, scope_type, definition_key, start_time)` and
     `HRM_TASK_QUERY ON HRM_TASK (tenant_id, process_instance_id, task_def_key)`.
   - No new tables. Read-model rows now store `""` for "no tenant" (as the other product tables).
1c. **REST bridges (WS-REST / integrator).** `HistoryPort` → `HistoryQueryService` (pass the VI.4 predicate as
   `AuthPredicate` over alias `r`; incidents, job log and operation log stay on Operaton history). `InsightsPort.sla` →
   `SlaService.stats` (avg business days = `avgElapsedMillis` through the tenant calendar), plus `breaches`/`atRisk`.
   `OpsPort` batches → `ProductBatchService` (`fourEyesRequired` for previews, `approve`/`reject`/`pause`/`resume`/
   `cancel`; map `BatchStateException` to 409). Event-registry delete → `retire*` then `delete*`; map
   `InFlightException` to 409. `SlaDefinition` gained a `definitionKey` component.
1a. **Ports to bind (integrator).** `UniqueStartPort` → WS-INTEGRATION's `UniqueStartGuard` (same method shapes; the
   event's unique reference value is stored as `business_key`, `source` = `evt:<channel>`), passed to `EventDispatcher`.
   `IdentityExtrasPort` → a thin adapter over WS-SECURITY's `PrivilegeService` and `ApiTokenService`, passed to `IdmFacade`.
2. **Inbox routing (WS-INTEGRATION).** The inbox loop must hand rows whose source starts with `evt:` to
   `EventDispatcher` (`EventDispatcher.owns(m)`) and run its transaction as an engine command (`EngineTx.transactions`).
3. **Relay publishers (WS-INTEGRATION).** Destinations `channel:*` → `ChannelPublisher`; `history-projector` →
   `HistoryProjector` (only when `readModelFeed=true`).
4. **WS-ENGINE settings.** With FC-55 on, set `rejectCmmnEventListeners=false`. Register `SangamPortsPlugin` after
   `SangamProcessEnginePlugin`. Engine's business-rule tasks must declare `decisionRefBinding` (their D-12 rule).
5. **REST (WS-REST)** endpoints to mount: `POST /events/{channel}` → `WebhookInboundEndpoint`; ad-hoc
   (`AdHocSubProcessService`), CMMN event listener occur, forms (`SangamFormService`), content, apps, product batches,
   migration documents, links, SLA, dynamic injection, cross-instance moves, debugger (sandbox profile), and an optional
   Flowable worker compatibility endpoint using `FlowableWorkerCompat`. Every call needs the VI.4 check (WS-SECURITY).
6. **Clock.** `jobs/*`, `TenantEngineRegistry` and `IdmFacade` read the engine clock through core
   `SangamClockHolder.now()` (no `ClockUtil` use in this module).
7. **History level per activity (WS-ENGINE).** FC-26 also asks for per-activity levels; the engine's
   `PerDefinitionHistoryLevel` is per key. Request: accept `key#activityId` entries (the ports removed their own level
   to avoid two custom levels).
8. **Engine events.** `EngineEventBus` (in-process, synchronous listeners) complements WS-ENGINE's `EngineEventSink`
   (outbox). Both are fed from history events; no duplicate outbox stream is written unless `readModelFeed=true`.
9. **Packs (WS-PACKS)** can ship app bundles (`AppBundle.of(map)`) and use `sangam:aggregateTo`, `sangam:eventType`,
   `sangam:taskType` in their models.

## Gaps (explicit)

- FC-56 state-machine extensions and FC-57 (P-4, stage E); FC-11/FC-19 native (P-5, stage E).
- FC-18 historic-instance migration (Operaton history is bound to the original definition).
- FC-34 several tenant engines in one JVM with a moved clock (needs WS-ENGINE's per-engine clock SPI, D-10).
- FC-51 CockroachDB, EC-06 data objects: stage E/D, not in WS-PORTS stage C.
- Not yet tested (phase rule): every port compiles; behaviour tests come in the testing phase.

## Third-party dependencies

| Dependency | Scope | Licence |
|---|---|---|
| `org.operaton.bpm:operaton-engine` 2.2.0 (BOM) | compile | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` (BOM 2.22.3) | compile | Apache-2.0 |
| `org.slf4j:slf4j-api` 2.0.17 | compile | MIT |
| `org.apache.kafka:kafka-clients` 3.9.2 | optional | Apache-2.0 |
| `jakarta.jms:jakarta.jms-api` 3.1.0 | optional | EPL-2.0 OR GPL-2.0 with Classpath exception |
| `com.rabbitmq:amqp-client` 5.37.1 | optional | Apache-2.0 / MPL-2.0 / GPL-2.0 (used under Apache-2.0) |

Flowable logic attribution: [NOTICE](NOTICE).
