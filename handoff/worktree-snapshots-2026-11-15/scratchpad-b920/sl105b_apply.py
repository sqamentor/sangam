"""SL-105 follow-up: B-26 class name, I-18 pins (findTasksByQueryCriteria, CommandContext check flags), allow-list A/B regenerated, B with reasons."""
import pathlib
import re
import sys

ROOT = pathlib.Path(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam")
E = ROOT / "sangam-engine"
IMPL = re.compile(r"org\.operaton\.bpm\.engine\.impl\.([A-Za-z0-9_.]*[A-Za-z0-9_*])")
CONFIRM = "reason to confirm"

REASONS = {
    "sangam-case/src/main/java/org/sangam/casefile/cmmn/CaseFileCmmnListener.java": "implements `CmmnTransformListener` (CMMN transform SPI), reads `CmmnActivity` at transform time",
    "sangam-case/src/main/java/org/sangam/casefile/cmmn/CaseFileEnginePlugin.java": "`ProcessEnginePlugin` that adds its `CmmnTransformListener` in `preInit` (`getCustomPostCmmnTransformListeners`)",
    "sangam-case/src/main/java/org/sangam/casefile/delegate/DelegateSupport.java": "reads the caller's `identity.Authentication` (`IdentityService.getCurrentAuthentication`) in a delegate",
    "sangam-cell/src/main/java/org/sangam/cell/id/CellIdGeneratorPlugin.java": "`AbstractProcessEnginePlugin` that installs the cell id generator in `preInit`",
    "sangam-cell/src/main/java/org/sangam/cell/id/OperatonCellIdGenerator.java": "implements `cfg.IdGenerator`: `<cellId>.<uuidv7>` ids instead of `StrongUuidGenerator`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/SangamPortsPlugin.java": "`ProcessEnginePlugin` that registers the ports' BPMN parse listeners, CMMN transform listeners, job handlers, history handlers and deployers in `preInit`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/AdHocSubProcessActivityBehavior.java": "extends `bpmn.behavior.SubProcessActivityBehavior` (ad-hoc sub-process) with `ActivityExecution` / `PvmExecutionImpl`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/AdHocSubProcessService.java": "reads the definition's PVM (`PvmActivity`) and the runtime `ExecutionEntity` (`ExecutionManager.findExecutionById`) to list and enable ad-hoc activities",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/AdHocSupport.java": "PVM navigation (`PvmActivity`, `PvmExecutionImpl`, `ActivityImpl.getParentFlowScopeActivity`) to find the enclosing ad-hoc activity",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/CompleteAdHocSubProcessCmd.java": "own `interceptor.Command`; finds the scope `ExecutionEntity` through `CommandContext.getExecutionManager`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/SangamBpmnParse.java": "subclass of `bpmn.parser.BpmnParse` overriding `parseActivity` for an element Operaton ignores (the module's own patch P-1, per its javadoc)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/adhoc/SangamBpmnParseFactory.java": "implements `BpmnParseFactory` to install `SangamBpmnParse`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/batch/StandardBatchHandlers.java": "casts to `MessageCorrelationBuilderImpl` to call `executionsOnly()` (no public API)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/BpmnExtensionsParseListener.java": "`BpmnParseListener` (parse SPI) with `util.xml.Element`/`Namespace`, `ActivityImpl`, `UserTaskActivityBehavior`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SangamBpmn.java": "`util.xml.Namespace`/`Element` attribute helpers; creates expressions with the engine's `ExpressionManager` (`Context.getProcessEngineConfiguration`)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SangamModelValidator.java": "a `persistence.deploy.Deployer` that validates the deployment's `ResourceEntity`s",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/SkipExpressionSupport.java": "`UserTaskActivityBehavior` subclass (skip expression) with `ActivityExecution` and `el.Expression`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/TerminateExtendedBehavior.java": "extends `bpmn.behavior.FlowNodeActivityBehavior`; PVM (`PvmExecutionImpl`, `ActivityImpl`) to terminate a multi-instance body or the process",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/TriggerableServiceTaskBehavior.java": "extends `bpmn.behavior.TaskActivityBehavior` (wait for a trigger, then `signal`); `util.ReflectUtil` instantiates the delegate class",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/bpmn/VariableChangeConditionalEventBehavior.java": "extends `bpmn.behavior.IntermediateConditionalEventBehavior`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/CmmnEventListenerService.java": "in an engine command, `CommandContext.getCaseExecutionManager().findCaseExecutionById` (`CaseExecutionEntity`) to fire a CMMN event listener",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/CmmnTimerJobHandler.java": "implements `jobexecutor.JobHandler` / `JobHandlerConfiguration` for CMMN timers; loads the `CaseExecutionEntity` through the `CommandContext`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/PortsCmmnTransformListener.java": "implements `CmmnTransformListener` (CMMN transform SPI) with `CmmnActivity`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/SangamCmmnElementHandlerRegistry.java": "extends `cmmn.handler.DefaultCmmnElementHandlerRegistry` / `EventListenerItemHandler` (Operaton does not support CMMN event listeners)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/cmmn/SangamEventListenerActivityBehavior.java": "extends `cmmn.behavior.EventListenerActivityBehavior`; evaluates with the engine's `ExpressionManager` and uses the business-calendar names (`CycleBusinessCalendar`, `DurationBusinessCalendar`, `DueDateBusinessCalendar`)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/debug/DebuggerService.java": "`BpmnParseListener` (parse SPI) that marks breakpoints on `ActivityImpl`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/events/EventRegistryParseListener.java": "implements `BpmnParseListener` (parse SPI) for the event registry",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/events/SendEventDelegate.java": "`ExecutionEntity#getRootProcessInstanceId` (the root id has no public API on `DelegateExecution`)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/history/HistoryEventMapper.java": "maps `history.event` entity types (`HistoricProcessInstanceEventEntity`, ...) to event kinds",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/history/PortsHistoryEventHandler.java": "implements `history.handler.HistoryEventHandler`, buffering per `CommandContext`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/jobs/AsyncJobHandOffInterceptor.java": "extends `interceptor.CommandInterceptor`; hands jobs to the `JobExecutor` of the `ProcessEngineImpl`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/jobs/FilteredAcquireJobsCmd.java": "extends `cmd.AcquireJobsCmd` to acquire one job class (timers or non-timers), reusing its locking",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/jobs/SplitJobExecutors.java": "installs two `jobexecutor.DefaultJobExecutor`s (async and timers) through `ProcessEngineConfigurationImpl`",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/support/EngineTx.java": "`Context.getCommandContext`, `cfg.standalone.StandaloneTransactionContext` and a `TransactionState.ROLLINGBACK` listener: product work on the engine connection rolls back with the command",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/task/TaskEventDispatcher.java": "`persistence.entity.TaskEntity#getPropertyChanges` / `PropertyChange`: which task properties changed",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/tenancy/TenantEngineRegistry.java": "builds per-tenant engines from `ProcessEngineConfigurationImpl` and registers them on one shared `DefaultJobExecutor` (`ProcessEngineImpl`)",
    "sangam-flowable-ports/src/main/java/org/sangam/ports/variables/MultiInstanceAggregation.java": "implements `BpmnParseListener` (parse SPI) for multi-instance aggregation",
    "sangam-geo/src/main/java/org/sangam/geo/feel/GeoEnginePlugin.java": "`AbstractProcessEnginePlugin` that adds a `FeelCustomFunctionProvider` (`setDmnFeelCustomFunctionProviders`) in `preInit`",
    "sangam-geo/src/main/java/org/sangam/geo/feel/GeoTenant.java": "`Context.getCoreExecutionContext` / `CommandContext.getAuthentication` (`identity.Authentication`) to find the current tenant",
    "sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/EngineContext.java": "engine JDBC connection (`CommandContext#getDbSqlSession`, Q1) and `ExecutionEntity#getRootProcessInstanceId`",
    "sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/OutboxHistoryEventHandler.java": "implements `history.handler.HistoryEventHandler` over the `history.event` entity types",
    "sangam-integration/src/main/java/org/sangam/integration/bridge/operaton/SangamIntegrationEnginePlugin.java": "`ProcessEnginePlugin` that adds its history event handler in `preInit`",
    "sangam-integration/src/main/java/org/sangam/integration/packs/IntegrationConnectorGateway.java": "engine JDBC connection of the current command (`CommandContext#getDbSqlSession`) when called from a delegate",
    "sangam-integration/src/main/java/org/sangam/integration/pipeline/PipelineDelegate.java": "engine JDBC connection of the current command (`CommandContext#getDbSqlSession`)",
    "sangam-ops/src/main/java/org/sangam/ops/listener/GlobalTaskListeners.java": "`BpmnParseListener` that attaches task listeners to `UserTaskActivityBehavior` task definitions (`ProcessDefinitionEntity` key)",
    "sangam-packs/src/main/java/org/sangam/packs/core/Vars.java": "`ExecutionEntity#getProcessInstance` of a delegate execution",
    "sangam-registry/src/main/java/org/sangam/registry/bpmn/DelegateSupport.java": "engine JDBC connection of the current command (`CommandContext#getDbSqlSession`)",
    "sangam-resources/src/main/java/org/sangam/resources/bpmn/ResourcesRuntime.java": "engine JDBC connection of the current command (`CommandContext#getDbSqlSession`)",
    "sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonDebugAdapter.java": "`JobEntity#getActivityId` of a job (not on the public `Job`)",
    "sangam-rest/src/main/java/org/sangam/rest/adapter/operaton/OperatonOpsAdapter.java": "`JobEntity#getJobHandlerType` of a job (not on the public `Job`)",
    "sangam-rest/src/main/java/org/sangam/rest/config/SangamRestAutoConfiguration.java": "`ProcessEngineConfigurationImpl#getJobExecutor().isActive()` for the ops console",
    "sangam-security/src/main/java/org/sangam/security/assignment/EngineConnections.java": "engine JDBC connection of the current command (`CommandContext#getDbSqlSession`)",
    "sangam-security/src/main/java/org/sangam/security/assignment/SangamSecurityEnginePlugin.java": "`AbstractProcessEnginePlugin` that adds its post BPMN parse listener in `preInit`",
    "sangam-security/src/main/java/org/sangam/security/assignment/SeatAssignmentParseListener.java": "`AbstractBpmnParseListener` reading the user task's `TaskDefinition` and the `ProcessDefinitionEntity` (seat assignment at parse time)",
    "sangam-security/src/main/java/org/sangam/security/tenant/SangamTenantIdProvider.java": "implements `cfg.multitenancy.TenantIdProvider`: the tenant of new process, case and decision instances",
    "sangam-server/src/main/java/org/sangam/server/bridge/AiBindings.java": "keeps the current `identity.Authentication`, starts a process as the agent identity of the tenant, and restores it",
    "sangam-server/src/main/java/org/sangam/server/bridge/CompliancePurposePlugin.java": "`AbstractProcessEnginePlugin` with an `AbstractBpmnParseListener` (`parseProcess`, `ProcessDefinitionEntity`); engine JDBC connection (`CommandContext#getDbSqlSession`)",
    "sangam-server/src/main/java/org/sangam/server/bridge/ConfigCaseBindingPlugin.java": "`AbstractProcessEnginePlugin` with an `AbstractBpmnParseListener` (`parseProcess`); `ExecutionEntity#getSuperExecution`",
    "sangam-server/src/main/java/org/sangam/server/bridge/EngineConfigurationCustomizer.java": "post-processes the starter's `ProcessEngineConfigurationImpl` bean: plugin order (`ProcessEnginePlugin`)",
    "sangam-server/src/main/java/org/sangam/server/bridge/OpsTaskListenerPlugin.java": "`AbstractProcessEnginePlugin` that adds the ops task-listener parse listener in `preInit`",
    "sangam-server/src/main/java/org/sangam/server/bridge/PortsDebugBridge.java": "`JobEntity#getActivityId` of a job (not on the public `Job`)",
    "sangam-server/src/main/java/org/sangam/server/bridge/TenantQuotaEnginePlugin.java": "`ProcessEnginePlugin` adding a post `CommandInterceptor` that takes a quota unit for process and case starts",
    "sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java": "`cfg.ProcessEnginePlugin` as the type of the ports plugin bean",
    "sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java": "`ProcessEngineConfigurationImpl#getAdminGroups`",
    "sangam-server/src/main/java/org/sangam/server/plugins/PluginFeelEnginePlugin.java": "`AbstractProcessEnginePlugin` that adds a `FeelCustomFunctionProvider` (`setDmnFeelCustomFunctionProviders`) in `preInit`",
    "sangam-server/src/main/java/org/sangam/server/security/EngineRestTenantFilter.java": "sets the engine `identity.Authentication` to the token's tenant for `/engine-rest` (keeping the previous one)",
}


def load(p):
    return p.read_bytes().decode("utf-8").replace("\r\n", "\n")


def save(p, t):
    p.write_bytes(t.replace("\n", "\r\n").encode("utf-8"))


def sub(t, old, new, name):
    if t.count(old) != 1:
        sys.exit(f"{name}: anchor found {t.count(old)} times")
    return t.replace(old, new)


rows = {}
for p in ROOT.rglob("*.java"):
    r = p.relative_to(ROOT).as_posix()
    if "/src/main/" not in r or any(s.startswith("target") or s in ("node_modules", ".git") for s in r.split("/")):
        continue
    types = sorted(set(IMPL.findall(p.read_text(encoding="utf-8"))))
    if types:
        rows[r] = types
eng = {k: v for k, v in rows.items() if k.startswith("sangam-engine/")}
out = {k: v for k, v in rows.items() if not k.startswith("sangam-engine/")}
missing = sorted(set(out) - set(REASONS))
extra = sorted(set(REASONS) - set(out))
if extra:
    sys.exit(f"reasons for files that no longer use impl: {extra}")
tA = ["| File | `impl` types used |", "|---|---|"] + [
    "| `%s` | %s |" % (k, ", ".join("`" + x + "`" for x in eng[k])) for k in sorted(eng)]
tB = ["| File | `impl` types used | Reason |", "|---|---|---|"] + [
    "| `%s` | %s | %s |" % (k, ", ".join("`" + x + "`" for x in out[k]), REASONS.get(k, CONFIRM)) for k in sorted(out)]

p = E / "PATCHES.md"
t = load(p)
a0 = t.index("### Allow-list A:")
a1 = t.index("\n", a0) + 1
b0 = t.index("### Allow-list B:")
b1 = t.index("\n", b0) + 1
c0 = t.index("### Allow-list C:")
newA = t[a0:a1] + "\n" + "\n".join(tA) + "\n\n"
newB = (t[b0:b1] + "\n"
        "Recorded from the SL-105 audit (2026-11-12): each row names the `impl` types and what the file uses them for, read "
        "from the code; a row marked \"" + CONFIRM + "\" is routed to the module owner. An Operaton upgrade re-checks these "
        "files with the engine's.\n\n" + "\n".join(tB) + "\n\n")
t = t[:a0] + newA + newB + t[c0:]
t = sub(t, "are merged into one TaskManager subclass by B3 "
           "and the security agent (class name to be recorded here when merged); the guard allows exactly one | ",
        "are merged into **one** TaskManager subclass, `org.sangam.engine.deploy.TenantScopedCreation$Tasks` (B3 + "
        "security, 2026-11-12): it overrides `insertTask` (SL-73) and all five `updateTaskSuspensionStateBy*` methods "
        "(by process instance, definition id, definition key, definition key and tenant, case execution; SG-03(c)): each "
        "reads the tasks of the scope in the opposite state with `findTasksByQueryCriteria` (authorization and tenant "
        "checks switched off and restored on the `CommandContext`), runs the UPDATE and reports exactly those tasks to "
        "`TaskSuspensionEvents`; the guard allows exactly one TaskManager replacement | ", "B-26 class")
t = sub(t, "`ResourceEntity`, `TaskEntity`, `SuspensionState`, `identity.Authentication`, `TenantManager#isTenantCheckEnabled`, ",
        "`ResourceEntity`, `TaskEntity`, `SuspensionState`, `TaskQueryImpl` (no-argument constructor), "
        "`TaskManager#findTasksByQueryCriteria(TaskQueryImpl)`, `CommandContext#is/set{Authorization,Tenant}CheckEnabled` "
        "and `disable{Authorization,Tenant}Check`, `identity.Authentication`, `TenantManager#isTenantCheckEnabled`, ", "I-18 types")
t = sub(t, "the Operaton `TaskManager` methods Sangam overrides (`insertTask`, the five `updateTaskSuspensionStateBy*`) keep their signatures |",
        "the Operaton `TaskManager` methods Sangam overrides (`insertTask`, the five `updateTaskSuspensionStateBy*`) and "
        "the ones its overrides call (`findTasksByQueryCriteria(TaskQueryImpl)`, `TaskQueryImpl()`, the `CommandContext` "
        "authorization/tenant-check getters and setters, which really switch the checks) keep their signatures |", "pin row")
save(p, t)

p = E / "src/test/java/org/sangam/engine/EngineInternalsPinTest.java"
t = load(p)
t = sub(t, '''        TaskManager.class.getMethod("updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId", String.class, String.class,
                SuspensionState.class);
    }
''', '''        TaskManager.class.getMethod("updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId", String.class, String.class,
                SuspensionState.class);
        // what the SG-03(c) overrides call: the query by criteria and the command's check switches
        TaskManager.class.getMethod("findTasksByQueryCriteria", org.operaton.bpm.engine.impl.TaskQueryImpl.class);
        org.operaton.bpm.engine.impl.TaskQueryImpl.class.getConstructor();
        Class<?> cc = org.operaton.bpm.engine.impl.interceptor.CommandContext.class;
        for (String m : List.of("isAuthorizationCheckEnabled", "isTenantCheckEnabled", "disableAuthorizationCheck",
                "disableTenantCheck")) {
            cc.getMethod(m);
        }
        cc.getMethod("setAuthorizationCheckEnabled", boolean.class);
        cc.getMethod("setTenantCheckEnabled", boolean.class);
        withEngine(a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            List<Boolean> flags = cfg.getCommandExecutorTxRequired().execute(c -> {
                boolean auth = c.isAuthorizationCheckEnabled();
                boolean tenant = c.isTenantCheckEnabled();
                c.disableAuthorizationCheck();
                c.disableTenantCheck();
                boolean offAuth = c.isAuthorizationCheckEnabled();
                boolean offTenant = c.isTenantCheckEnabled();
                c.setAuthorizationCheckEnabled(auth);
                c.setTenantCheckEnabled(tenant);
                return List.of(offAuth, offTenant, c.isAuthorizationCheckEnabled() == auth,
                        c.isTenantCheckEnabled() == tenant);
            });
            assertEquals(List.of(false, false, true, true), flags,
                    "I-18: disable* switches the command's checks off and set* restores them");
        });
    }
''', "pin flags")
save(p, t)
print("A", len(eng), "B", len(out), "confirm", missing)
