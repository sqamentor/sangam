# sangam-flowable-ports: engine behaviour changes (patch ledger)

Base: `org.operaton.bpm:operaton-engine:2.2.0` from the BOM, used **unchanged as a dependency** (ADR-D-16). WS-ENGINE's
ledger is `../sangam-engine/PATCHES.md` (P-1/P-2 there are MySQL schema patches). The ids below are this module's own
(prefix them with `PORTS-` when citing across modules).

## Summary

| Kind | Count |
|---|---|
| Engine source or bytecode patches | **0** |
| "Patch route" items done as subclass/replacement of an Operaton impl class, installed through a public setter | **3** (P-1 … P-3) |
| Patches designed but not implemented (stage D/E, upstream first) | **2** (P-4, P-5) |
| Reflection | 0 |

## Patch-route items (implemented)

| ID | What | Mechanism | Why no plugin route | Re-check on upgrade | Upstream offer |
|---|---|---|---|---|---|
| **P-1** | BPMN ad-hoc sub-process (FC-01, D-09): parse `adHocSubProcess` (ordering, `completionCondition`, `cancelRemainingInstances`, MI, I/O mapping) into a PVM scope with `AdHocSubProcessActivityBehavior`; runtime API (enabled activities, execute activity, complete) | `adhoc/SangamBpmnParse` extends `impl.bpmn.parser.BpmnParse`, installed with `ProcessEngineConfigurationImpl.setBpmnParseFactory(new SangamBpmnParseFactory())`; overrides `parseActivity` (only for the ad-hoc tag) and `checkActivityInputOutputSupported` | Operaton 2.2.0 `BpmnParse.parseActivity` drops the element with "Ignoring unsupported activity type"; sequence flows to it then fail with ENGINE-09005. No `BpmnParseListener` callback exists for an element that never becomes an activity | `parseActivity`, `parseMultiInstanceLoopCharacteristics`, `parseAsynchronousContinuationForActivity`, `parseActivityInputOutput`, `createActivityOnScope`, `parseScope`, `PROPERTYNAME_*` constants still protected/public; `SubProcessActivityBehavior` still `CompositeActivityBehavior`; `PvmExecutionImpl.interrupt/setActivityInstanceId` | Native `adHocSubProcess` parse branch + behaviour + `RuntimeService.getEnabledActivitiesFromAdhocSubProcess/executeActivityInAdhocSubProcess/completeAdhocSubProcess` |
| **P-2** | CMMN event listeners (FC-55, D-08): timer, user and generic listeners are transformed and work (`created` schedules a case timer job; occur API) | `cmmn/SangamCmmnElementHandlerRegistry` extends `impl.cmmn.handler.DefaultCmmnElementHandlerRegistry`; set in `postInit` on `CmmnDeployer.getTransformer().setCmmnElementHandlerRegistry(..)`; behaviour `SangamEventListenerActivityBehavior` extends `EventListenerActivityBehavior`; job handler `CmmnTimerJobHandler` (timer job without execution, configuration = case execution id) | The registry has no public registration API; `EventListenerActivityBehavior.created` is a TODO | registry field `planItemElementHandlers`, `CmmnTransform` dispatch for `EventListener`, `CaseExecutionEntity.occur/isAvailable`, `JobManager.schedule(TimerEntity)` with null execution | Register `EventListenerItemHandler`, implement `created()` for timer listeners |
| **P-3** | Separate timer/async acquisition, global acquire lock, post-commit hand-off (FC-24) | `jobs/FilteredAcquireJobsCmd` extends `impl.cmd.AcquireJobsCmd` (reuses `lockJob` and its OLE listener; own SQL on `ACT_RU_JOB`), set with `JobExecutor.setAcquireJobsCmdFactory`; second `DefaultJobExecutor` for timers; `jobs/AsyncJobHandOffInterceptor` (post command interceptor reading the entity cache) | Operaton's acquisition query has no job-type filter and no hand-off | `ACT_RU_JOB` columns `TYPE_, DUEDATE_, LOCK_OWNER_, LOCK_EXP_TIME_, RETRIES_, SUSPENSION_STATE_, PRIORITY_`; `AcquireJobsCmd` fields `acquiredJobs/numJobsToAcquire`; `DbEntityCache.getEntitiesByType/isTransient`; `JobExecutor.executeJobs(List, ProcessEngineImpl)` | Job-type filtered acquisition and post-commit hand-off in the job executor |

## Designed, not implemented

| ID | What | Why not now | Stage |
|---|---|---|---|
| **P-4** | CMMN plan-item state machine extensions (FC-56: async-active, wait_repetition, unavailable, available condition, completion-neutral, parent-completion rule, exit types, sentry `onEvent`) and repetition with collections / max instance count (FC-57) | Deep change to Operaton's hard-wired CMMN 1.1 state machine (XL); gap list: low feasibility, on proven demand only (R45). Lifecycle *listeners* (the feasible part of FC-56) are implemented in `cmmn/PortsCmmnTransformListener` | E |
| **P-5** | Native CMMN case-execution external tasks (FC-11 native) and native CMMN migration (FC-19 native) | External-task entity is process-only; Operaton has no CMMN migration model. Pattern/run-off routes are implemented (`CmmnTaskPatterns`, `CaseLifecycleService`) | E |

The per-engine clock SPI needed before several engines share one JVM with a moved clock (D-10, FC-34) is WS-ENGINE's
(their "Stage D patch"); `tenancy/TenantEngineRegistry` contains the limit (see README).

## Internal (`impl`) classes used (besides P-1 … P-3)

| ID | Class / contract | Where | Why |
|---|---|---|---|
| I-1 | `pvm.runtime.PvmExecutionImpl` (`interrupt`, `deleteCascade`, `setActivity`, `setActivityInstanceId`, `getParentActivityInstanceId`, `findExecutionForFlowScope`) | `bpmn/TerminateExtendedBehavior`, `adhoc/*` | FC-22, FC-01 |
| I-2 | MI body id convention `<activityId>#multiInstanceBody` | `variables/MultiInstanceAggregation`, `bpmn/TerminateExtendedBehavior` | FC-21, FC-22 |
| I-3 | `persistence.entity.TaskEntity.getPropertyChanges()` / `PropertyChange` | `task/TaskEventDispatcher` | fine-grained task events |
| I-4 | `bpmn.behavior.UserTaskActivityBehavior(TaskDecorator)`, `TaskActivityBehavior`, `BpmnActivityBehavior.performDefaultOutgoingBehavior` | `bpmn/SkipExpressionSupport`, `bpmn/TriggerableServiceTaskBehavior` | FC-13, FC-12 |
| I-5 | `bpmn.parser.EventSubscriptionDeclaration.getDeclarationsForScope` | `events/EventRegistryParseListener` | message name = event key check |
| I-6 | DMN `impl.spi.hitpolicy.*`, `impl.transform.DefaultDmnTransformer`, `DmnDecisionTableEvaluationEventImpl.setMatchingRules` | `dmn/PriorityHitPolicies` | FC-54 |
| I-7 | (removed) `ClockUtil` is no longer used; engine time comes from core `SangamClockHolder.now()` | `jobs/*`, `tenancy/TenantEngineRegistry` | integrator decision 2026-11 |
| I-8 | `RepositoryServiceImpl.getDeployedProcessDefinition`, `ReadOnlyProcessDefinition`, `PvmActivity` | `adhoc/AdHocSubProcessService` | enabled-activity query |

## Upgrade checklist (every Operaton minor)

1. P-1: diff `BpmnParse.parseActivity` and `parseSubProcess`; deploy the ad-hoc test model.
2. P-2: diff `DefaultCmmnElementHandlerRegistry` (Operaton may register event listeners itself → drop P-2) and `EventListenerActivityBehavior`.
3. P-3: diff `AcquireJobsCmd` and the `ACT_RU_JOB` DDL.
4. I-1/I-2/I-3: re-run the FC-01/21/22 and task-event tests.
