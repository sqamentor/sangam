"""SL-105 targeted edits (CRLF kept): EngineInternalsPinTest pins I-18/I-19 and the PATCHES.md ledger entries."""
import pathlib
import sys

E = pathlib.Path(r"D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-engine")
S = pathlib.Path(r"C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad")


def load(p):
    return p.read_bytes().decode("utf-8").replace("\r\n", "\n")


def save(p, t):
    p.write_bytes(t.replace("\n", "\r\n").encode("utf-8"))


def sub(t, old, new, name):
    if t.count(old) != 1:
        sys.exit(f"{name}: anchor found {t.count(old)} times: {old[:80]!r}")
    return t.replace(old, new)


# ---------------------------------------------------------------- EngineInternalsPinTest
p = E / "src/test/java/org/sangam/engine/EngineInternalsPinTest.java"
t = load(p)
t = sub(t, "import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;\n",
        "import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;\n"
        "import org.operaton.bpm.engine.OptimisticLockingException;\n"
        "import org.operaton.bpm.engine.delegate.DelegateExecution;\n"
        "import org.operaton.bpm.engine.delegate.JavaDelegate;\n"
        "import org.operaton.bpm.engine.impl.interceptor.SessionFactory;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.BatchManager;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.DeploymentManager;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.ResourceManager;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.SuspensionState;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;\n"
        "import org.operaton.bpm.engine.impl.persistence.entity.TaskManager;\n"
        "import org.operaton.bpm.engine.runtime.Job;\n", "pin imports")
t = sub(t, "API or extension point (PATCHES.md I-7 … I-10, I-17, upgrade checklist items 2, 3 and 6).",
        "API or extension point (PATCHES.md I-7 … I-10, I-17, I-18, I-19, upgrade checklist items 2, 3, 6 and 7).",
        "pin javadoc")
PINS = '''
    /**
     * I-18 (B-26): {@code ProcessEngineConfigurationImpl.initSessionFactories} adds the {@code customSessionFactories}
     * after the built-in ones into a map keyed by session type ({@code addSessionFactory}: last one wins), and
     * {@code CommandContext#getSession} resolves a manager by that type. Pinned: the custom factories Sangam registers
     * are the engine's factories for the batch, deployment, resource and task managers, every command gets Sangam's
     * managers, and the Operaton TaskManager methods the Sangam TaskManager overrides still exist with these signatures.
     */
    @Test
    void i18_customSessionFactoriesReplaceTheBuiltInManagers() throws Exception {
        List<Class<?>> types = List.of(BatchManager.class, DeploymentManager.class, ResourceManager.class, TaskManager.class);
        withEngine(a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            for (Class<?> type : types) {
                SessionFactory used = cfg.getSessionFactories().get(type);
                assertTrue(cfg.getCustomSessionFactories().contains(used),
                        "I-18: the registered custom factory is the engine's factory for " + type.getSimpleName());
            }
            List<Object> managers = cfg.getCommandExecutorTxRequired().execute(cc -> List.<Object>of(cc.getBatchManager(),
                    cc.getDeploymentManager(), cc.getResourceManager(), cc.getTaskManager()));
            for (int i = 0; i < types.size(); i++) {
                Class<?> c = managers.get(i).getClass();
                assertTrue(types.get(i).isAssignableFrom(c) && c != types.get(i) && c.getName().startsWith("org.sangam."),
                        "I-18: commands use Sangam's " + types.get(i).getSimpleName() + ", got " + c.getName());
            }
        });
        TaskManager.class.getMethod("insertTask", TaskEntity.class);
        for (String m : List.of("updateTaskSuspensionStateByProcessDefinitionId", "updateTaskSuspensionStateByProcessInstanceId",
                "updateTaskSuspensionStateByProcessDefinitionKey", "updateTaskSuspensionStateByCaseExecutionId")) {
            TaskManager.class.getMethod(m, String.class, SuspensionState.class);
        }
        TaskManager.class.getMethod("updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId", String.class, String.class,
                SuspensionState.class);
    }

    /** Throws {@link #failure} from an async service task (I-19). */
    public static class FailingDelegate implements JavaDelegate {
        static volatile RuntimeException failure;

        @Override
        public void execute(DelegateExecution execution) {
            throw failure;
        }
    }

    /**
     * I-19 (B-16, SL-89): every failed job execution ({@code ManagementService.executeJob} as well as the job executor)
     * runs the command of the configured {@code FailedJobCommandFactory} ({@code ExecuteJobHelper} ->
     * {@code FailedJobListener}), and Operaton's {@code JobRetryCmd.shouldDecrementRetriesFor} spares a retry only for an
     * {@code OptimisticLockingException}. Pinned: a plain failure costs one retry; an {@code OptimisticLockingException}
     * costs none; and a transient database conflict (here a deadlock, SQLState 40P01), which Sangam's factory hands to
     * the engine as an {@code OptimisticLockingException}, costs none either.
     */
    @Test
    void i19_retryIsKeptOnlyForOptimisticLockingFailures() {
        String m = """
                  <process id="fail" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                    <serviceTask id="work" operaton:asyncBefore="true" operaton:class="%s"/>
                    <sequenceFlow id="f2" sourceRef="work" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                """.formatted(FailingDelegate.class.getName());
        withEngine(a -> {
            a.deploy("t1", "fail", Map.of("fail.bpmn", bpmn(m)));
            assertEquals(1, retriesLost(a, "F-plain", new IllegalStateException("plain failure")),
                    "a plain failure costs one retry");
            assertEquals(0, retriesLost(a, "F-olock", new OptimisticLockingException("engine conflict")),
                    "I-19: an OptimisticLockingException keeps the retry");
            assertEquals(0, retriesLost(a, "F-deadlock", new RuntimeException("deadlock",
                            new java.sql.SQLException("deadlock detected", "40P01"))),
                    "SL-89: a transient database conflict keeps the retry");
        });
    }

    private static int retriesLost(OperatonEngineAdapter a, String businessKey, RuntimeException failure) {
        String pi = a.startProcess("t1", "fail", businessKey, Map.of());
        var management = a.processEngine().getManagementService();
        Job before = management.createJobQuery().processInstanceId(pi).singleResult();
        FailingDelegate.failure = failure;
        try {
            management.executeJob(before.getId());
            throw new AssertionError("the job was expected to fail");
        } catch (RuntimeException expected) {
            // the failure surfaces to the caller after the failed-job command ran
        } finally {
            FailingDelegate.failure = null;
        }
        Job after = management.createJobQuery().jobId(before.getId()).singleResult();
        return before.getRetries() - after.getRetries();
    }

    private interface ParseCall {'''
t = sub(t, "\n    private interface ParseCall {", PINS, "pin tests")
save(p, t)

# ---------------------------------------------------------------- PATCHES.md
p = E / "PATCHES.md"
t = load(p)
t = sub(t, "| Behaviour changed through public extension points | B-1 … B-14 |\n| Internal (`impl`) classes used | I-1 … I-17 |",
        "| Behaviour changed through public extension points | B-1 … B-26 |\n| Internal (`impl`) classes used | I-1 … I-19 "
        "(sangam-engine); every file that uses one, in any module, is on allow-list A or B |", "summary")
t = sub(t, "| B-16 | Job metrics: acquisition counted after `AcquireJobsCmd`; the failed-job command factory is wrapped "
           "(delegates unchanged) | `job/JobMetricsInterceptor`, `job/CountingFailedJobCommandFactory` | D-39, R13 |",
        "| B-16 | Job metrics: acquisition counted after `AcquireJobsCmd`. The failed-job command factory is wrapped: it "
        "counts every failure (`sangam_jobs_failed_total{cause,conflict}`) and **changes the retry semantics (SL-89)**: a "
        "transient database conflict (`db/Conflicts.isRetryable`: lock timeout, deadlock, serialization failure) is handed "
        "to the engine as an `OptimisticLockingException` carrying the original as its cause, so the job keeps its retry "
        "and is re-acquired, as Operaton treats its own conflicts (each conversion is logged at WARN and counted with "
        "`conflict=true`); every other failure is passed through unchanged | `job/JobMetricsInterceptor`, "
        "`job/CountingFailedJobCommandFactory` (installed by `SangamProcessEnginePlugin`) | D-39, R13, SL-89; pin "
        "`i19_retryIsKeptOnlyForOptimisticLockingFailures` |", "B-16")
B26 = ("| B-26 | (SL-45, SL-73) What a tenant-scoped engine identity creates belongs to its tenant: a deployment only "
       "into the caller's tenant (the duplicate-filter lookup is refused for another tenant), a batch and a standalone "
       "task take the caller's single tenant; anything else is refused with `TenantIsolationException` (fail closed). "
       "Operaton's batch, deployment, resource and task managers are **replaced** through the `customSessionFactories` "
       "extension point (I-18). **One Sangam TaskManager:** SL-73 tenant-scoped task creation and SG-03(c) suspension "
       "events (overrides of the `updateTaskSuspensionStateBy*` methods) are merged into one TaskManager subclass by B3 "
       "and the security agent (class name to be recorded here when merged); the guard allows exactly one | "
       "`deploy/TenantScopedCreation` (`sessionFactories()`), registered in `SangamProcessEnginePlugin.preInit` | SL-45, "
       "SL-73, SG-03(c), VI.4; pin `i18_customSessionFactoriesReplaceTheBuiltInManagers`; tests "
       "`TenantScopedCreationH2Test` |\n")
t = sub(t, "| (opt.) | Per-definition history level", B26 + "| (opt.) | Per-definition history level", "B-26")
I1819 = ("| I-18 | `interceptor.SessionFactory`, `interceptor.Session`, `persistence.entity.BatchManager`, "
         "`DeploymentManager`, `ResourceManager`, `TaskManager` (subclassed), `BatchEntity`, `DeploymentEntity`, "
         "`ResourceEntity`, `TaskEntity`, `SuspensionState`, `identity.Authentication`, `TenantManager#isTenantCheckEnabled`, "
         "`ProcessEngineConfigurationImpl#setCustomSessionFactories` | `deploy/TenantScopedCreation`, "
         "`SangamProcessEnginePlugin` | B-26: relies on `ProcessEngineConfigurationImpl.initSessionFactories` adding the "
         "custom factories after the built-in ones into a map keyed by session type (`addSessionFactory`: `Map.put`, the "
         "last one wins) and on `CommandContext#getSession` resolving managers by that type |\n"
         "| I-19 | `cmd.JobRetryCmd#shouldDecrementRetriesFor` (spares a retry only for `OptimisticLockingException`), "
         "`cmd.DefaultJobRetryCmd`, `jobexecutor.ExecuteJobHelper` / `FailedJobListener` (every failed execution runs the "
         "configured factory's command) | `job/CountingFailedJobCommandFactory` | B-16 (SL-89) |\n")
t = sub(t, "| I-16 | `bpmn.behavior.ExclusiveGatewayActivityBehavior`", I1819 + "| I-16 | `bpmn.behavior.ExclusiveGatewayActivityBehavior`", "I-18/19")
t = sub(t, "| I-17 `CronTimer.parse` (cron-utils `SPRING53`, shaded) and its evaluation | `ZonedCron` (cron in the timer zone) | `i17_cronParsedAndEvaluatedAsOperatonDoes` |\n",
        "| I-17 `CronTimer.parse` (cron-utils `SPRING53`, shaded) and its evaluation | `ZonedCron` (cron in the timer zone) | `i17_cronParsedAndEvaluatedAsOperatonDoes` |\n"
        "| I-18 custom session factories replace the built-in manager of the same session type; the Operaton `TaskManager` methods Sangam overrides (`insertTask`, the five `updateTaskSuspensionStateBy*`) keep their signatures | B-26 tenant-scoped creation, one Sangam TaskManager | `i18_customSessionFactoriesReplaceTheBuiltInManagers` |\n"
        "| I-19 `JobRetryCmd.shouldDecrementRetriesFor` spares only `OptimisticLockingException`; `executeJob` failures run the configured failed-job factory | B-16 / SL-89 retry kept on transient conflicts | `i19_retryIsKeptOnlyForOptimisticLockingFailures` |\n",
        "SG-09 pins")
t = sub(t, "4. Re-check the `TEXT_` column sizes per dialect (B-7) and the H2 `BLOB` type in PostgreSQL mode (`DatabaseGuard.prepareH2`).",
        "4. Re-check the `TEXT_` column sizes per dialect (B-7) and the H2 `BLOB` type in PostgreSQL mode (`DatabaseGuard.prepareH2`).\n"
        "7. Re-run `EngineInternalsPinTest.i18_customSessionFactoriesReplaceTheBuiltInManagers` and "
        "`i19_retryIsKeptOnlyForOptimisticLockingFailures` (I-18 session factories and TaskManager signatures, I-19 retry "
        "semantics), and `EnginePatchLedgerGuardTest` (allow-lists below).", "checklist")
engine = (S / "impl-engine.md").read_text(encoding="utf-8").replace("\r\n", "\n")
outside = (S / "impl-outside.md").read_text(encoding="utf-8").replace("\r\n", "\n")
ALLOW = ("\n## Allow-lists (checked by `EnginePatchLedgerGuardTest`, SL-105)\n\n"
         "The guard scans the main Java sources of every reactor module and fails on a file that is not listed here, and on "
         "a listed file that no longer matches. Record a new use with its B-n / I-n entry first. Test sources are not in "
         "scope (the pin tests use `impl` classes on purpose).\n\n"
         "### Allow-list A: main sources of sangam-engine that use `org.operaton.bpm.engine.impl` (I-1 … I-19)\n\n"
         + engine + "\n\n"
         "### Allow-list B: main sources outside sangam-engine that use `org.operaton.bpm.engine.impl`\n\n"
         "Recorded from the SL-105 audit (2026-11-12): each is an engine extension point or SPI used by a module plugin, "
         "behaviour or delegate. The per-file reason is not yet ledgered as an I-n entry; an Operaton upgrade re-checks "
         "these files with the engine's.\n\n"
         + outside + "\n\n"
         "### Allow-list C: `customSessionFactories` (B-26, I-18)\n\n"
         "| File | Entry |\n|---|---|\n"
         "| `sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` | B-26: registers the factories |\n"
         "| `sangam-engine/src/main/java/org/sangam/engine/deploy/TenantScopedCreation.java` | B-26: the factories and managers |\n\n"
         "### Allow-list D: `FailedJobCommandFactory` wrappers (B-16, I-19)\n\n"
         "| File | Entry |\n|---|---|\n"
         "| `sangam-engine/src/main/java/org/sangam/engine/SangamProcessEnginePlugin.java` | B-16: installs the wrapper |\n"
         "| `sangam-engine/src/main/java/org/sangam/engine/job/CountingFailedJobCommandFactory.java` | B-16: the wrapper (SL-89) |\n")
t = t.rstrip("\n") + "\n" + ALLOW
save(p, t)
print("ok")
