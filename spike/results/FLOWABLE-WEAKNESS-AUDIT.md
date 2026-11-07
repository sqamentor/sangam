# Flowable weakness audit: register FW-01…FW-47, verdicts, fixes, patch requests, scenario tests

Date: 2026-11-05. Workstream: FLOWABLE-WEAKNESS-AUDIT. Scope: every Flowable-derived feature in `sangam/sangam-flowable-ports`,
plus the Flowable-derived code in `sangam-server` (bridges, `FlowableWorkerCompatController`) and the ports' callers in
`sangam-integration` / `sangam-rest`.

Companion register: Operaton's weaknesses are D-01…D-56 in `FLOWABLE-INTO-OPERATON.md`. This file is the Flowable side, so
that **both engines' strengths are kept and both engines' weaknesses are fixed**: a Flowable strength ported into Sangam must
not bring the matching Flowable weakness along.

Markers: **[F]** measured or cited fact; **[I]** judgement; **[S]** found by this audit's source reading of the ports (not
measured). Build after the fixes: `mvnw.cmd -q -B -DskipTests -pl sangam-flowable-ports -am install` → exit 0. No tests run.
Nothing committed.

---

## 1. Register (FW-01…FW-47)

Sources: E1–E17 reports, `scorecard.md`, `patch-ledger-flowable.md` (PL-F), `E12-E15-report.md`, SANGAM.md XI.10.1–XI.10.4,
and the source reading of the ports (FW-27…FW-47 are weaknesses of Flowable *features* that a port can inherit).

| FW | Flowable weakness | Evidence | Class |
|---|---|---|---|
| FW-01 | **XXE in the engine** (CVE-2026-91197, CWE-611, `ProcessDiagramLayoutFactory.parseXml()`), unfixed through 8.0.0; parsers not hardened by default | E12 [F]; GHSA-8x6x-vw5h-q563; issue #4216 / PR #4254 open | security |
| FW-02 | **Jackson 3.0.1 DoS findings** (10 HIGH: document-length, nesting-depth, number-length bypasses, ReDoS); fix only on a newer minor line | E12 [F] | security |
| FW-03 | **Correlation business-key bug**: the one-argument `processInstanceBusinessKey` compares the child execution's own key, so a catch in a parallel branch is missed | E2-E7 F-1, E5-5 [F]; PL-F "Fix C1"; XI.10.4 | correctness |
| FW-04 | **Async continuation after external completion**: completion and the next step are two transactions; a failing continuation leaves the completion committed and the lease gone | E2-E7 F-2; PL-F F11 [F]; XI.10.4 | atomicity |
| FW-05 | **Slow fetch scan**: no topic or "lock expired" filter; every fetch loaded every external-worker job of every topic (1000 → 0 rows only after F9) | PL-F F3/F9 [F]; XI.10.4 | performance |
| FW-06 | **No extendLock / heartbeat API** for external-worker jobs | PL-F F1, Q3 [F]; XI.10.1 Q3 | lease |
| FW-07 | **No fencing**: complete/fail compare only `lockOwner`; an expired lease is accepted; a re-acquiring worker cannot be told apart from its stale self | PL-F F2/F6, Q3 [F]; XI.10.1 Q3 | lease |
| FW-08 | **Engine reset re-offers expired work** before the product's A-10 policy (`ResetExpiredJobsRunnable` covers external-worker jobs): the measured double-payment path | PL-F F12; E3-hz [F]; A-10 | lease |
| FW-09 | **Compensation FK bug**: throwing compensation with a wait-state handler fails with `act_fk_exe_parent` (12/12 probe cells fail; only `manualTask` works) | E16a GP-13, D-TENDER [F] | correctness |
| FW-10 | Compensation event sub-process rejected / no-op | E11 [F] (scorecard: 1 engine patch) | coverage |
| FW-11 | Link events, signal end, message throw, process-level conditional start rejected by validation | E11 [F] (4 extensions) | coverage |
| FW-12 | Conditional events are not evaluated on variable change (`evaluateConditionalEvents` needed) | E16c F-1 [F] | correctness |
| FW-13 | External-worker jobs exclusive by default: a parallel MI fetch silently returns 0 and leaves jobs locked | E16a #8, E16c F-4 [F] | correctness |
| FW-14 | Timer re-dating: no recalculate call; reschedule creates a new job id; re-dating a suspended timer is a silent no-op; activate-then-re-date race | E16c area 1, GP-45 [F] | correctness |
| FW-15 | Change-state can only *move*: the running task is re-created with a new id, claims and assignees lost | E16c area 2 [F] | correctness |
| FW-16 | **Slow count under authorization**: 550 ms p95 at 10k tasks × 300 groups (list 86 ms) | E2 [F]; XI.10.1; A-16 | performance |
| FW-17 | Default history AUDIT (no variable-update detail); assignment history without order or operation | E16a #10, E16c N-1 [F] | audit |
| FW-18 | First-class `ACT_RU_ACTINST` grows for long-lived instances (+175 rows over 36 cycles); no activity-instance id on the job | E16a #5; PL-F F8 [F] | performance |
| FW-19 | Process `TaskService` refuses CMMN tasks; CMMN external-worker jobs are not leased | PL-F F7, open items [F] | API |
| FW-20 | **No OSS UI**: Flowable Design proprietary, OSS UI apps gone in 8.x, Eclipse designer stale since 2016; no 8.x Helm chart; no Quarkus | E15 [F] | ecosystem |
| FW-21 | OSS viability: 2 GA in 24 months, no OSS patch release since 8.0.0, CVE unanswered for 4.5 months | E13 [F] | viability |
| FW-22 | Lower throughput: R1 ≈ 23/s vs 35/s; GP-08 decide 49/s vs 87/s; bulk delete 22.4 s vs 9.6 s; GP-09 MI completion slower; cancel 82 s vs 44 s | E9, E16a [F, low confidence] | performance |
| FW-23 | Q1 rollback defect (MyBatis skips rollback, `setAutoCommit(true)` commits product rows); same as D-22 | PL-F F5 [F] | shared |
| FW-24 | Lease times on the engine clock, not the DB clock (A-1); same as Operaton | PL-F open items [F] | shared |
| FW-25 | DST autumn-fold timer runaway (≥ 21 firings); same as Operaton (A-12) | E8 [F] | shared |
| FW-26 | Hot-root OLE storm without a root lock (511/577/704 unrecovered); same as D-24 (A-11) | E6 [F] | shared |
| FW-27 | **Model importer gaps** (LEAP is commercial-only): Activiti namespace ignored, `flowable:type="dmn"` imported as a do-nothing service task, script tasks not reported | E15 [F]; source reading [S] | import |
| FW-28 | **Poison inbound events**: a payload that fails in a non-`IllegalArgumentException` way (null body, wrong node type, Jackson constraint) propagates, so a broker adapter redelivers it for ever | source reading [S] | robustness |
| FW-29 | **Tenant detection**: Flowable's payload tenant detector can pick any tenant; default-tenant `""`/`null` mismatch makes default-tenant events never correlate | E16b GP-37; source reading [S] | tenant isolation |
| FW-30 | **Reserved switches settable by any variable writer**: `_FLOWABLE_SKIP_EXPRESSION_ENABLED` (and the Sangam equivalent) can be set by an event payload or a worker completion, enabling skips of approval steps | `SkipExpressionSupport.java:38-39` [S] | security |
| FW-31 | **Correlation broadcast**: an event without any correlation value matches every waiting subscription of the message | source reading [S] (Flowable event-registry semantics [I]) | correctness |
| FW-32 | **Unique start with a missing reference**: `String.valueOf(null)` claims the literal `"null"`; every later event without a value becomes a silent `DUPLICATE_START` | source reading [S] | correctness |
| FW-33 | **Dynamic injection expression injection**: Flowable copies the injected task spec into model attributes verbatim, so `${…}` in an assignee/name runs with engine privileges | source reading [S] | security |
| FW-34 | **Dynamic injection × compensation**: fragment `activityRef` not prefixed (compensates a host activity or fails), root-level refs not carried, no fragment shape check, unbounded injections per instance | source reading [S] | correctness |
| FW-35 | **Ad-hoc sequential race**: the "one active inner activity" rule is checked outside the start command; two concurrent starts both pass | source reading [S] | concurrency |
| FW-36 | **Exclusive-job rule lost in the split executors**: the filtered acquisition did not keep Operaton's "no second exclusive job of the same instance while one is locked" clause (Flowable's async-executor exclusivity problems, FW-13 family) | source reading [S] | concurrency |
| FW-37 | **Hand-off failure modes** (Flowable async-executor hand-off): a hand-off error after COMMIT surfaced to the caller of an already-committed command; deployment-aware nodes ran foreign jobs | source reading [S] | robustness |
| FW-38 | **Inspect/debugger reachable on non-sandbox data**: step/fire/setVariables/sendMessage took any id | PREM PF-45 ("not for production") [F]; source reading [S] | security |
| FW-39 | Content uploads unbounded | source reading [S] | robustness |
| FW-40 | Content items resolvable by id without a tenant check | source reading [S] | tenant isolation |
| FW-41 | HTTP task / outbound HTTP: SSRF, redirects, unbounded timeouts and response bodies | FC-06 port [I]; source reading [S] | security |
| FW-42 | Event-registry XML and expression deserializers (XML parsing of untrusted payloads, expression evaluation of payloads) | FC-03 [I] | security |
| FW-43 | Early messages not buffered ("matched 0 waiting executions") | E16a #9 [F] | correctness |
| FW-44 | In-engine shell task and JSR-223 script tasks | FC-10, D-46 [F] | security |
| FW-45 | App `.bar`/`.zip` deployment: zip-slip, zip-bomb, nested archives | FC-41 [I] | security |
| FW-46 | Change tenant by direct engine-table update (`ChangeTenantIdBuilder`) | FC-35 [I] | tenant isolation |
| FW-47 | Inbound REST/webhook channels without payload authentication | FC-03 [I] | security |

## 2. Verdict matrix

(a) not applicable to the ports · (b) avoided by design (file:line) · (c) PRESENT → fixed here (§3) or patch request (§4).
Paths are relative to `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/` unless they start with a module name.
Line numbers are after the fixes.

| FW | Verdict | Where / why |
|---|---|---|
| FW-01 | **(b)** + guards | All XML goes through `support/SecureXml.java:39` (disallow-doctype), `:72` `parse`; `ProcessDiagramLayoutFactory` never ported (`SecureXml.java` class doc). Callers: `modelimport/FlowableModelImporter.java:74`, `bpmn/SangamModelValidator.java:90`, `dynamic/DynamicInjectionService.java` (fragment + host model), `dmn/DecisionServiceEvaluator.java:86`, `cmmn/CmmnTaskPatterns.java:68`. Guards added: 10 MiB model limit `:27/:77`, element depth `:48` |
| FW-02 | **(c) fixed** | `support/Json.java:26-41` explicit stream constraints; intake size limit `events/InboundEventProcessor.java:33,126`; webhook pre-HMAC size check `events/channel/WebhookInboundEndpoint.java:45`. Patch request PR-3 (server mappers) |
| FW-03 | **(b)** | `events/EventDispatcher.java:126` uses Operaton's `processInstanceBusinessKey`, which matches the process-instance key at any execution depth (B-24) |
| FW-04 | **(b)** | Operaton completes external tasks and continues in one transaction (B-8). The only ports hand-off is post-commit: `jobs/AsyncJobHandOffInterceptor.java:71` (COMMITTED listener); hardening under FW-37 |
| FW-05 | (a) | The ports have no external-task fetch; Operaton's fetch is engine-side (D-19 tracks its SKIP LOCKED patch) |
| FW-06 | (a) | Native `extendLock` in Operaton; the ports do not lease |
| FW-07 | **(c)** | Ports translation fixed: `worker/FlowableWorkerCompat.java:153` `fencingToken`, `:166` echoed version validated, `UNFENCED` never guessed. **PRESENT in `sangam-server/.../web/FlowableWorkerCompatController.java:184-197`**: when the client does not echo `sangamLockVersion` it uses the *current* lease of the same worker id, which is exactly Flowable's stale-self weakness, and it reads the lease in a separate transaction from the completion (TOCTOU) → PR-1 |
| FW-08 | **(b)** | `jobs/SplitJobExecutors.java:26`: Operaton job-lock expiry is native acquisition; external tasks are not jobs, so the ports' executors never touch leases |
| FW-09 | **(b)** | Ad-hoc completion leaves through `SubProcessActivityBehavior.leave` (`adhoc/AdHocSubProcessActivityBehavior.java:111`), so Operaton's compensation (12/12 cells, B-3) applies; inner activities start by public modification. Injection side: see FW-34 |
| FW-10 | (a) | Operaton native (B-1); the importer passes the construct through |
| FW-11 | (a) | Operaton native (B-1) |
| FW-12 | **(b)** | Importer maps `variableListenerEventDefinition` to `conditionalEventDefinition` (`FlowableModelImporter.java` `mapVariableListener`), which Operaton evaluates on every change (B-2) |
| FW-13 | **(b)** | Operaton external tasks are not jobs (B-7); importer maps `flowable:exclusive` 1:1 |
| FW-14 | (a) | No timer re-dating in the ports (D-32 pause/resume service is WS-ENGINE) |
| FW-15 | **(b)** | `dynamic/CrossInstanceStateChangeService.java:40-49` and `DynamicInjectionService.java` use Operaton modification `startBeforeActivity`, which keeps running task ids (B-4) |
| FW-16 | **(c) fixed** | Read-model counts were already capped (`support/Paging.java:54`, `history/HistoryQueryService.java:240`). PRESENT: engine-history variables/decisions ran an uncapped `count()` → probed paging `HistoryQueryService.java:183,227`, `Paging.java:68`; deep-offset guard `Paging.java:22,31` |
| FW-17 | (a) | Operaton runs FULL history with an ordered identity-link log (B-10); engine config |
| FW-18 | **(b)** | FC-20 read model instead of a runtime table (`HistoryQueryService.openActivities`) |
| FW-19 | (a) | Operaton `TaskService` handles case tasks (B-9) |
| FW-20 | (a) | Product UI (D-42/D-43, R63/R76); no Flowable UI code was ported |
| FW-21 | (a) | Decision record (ADR-D-1); Flowable is not a runtime dependency |
| FW-22 | (a) | Operaton is the engine (E9, B-23) |
| FW-23 | **(b)** | `support/EngineTx.java` runs product SQL inside engine commands only; the explicit rollback listener is WS-ENGINE's (D-22) |
| FW-24 | (a) | Shared engine issue (D-10, A-1), WS-ENGINE |
| FW-25 | (a) | Shared engine issue (A-12, R31), WS-ENGINE |
| FW-26 | (a) | Shared engine issue (D-24, A-11), WS-ENGINE |
| FW-27 | **(c) fixed** | `FlowableModelImporter.java:45` Activiti namespace, `:113` script tasks reported, `:238` `mapDmnTask` (real `businessRuleTask` conversion, UNSUPPORTED without a key); validator warns on Activiti attributes `bpmn/SangamModelValidator.java:56` |
| FW-28 | **(c) fixed** | `InboundEventProcessor.java:100` every payload failure → `REJECTED` with a code; infrastructure errors rethrown `:142`; id/key/tenant/business-key length limits. Kafka/JMS/Rabbit adapters now ack poison records (`events/channel/KafkaAdapters.java:146`) |
| FW-29 | **(c) fixed** | `events/pipeline/InboundEventPipeline.java:154` tenant rule + `:168` allow-list; deploy check `events/EventRegistry.java:359`; engine tenant mapping `EventDispatcher.java:232` (and `processDefinitionWithoutTenantId` / `caseDefinitionWithoutTenantId`) |
| FW-30 | **(c) fixed** | Event payload: `InboundEventPipeline.java:212`, `EventDispatcher.java:101`, deploy-time reject `EventRegistry.java:335`; worker completion: `FlowableWorkerCompat.java:211`; helper `support/PortsProblem.java` `isReservedVariable` |
| FW-31 | **(c) fixed** | `EventDispatcher.java:119` broadcast guard |
| FW-32 | **(c) fixed** | Intake: `InboundEventPipeline.java:125`; dispatch defence: `EventDispatcher.java:154` |
| FW-33 | **(c) fixed** | `DynamicInjectionService.java:97` (spec fields), `:276` `noExpression` |
| FW-34 | **(c) fixed** | `DynamicInjectionService.java:72` `activityRef` prefixed, `:236` `validateFragment` (one none start, an end, no root refs), `:180` per-instance cap 20; suspended instances refused |
| FW-35 | **(c) fixed** | `adhoc/AdHocSubProcessService.java:89` check + start in one command with `forceUpdate` of the process-instance execution; `complete` with a null id resolves a single running ad-hoc `:117` |
| FW-36 | **(c) fixed** | `jobs/FilteredAcquireJobsCmd.java:68` exclusive NOT EXISTS clause (root-instance variant when configured), `:77` |
| FW-37 | **(c) fixed** | `AsyncJobHandOffInterceptor.java:53` skip on deployment-aware engines, `:71` hand-off errors logged, never thrown after COMMIT |
| FW-38 | **(c) fixed** | `debug/DebuggerService.java:134` sandbox-tenant check on step/fire/setVariables/sendMessage/pausedAt, `:66` resume cap 1000 (server gating in `sangam-server/.../config/PortsWiring.java:163` is kept) |
| FW-39 | **(c) fixed** | `content/ContentService.java:57,200` 256 MiB default limit, partial object deleted; PR-4 for the server setting and streaming download |
| FW-40 | **(c) fixed** (ports) / (b) server | `ContentService.java:77` tenant-checked `open`; the server bridge already checks visibility (`PortsDocumentBridge.java:116`); PR-4 to call the tenant overload too |
| FW-41 | **(c) fixed** | Already (b): https only, egress allow-list, no redirects (`events/channel/HttpOutboundAdapter.java:30,32,46`). Fixed: timeout bounded 1–120 s `:45`, response body discarded `:60` |
| FW-42 | **(b)** | JSON only; XML and expression deserializers are not ported (`InboundEventPipeline.java:92`) |
| FW-43 | **(b)** | Inbox buffering before dispatch (`InboundEventProcessor` class doc, D-33) |
| FW-44 | **(b)** | Importer marks `flowable:type="shell"` UNSUPPORTED and now reports `scriptTask` (D-46); WS-ENGINE rejects scripts at deploy |
| FW-45 | **(c) fixed** | Already (b): zip-slip, entry count, uncompressed size (`app/AppBundle.java:31,84`). PRESENT: nested archives were documented as rejected but not checked → `:88`; duplicate and over-long names rejected |
| FW-46 | **(c) fixed** | Already (b): cut-over, no table update (`tenancy/ChangeTenantService.java:27`). Fixed: missing target tenant became the literal tenant `"null"` through the batch handler → `:58` |
| FW-47 | **(b)** | HMAC-SHA256, constant-time (`WebhookInboundEndpoint.java:60`) when `secretRef` is set; the REST route is authenticated (`PortsEventBridge.publish`). Recommendation in PR-3 |

**Counts:** 47 weaknesses. **(a) 13** (FW-05, 06, 10, 11, 14, 17, 19, 20, 21, 22, 24, 25, 26) · **(b) 14** (FW-01, 03, 04, 08, 09, 12, 13, 15, 18, 23, 42, 43, 44, 47) · **(c) 20** (FW-02, 07, 16, 27–41, 45, 46).
Of the 20 (c) items, 19 are fully fixed in `sangam-flowable-ports`; FW-07 is fixed in the ports translation and still
PRESENT in `sangam-server` (PR-1).

## 3. Fixes (in `sangam-flowable-ports`)

| # | FW | File:line | Change |
|---|---|---|---|
| X-1 | all | `support/PortsProblem.java` (new) | `IllegalArgumentException` with a stable `code()` and `status()`; `requireText`, `tooLarge`, `isReservedVariable`. Existing callers still map it to 422 |
| X-2 | FW-02 | `support/Json.java:26-41` | `StreamReadConstraints`: document 16 MiB, nesting 200, string 8 Mi chars, number 400 digits, names 10k; empty input rejected |
| X-3 | FW-01 | `support/SecureXml.java:27,48,72-77` | 10 MiB model limit (`MODEL_TOO_LARGE`), JDK `maxElementDepth` 256, empty/null rejected |
| X-4 | FW-01 | `bpmn/SangamModelValidator.java:56,90` | Activiti attributes warned; null resource guarded; parser problem codes preserved in findings |
| X-5 | FW-27 | `modelimport/FlowableModelImporter.java:45,113,238` | Activiti namespace alias; `scriptTask` → UNSUPPORTED (D-46); `type="dmn"` → `businessRuleTask` + `decisionRef` + `decisionRefBinding=deployment`; guarded `eventType` owner |
| X-6 | FW-02, FW-28 | `events/InboundEventProcessor.java:33-146` | 1 MiB default payload limit (constructor overload), all payload failures → `REJECTED` with code (`Result.code()`), id/key/tenant/business-key ≤ 255 chars, infrastructure errors rethrown |
| X-7 | FW-29, FW-30, FW-32 | `events/pipeline/InboundEventPipeline.java:125,154,168,212,237` | Tenant allow-list (`TENANT_MISMATCH` 403), blank tenant = not detected, reserved fields skipped, unique reference required, int/long overflow rejected, null header keys |
| X-8 | FW-29–32 | `events/EventDispatcher.java:101,119,154,232` | Engine tenant mapping `""`→`null` everywhere (correlation, start, unique start, definition lookup); broadcast guard; no `"null"` unique claim; reserved variables stripped |
| X-9 | FW-29, FW-30 | `events/EventRegistry.java:316-360` | Key pattern `[A-Za-z0-9_.:-]{1,128}`, ≤ 200 fields, TTL ≤ 400 days, reserved payload names rejected, `allowPayloadTenant` needs `allowedTenants` |
| X-10 | FW-02 | `events/channel/WebhookInboundEndpoint.java:41-55` | Empty body / oversized body rejected before the HMAC; null header keys |
| X-11 | FW-41 | `events/channel/HttpOutboundAdapter.java:39-46,60` | Timeout parsed safely and bounded 1–120 s; response body discarded |
| X-12 | FW-07, FW-30 | `worker/FlowableWorkerCompat.java` (whole file) | `fencingToken` (`FENCING_TOKEN_REQUIRED` 409 unless opt-in), echoed version validated, `UNFENCED` constant, workerId/topic required, lockDuration 1 s–24 h, maxTasks 1–100, ≤ 500 variables, reserved names refused, malformed durations/dates → `INVALID_INPUT`, error message ≤ 4000 chars, retries 0–100 |
| X-13 | FW-33, FW-34 | `dynamic/DynamicInjectionService.java:72-76,97,180,236,276` | Expressions refused in injected specs; `activityRef` prefixed; fragment shape and root-reference checks; ≤ 20 injections per instance; suspended instance refused |
| X-14 | FW-35 | `adhoc/AdHocSubProcessService.java:79-128`; `adhoc/CompleteAdHocSubProcessCmd.java` | One-command check-and-start with `forceUpdate`; null-id resolution; null guards |
| X-15 | FW-36 | `jobs/FilteredAcquireJobsCmd.java:62-77` | Exclusive-job NOT EXISTS clause, dialect boolean literals |
| X-16 | FW-37 | `jobs/AsyncJobHandOffInterceptor.java:53,71` | Deployment-aware skip; post-commit hand-off errors contained |
| X-17 | FW-16 | `history/HistoryQueryService.java:183,227,258`; `support/Paging.java:22,31,68` | Probed paging for unbounded engine-history lists; null auth predicate refused; deep-offset guard (`PAGE_TOO_DEEP` beyond row 100 000) |
| X-18 | FW-38 | `debug/DebuggerService.java:66-138` | `NOT_SANDBOX` (403) checks on every action; resume ≤ 1000 steps; sandbox check on breakpoint removal |
| X-19 | FW-39, FW-40 | `content/ContentService.java:55-90,195-212` | 256 MiB default limit (overload), `PAYLOAD_TOO_LARGE`, partial object deleted; `open(tenant, id, version)` |
| X-20 | FW-45 | `app/AppBundle.java:72-95` | Nested archives, duplicates, names > 512 chars and empty bundles rejected |
| X-21 | FW-46 | `tenancy/ChangeTenantService.java:56-60` | Target tenant required (no literal `"null"`) |

Behaviour changes callers should know about: an unfenced Flowable-compat completion now needs an explicit opt-in (once PR-1
lands); `allowPayloadTenant=true` needs `allowedTenants`; payload fields named `_SANGAM_*`/`_FLOWABLE_*` are refused at
deploy; an event of a correlated definition without any correlation value is buffered, not broadcast; history lists without
an instance filter report `capped=true` with a lower-bound total; `HistoryQueryService` refuses a null predicate (the server
bridge always passes one).

## 4. Patch requests (other modules)

| PR | Module / file:line | Request | FW |
|---|---|---|---|
| **PR-1** | `sangam-server/src/main/java/org/sangam/server/web/FlowableWorkerCompatController.java:77-116, 184-197` | Replace `lockVersion(jobId, m)` with `FlowableWorkerCompat.fencingToken(m, allowUnfenced)`, where `allowUnfenced` comes from a new property `sangam.compat.flowable.allow-unfenced` (default **false**). When it is true: (1) also require `sangamAttemptId` and compare it with the lease's attempt id; (2) resolve the lease **in the same transaction** as `work.complete/fail/bpmnError` (today `tx.withConnection` reads it in a separate transaction, a TOCTOU window); (3) audit every unfenced call. Map `PortsProblem` codes (`FENCING_TOKEN_REQUIRED` → 409). Reuse `FlowableWorkerCompat.toFlowableJob` instead of the private copy at `:118-144` (the copy does not convert `Date` values). Replace the private `ObjectMapper` at `:45` / `attemptId(...)` at `:174` with the translated map's `attemptId` field | FW-07, FW-02 |
| **PR-2** | `sangam-server/src/main/java/org/sangam/server/bridge/PortsErrors.java:20-45` | Before the `IllegalArgumentException` branch: `catch (org.sangam.ports.support.PortsProblem p) { throw new ApiProblem(p.status(), p.code(), p.getMessage()); }`. Gives 413 `PAYLOAD_TOO_LARGE`, 403 `TENANT_MISMATCH` / `NOT_SANDBOX`, 409 `FENCING_TOKEN_REQUIRED`, 422 `PAGE_TOO_DEEP` / `EXPRESSION_NOT_ALLOWED` / `FRAGMENT_INVALID` / `LIMIT_EXCEEDED` / `RESERVED_VARIABLE` | all (c) |
| **PR-3** | `sangam-server/src/main/java/org/sangam/server/bridge/PortsEventBridge.java:42, 287-290` | Map a `REJECTED` result by `r.code()` (`TENANT_MISMATCH` → 403, `PAYLOAD_TOO_LARGE` → 413, else 422 `EVENT_REJECTED`). Use `org.sangam.ports.support.Json.MAPPER` (bounded) instead of `new ObjectMapper()`. Recommendation: for channels of transport WEBHOOK that are reachable without a Sangam principal, refuse deploy without `config.secretRef` | FW-02, FW-28, FW-29, FW-47 |
| **PR-4** | `sangam-server/src/main/java/org/sangam/server/bridge/PortsDocumentBridge.java:118-120`; `sangam-server/.../config/PortsWiring.java:327` | Call `content.open(ctx.tenantId(), documentId, v)`; stream the download instead of `in.readAllBytes()` (memory); pass `sangam.content.max-bytes` to the new `ContentService` constructor (default 256 MiB) | FW-39, FW-40 |
| **PR-5** | `sangam-server/.../config/PortsWiring.java:230` | Pass `sangam.ports.events.max-payload-bytes` (default 1 MiB) to the new `InboundEventProcessor` constructor; align the servlet max request size with it | FW-02 |
| **PR-6** | `sangam-server/.../bridge/PortsHistoryBridge.java` (variables/decisions mapping) | Map `PageResult.capped` to the REST "more than N" form, so probed totals are shown as lower bounds, not exact counts | FW-16 |
| **PR-7** | `sangam-integration/.../inbox/InboxService.java` (handler loop for `evt:*`) | A `PortsProblem` (or any `IllegalArgumentException`) thrown by `EventDispatcher.handle` is permanent: park the row (dead letter + reconciliation item) after the first failure instead of retrying until the TTL | FW-28, FW-32 |
| **PR-8** | `sangam-integration/.../inbox/KafkaInboxConsumer.java`, `JmsInboxListener.java` | Verify (testing phase) that a `REJECTED` result from the ports processor is acknowledged and an infrastructure error is not; same contract as the ports' own Kafka/JMS/Rabbit adapters | FW-28 |

## 5. Scenario tests for the testing phase (list only; not run)

| FWT | FW | Scenario | Expected |
|---|---|---|---|
| FWT-001 | 01 | Import / validate / inject a model with `<!DOCTYPE>` + external entity (UTF-8 and UTF-16) | Rejected (`D-51` / `INVALID_INPUT`); no file or network access |
| FWT-002 | 01 | Billion-laughs model without DOCTYPE in the first 4 KiB (comment padding) | Rejected by the parser feature |
| FWT-003 | 01 | 11 MiB BPMN to importer, validator, injection | `MODEL_TOO_LARGE` (413) |
| FWT-004 | 01 | BPMN nested 300 elements deep | Rejected (depth limit) |
| FWT-005 | 02 | Event payload nested 500 levels; 20 MB string; 1000-digit number | `REJECTED`, code `INVALID_INPUT`; no OOM; broker acked |
| FWT-006 | 02 | 2 MiB webhook body | 413 before HMAC (timing shows no hashing) |
| FWT-007 | 03 | Message catch in one branch of a parallel gateway, correlation by business key | Delivered to that instance only |
| FWT-008 | 04 | External completion whose continuation throws | Whole completion rolls back; lease intact (Operaton one-transaction semantics) |
| FWT-009 | 07 | Flowable client completes without `sangamLockVersion` (default settings, after PR-1) | 409 `FENCING_TOKEN_REQUIRED`; nothing changes |
| FWT-010 | 07 | Worker A's lease expires, worker A re-acquires (new version), stale A call with old version | 409 stale lease |
| FWT-011 | 07 | `allow-unfenced=true`, stale call with the right attempt id vs wrong attempt id | Right: accepted; wrong: 409 |
| FWT-012 | 07 | Malformed `lockDuration` / `retryTimeout`; `numberOfTasks=10000`; missing workerId | 422 `INVALID_INPUT`; maxTasks clamped to 100 |
| FWT-013 | 08 | Split executors on, job lock expiry | Jobs re-acquired; external-task leases untouched |
| FWT-014 | 09 | Ad-hoc sub-process with a user-task compensation handler; complete the ad-hoc, then throw compensation | Handler task created; no FK error (PG, Oracle, MSSQL, MySQL) |
| FWT-015 | 09 | Ad-hoc completed with `cancelRemaining=true` while one inner activity runs; then compensate | Only completed inner activities are compensated |
| FWT-016 | 12 | Imported `variableListenerEventDefinition` | Fires on the variable change without an explicit evaluate call |
| FWT-017 | 16 | History variables list without instance filter on 100k rows | No `COUNT(*)` issued; `capped=true`, lower-bound total; p95 within R19 |
| FWT-018 | 16 | page=300, size=500 | 422 `PAGE_TOO_DEEP` |
| FWT-019 | 16 | `HistoryQueryService` with a null predicate | `IllegalArgumentException` |
| FWT-020 | 27 | Import an Activiti-namespace model | Attributes mapped like Flowable ones |
| FWT-021 | 27 | Import `serviceTask flowable:type="dmn"` with field key; without key | With: deploys as `businessRuleTask`, decision evaluated with deployment binding; without: UNSUPPORTED |
| FWT-022 | 27 | Import a model with a `scriptTask` | UNSUPPORTED finding `D-46`; `importable()=false` |
| FWT-023 | 28 | Kafka record with null value (tombstone), array payload, wrong-type field | `REJECTED`, offset committed, next record processed |
| FWT-024 | 28 | Database down during receive | Exception; Kafka seeks back and retries |
| FWT-025 | 28 | Message id of 300 chars | `REJECTED` (`INVALID_INPUT`) |
| FWT-026 | 29 | Channel in tenant A, payload names tenant B, `allowPayloadTenant=false` | `TENANT_MISMATCH` |
| FWT-027 | 29 | `allowPayloadTenant=true` without `allowedTenants` | Channel deploy refused |
| FWT-028 | 29 | `allowedTenants=B,C`, payload names D | `TENANT_MISMATCH`; payload names B → delivered in B only |
| FWT-029 | 29 | Default-tenant (`""`) channel, process deployed without tenant | Correlation and message start work |
| FWT-030 | 30 | Event definition with payload field `_SANGAM_SKIP_EXPRESSION_ENABLED` | Deploy refused |
| FWT-031 | 30 | Flowable worker completes with variable `_FLOWABLE_SKIP_EXPRESSION_ENABLED=true` | 422 `RESERVED_VARIABLE`; no skip downstream |
| FWT-032 | 31 | Event of a correlated definition without correlation values and business key, 50 waiting instances | 0 correlated; event stays BUFFERED until TTL |
| FWT-033 | 32 | Unique-start event without the reference value | `REJECTED` at intake; no `"null"` row in `UNIQUE_START` |
| FWT-034 | 33 | Inject user task with assignee `${execution.setVariable('x',1)}` | 422 `EXPRESSION_NOT_ALLOWED`; nothing deployed |
| FWT-035 | 34 | Inject a fragment whose compensation throw has `activityRef` to its own task; host has a task with the same id | Only the fragment task is compensated |
| FWT-036 | 34 | Fragment with two none start events / no end / a `messageRef` | 422 `FRAGMENT_INVALID` |
| FWT-037 | 34 | 21st injection into one instance; injection into a suspended instance | `LIMIT_EXCEEDED`; `INVALID_STATE` |
| FWT-038 | 34 | Injection into an instance with an event-scope execution (completed sub-process with compensation subscription), then compensate | Migration succeeds or rolls back cleanly; compensation still runs |
| FWT-039 | 35 | Two threads start different inner activities of a sequential ad-hoc at once | Exactly one starts; the other gets an optimistic-locking conflict; retry sees "busy" |
| FWT-040 | 36 | Split executors, 2 nodes, one instance with 10 exclusive async jobs and a timer | Never two exclusive jobs of the instance locked at once; no OLE storm |
| FWT-041 | 37 | Hand-off with a saturated pool; deployment-aware engine | Command succeeds; jobs run after lock expiry; no hand-off on deployment-aware |
| FWT-042 | 38 | Debugger `step` / `fireTimer` / `setVariables` / `sendMessage` on a non-sandbox tenant id | 403 `NOT_SANDBOX`; job untouched |
| FWT-043 | 38 | `resume(pi, 1_000_000)` on a looping model | Stops at 1000 steps |
| FWT-044 | 39 | Upload 300 MiB with default limit | 413; no object left in the store |
| FWT-045 | 40 | `open(tenantB, itemOfTenantA)` | "not found" |
| FWT-046 | 41 | Outbound channel `timeoutMs=0`, `timeoutMs=abc`, endpoint returning 1 GB body or a 302 | Timeout 1 s; deploy error; body discarded; redirect not followed |
| FWT-047 | 42 | Channel with `deserializerType=xml` | Refused |
| FWT-048 | 43 | Event before its subscription exists | Buffered, delivered when the instance reaches the catch |
| FWT-049 | 45 | Bundle with `../x.bpmn`, nested `.zip`, duplicate entry, 600 entries, 70 MB uncompressed | Each refused |
| FWT-050 | 46 | Change-tenant batch item without `targetTenant` | `IllegalArgumentException`; no deployment in tenant "null" |
| FWT-051 | 47 | Webhook with `secretRef`: missing / wrong / right signature | 403 / 403 / accepted |
| FWT-052 | 02, 28 | Soak: 10k mixed valid/poison events across Kafka, JMS, webhook | No stuck partition, no heap growth, EVT_LOG has one REJECTED per poison |
| FWT-053 | 13 | Import a Flowable model with a parallel MI `external-worker` task (no `exclusive` attribute); fetch 10 | All MI items fetched (Operaton external tasks are not exclusive jobs) |
| FWT-054 | 15 | Claimed user task keeps running while a cross-instance move / injection starts another activity | Running task keeps its id, assignee and claim |
| FWT-055 | 18 | Instance with 36 timer cycles; open-activities query on the read model | Open-activity rows stay flat; no runtime table growth |
| FWT-056 | 23 | Product row written through `EngineTx.connection()`, then the engine command fails (with and without engine writes) | Product row rolled back in both cases |
| FWT-057 | 44 | Import `flowable:type="shell"` and a `scriptTask`; deploy a script task directly | Importer: UNSUPPORTED for both; deploy rejected (D-46) |

## 6. Limits

- The ports have no unit tests in the module; every fix is compile-verified only. FWT-001…057 are the acceptance set.
- FW coverage map (verdict, file:line, FWT ids per FW): `sangam/sangam-flowable-ports/README.md`, section "FW. Flowable weaknesses".
- FW-27…FW-47 are found by source reading [S]; Flowable's own behaviour for FW-29/31/33/39/40/42/45/47 is a judgement [I]
  from its API shape, not reproduced on Flowable 8.0.0.
- `sangam-server`, `sangam-integration` and `sangam-rest` were read, not built; the ports' public signatures used there are
  unchanged (only overloads and new types were added), so no compile break is expected.
