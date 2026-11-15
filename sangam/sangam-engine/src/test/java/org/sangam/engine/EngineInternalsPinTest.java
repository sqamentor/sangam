// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.calendar.CycleBusinessCalendar;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.interceptor.SessionFactory;
import org.operaton.bpm.engine.impl.persistence.entity.BatchManager;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentManager;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceManager;
import org.operaton.bpm.engine.impl.persistence.entity.SuspensionState;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TaskManager;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.timer.DstSafeCycleBusinessCalendar;
import org.sangam.engine.timer.ZonedCron;

/**
 * SG-09: Sangam relies on behaviours of engine-internal Operaton classes for which Operaton 2.2 has no public
 * API or extension point (PATCHES.md I-7 … I-10, I-17, I-18, I-19, I-26, I-27, upgrade checklist items 2, 3, 6, 7 and 19). Each test pins one of them through
 * its observable effect, so an Operaton upgrade that changes the internal behaviour fails here, loudly, instead of
 * silently weakening Sangam.
 */
class EngineInternalsPinTest {

    private static final String NS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
            """;

    private static byte[] bpmn(String body) {
        return (NS + body + "\n</definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private static void withEngine(Consumer<OperatonEngineAdapter> test) {
        withEngine("", test);
    }

    /** @param h2Options further H2 URL settings of the test's own database, for example {@code ;LOCK_TIMEOUT=30000} */
    private static void withEngine(String h2Options, Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:p" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                + h2Options);
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        // SL-160: the test delegate is tenant-approved, as a deployment would approve it
        s.getDeployAllowedClasses().put("*", java.util.Set.of(FailingDelegate.class.getName()));
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    /**
     * I-7: {@code DbEntityManager.invokeOptimisticLockingListeners} lets the last registered listener's result win, so
     * the adapter's fetch listener turns a lost fetch race into "not fetched" instead of an exception. Pinned: many
     * workers racing for one external task never fail and exactly one gets it.
     */
    @Test
    void i7_lostFetchRaceIsSilentAndExclusive() throws Exception {
        String m = """
                  <process id="ext" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="x"/>
                    <serviceTask id="x" operaton:type="external" operaton:topic="pin"/>
                    <sequenceFlow id="f2" sourceRef="x" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                """;
        withEngine(a -> {
            a.deploy("t1", "ext", Map.of("ext.bpmn", bpmn(m)));
            for (int round = 0; round < 5; round++) {
                a.startProcess("t1", "ext", "E-" + round, Map.of());
                ExecutorService pool = Executors.newFixedThreadPool(8);
                CountDownLatch go = new CountDownLatch(1);
                List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
                List<Future<Integer>> got = new ArrayList<>();
                for (int w = 0; w < 8; w++) {
                    String worker = "w" + w;
                    got.add(pool.submit(() -> {
                        go.await();
                        try {
                            return a.fetchAndLockWork(worker, "pin", 1, Duration.ofMinutes(5)).size();
                        } catch (Throwable t) {
                            errors.add(t);
                            return 0;
                        }
                    }));
                }
                go.countDown();
                int total = 0;
                for (Future<Integer> f : got) {
                    try {
                        total += f.get(2, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        errors.add(e);
                    }
                }
                pool.shutdown();
                assertEquals(List.of(), errors.stream().map(String::valueOf).toList(), "I-7: no fetch fails");
                // the task stays locked by the winner, so the next round races for the new instance's task only
                assertEquals(1, total, "I-7: exactly one worker holds the task");
            }
        });
    }

    /** I-8: how long the test holds the root's row, and the lock wait of its database sessions (far above the hold). */
    static final long I8_HOLD_MILLIS = 2000;
    static final long I8_LOCK_WAIT_MILLIS = 30_000;

    /**
     * I-8: {@code CommandContext.tryCloseInternal} fires command-context close listeners before
     * {@code flushSessions}, which is where the L2 flush backstop locks the root of a command that did not go through
     * the adapter. Pinned: a plain {@code TaskService.complete} creates/locks the root's {@code ROOT_SEQ} row.
     *
     * <p>The test holds the root's row for {@link #I8_HOLD_MILLIS} and the plain command must still be waiting then.
     * A plain engine command waits for the root's {@code ROOT_SEQ} row (the audit sequence of its history events, then
     * the flush backstop) with the lock wait of its database session: the engine's own bound,
     * {@code SangamEngineSettings.lockTimeout}, is set by the adapter's commands and the job handler, not on this
     * path, and H2's session default is 2 s. The hold used to be 2 s as well, so whether the
     * command was still waiting or had just given up with "Timeout trying to lock table ROOT_SEQ" depended on a few
     * milliseconds (thread start, commit, timer granularity): green alone, red once in the full run on a loaded
     * machine, and red every time with a hold of 2.6 s. The database of this test therefore gets a session lock wait
     * far above the hold ({@link #I8_LOCK_WAIT_MILLIS}); the lock-wait bounds have their own tests.
     */
    @Test
    void i8_flushBackstopTakesRootLockForPlainEngineCommands() {
        String m = """
                  <process id="plain" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="u"/>
                    <userTask id="u"/>
                    <sequenceFlow id="f2" sourceRef="u" targetRef="v"/>
                    <userTask id="v"/>
                  </process>
                """;
        withEngine(";LOCK_TIMEOUT=" + I8_LOCK_WAIT_MILLIS, a -> {
            a.deploy("t1", "plain", Map.of("plain.bpmn", bpmn(m)));
            String pi = a.processEngine().getRuntimeService().startProcessInstanceByKey("plain").getId();
            var ts = a.processEngine().getTaskService();
            String task = ts.createTaskQuery().processInstanceId(pi).singleResult().getId();
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            Thread worker = new Thread(() -> {
                try {
                    ts.complete(task);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            // hold L2 of the root in another transaction; a plain engine command on that root must wait for it
            boolean waited = a.inEngineTransaction(c -> {
                assertTrue(Jdbc.one(c, "SELECT root_id FROM ROOT_SEQ WHERE root_id = ? FOR UPDATE",
                        rs -> rs.getString(1), pi).isPresent(), "root row exists");
                worker.start();
                try {
                    worker.join(I8_HOLD_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return worker.isAlive();
            });
            try {
                worker.join(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(waited, "I-8: the plain command blocked on the held root lock (backstop ran before flush)");
            assertEquals(List.of(), errors.stream().map(String::valueOf).toList());
            assertEquals("v", ts.createTaskQuery().processInstanceId(pi).singleResult().getTaskDefinitionKey(),
                    "it committed once the lock was released");
        });
    }

    /**
     * I-9: {@code TimerEntity.calculateNewDueDate} looks the {@code cycle} calendar up by name and calls it as a
     * {@link CycleBusinessCalendar}. Pinned: the registered calendar is Sangam's, and a cycle timer's follow-up is
     * scheduled by it (next due exactly one UTC hour after the fired one, with the guard row written).
     * I-10: {@code TimerEntity.preExecute} schedules the follow-up before the job handler runs, which the guard relies
     * on to suppress a firing without ending the series. Pinned: after a firing, a follow-up timer exists.
     */
    @Test
    void i9_i10_cycleCalendarAndFollowUpScheduling() {
        String m = """
                  <process id="cyc" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                    <userTask id="hold"/>
                    <boundaryEvent id="tick" attachedToRef="hold" cancelActivity="false">
                      <timerEventDefinition><timeCycle xsi:type="tFormalExpression">R/PT1H</timeCycle></timerEventDefinition>
                    </boundaryEvent>
                    <sequenceFlow id="f2" sourceRef="tick" targetRef="t"/>
                    <userTask id="t"/>
                  </process>
                """;
        withEngine(a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            assertTrue(cfg.getBusinessCalendarManager().getBusinessCalendar(CycleBusinessCalendar.NAME)
                    instanceof DstSafeCycleBusinessCalendar, "I-9: Sangam's cycle calendar is the one the engine uses");
            Instant t0 = Instant.parse("2026-11-08T00:00:00Z");
            a.setClock(t0);
            a.deploy("t1", "cyc", Map.of("cyc.bpmn", bpmn(m)));
            String pi = a.startProcess("t1", "cyc", "C-1", Map.of());
            a.setClock(t0.plus(Duration.ofMinutes(61)));
            a.executeDueJobs();
            var jobs = a.processEngine().getManagementService().createJobQuery().processInstanceId(pi).timers().list();
            assertEquals(1, jobs.size(), "I-10: the follow-up timer exists after the firing");
            assertEquals(t0.plus(Duration.ofHours(2)), jobs.get(0).getDuedate().toInstant(),
                    "I-9: follow-up due computed by the DST-safe calendar (next UTC slot)");
            long fired = a.inEngineTransaction(c -> Jdbc.one(c,
                    "SELECT fire_count FROM SANGAM_TIMER_GUARD WHERE series_key LIKE ?", rs -> rs.getLong(1),
                    "%:" + pi).orElse(-1L));
            assertEquals(1L, fired, "I-10: the guard ran in the handler with the follow-up already scheduled");
            assertEquals(1, a.queryTasks(new SangamEngineAdapter.TaskQuery("t1", null, null, pi, "t", 0, 10)).size());
        });
    }

    /**
     * I-17 (SL-53): Sangam evaluates cron cycles in the timer zone with {@link ZonedCron}, which parses through the
     * cron-utils copy shaded into operaton-engine with the definition {@code CronTimer.parse} uses. Pinned: in the UTC
     * JVM, with zone UTC, it accepts and rejects the same expressions as Operaton and returns the same next execution,
     * so an Operaton upgrade that changes cron parsing or evaluation fails here.
     */
    @Test
    void i17_cronParsedAndEvaluatedAsOperatonDoes() throws Exception {
        assertTrue(org.sangam.core.time.EngineTimeZone.isUtc(java.util.TimeZone.getDefault().toZoneId()),
                "the build's test JVM runs in UTC");
        java.util.Date after = java.util.Date.from(Instant.parse("2026-10-24T23:59:30Z"));
        for (String cron : List.of("0 0 9 * * *", "0 30 2 * * *", "0 */15 * * * *", "0 0 12 ? * MON-FRI",
                "0 0 0 L * *", "*/10 * * * * *", "0 0 3 1 1 *")) {
            java.util.Date operaton = org.operaton.bpm.engine.impl.calendar.CronTimer.parse(cron).getDueDate(after);
            Instant sangam = ZonedCron.parse(cron).nextAfter(after.toInstant(), java.time.ZoneOffset.UTC);
            assertEquals(operaton.toInstant(), sangam, cron);
        }
        for (String bad : List.of("not a cron", "0 0 25 * * *", "0 0 9 * *", "")) {
            assertTrue(fails(() -> org.operaton.bpm.engine.impl.calendar.CronTimer.parse(bad)), "Operaton rejects " + bad);
            assertTrue(fails(() -> ZonedCron.parse(bad)), "Sangam rejects " + bad);
        }
    }

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

    /**
     * I-26 (decision 332): the engine's public services are {@code ServiceImpl}s whose {@code getCommandExecutor()} is
     * the required-transaction executor, and a command issued inside a command of that executor reuses its
     * {@code CommandContext}. {@code CorrelationKeyHandover.call} relies on both to hand a key to the capture point.
     */
    @Test
    void i26_aServiceCommandInsideACommandReusesItsContext() throws Exception {
        org.operaton.bpm.engine.impl.ServiceImpl.class.getMethod("getCommandExecutor");
        withEngine(a -> {
            var runtime = a.processEngine().getRuntimeService();
            assertTrue(runtime instanceof org.operaton.bpm.engine.impl.ServiceImpl, "I-26: " + runtime.getClass());
            var executor = ((org.operaton.bpm.engine.impl.ServiceImpl) runtime).getCommandExecutor();
            List<Boolean> same = executor.execute(outer -> List.of(
                    executor.execute(inner -> inner == outer),
                    org.sangam.engine.tx.CommandState.of(outer) == executor.execute(
                            inner -> org.sangam.engine.tx.CommandState.of(inner))));
            assertEquals(List.of(true, true), same, "I-26: a nested command shares the context and the command state");
        });
    }

    /**
     * I-27 (B-46, decision 331): Operaton's delayed state change ({@code AbstractSetStateCmd.scheduleSuspensionStateUpdate})
     * creates a {@code TimerEntity} with the handler type and the canonical handler configuration below and hands it to
     * {@code JobManager.schedule(TimerEntity)}; the custom session factory makes that Sangam's JobManager. Pinned: the
     * handler types, the configuration keys {@code DelayedStateChangeTenant} reads, the method it overrides, and that
     * every command gets Sangam's JobManager.
     */
    @Test
    void i27_delayedStateChangeTimersAreScheduledThroughTheJobManagerWithThisConfiguration() throws Exception {
        org.operaton.bpm.engine.impl.persistence.entity.JobManager.class.getMethod("schedule",
                org.operaton.bpm.engine.impl.persistence.entity.TimerEntity.class);
        assertEquals("suspend-processdefinition",
                org.operaton.bpm.engine.impl.jobexecutor.TimerSuspendProcessDefinitionHandler.TYPE);
        assertEquals("activate-processdefinition",
                org.operaton.bpm.engine.impl.jobexecutor.TimerActivateProcessDefinitionHandler.TYPE);
        assertEquals("suspend-job-definition", org.operaton.bpm.engine.impl.jobexecutor.TimerSuspendJobDefinitionHandler.TYPE);
        assertEquals("activate-job-definition",
                org.operaton.bpm.engine.impl.jobexecutor.TimerActivateJobDefinitionHandler.TYPE);
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        var pd = org.operaton.bpm.engine.impl.jobexecutor.TimerChangeProcessDefinitionSuspensionStateJobHandler
                .ProcessDefinitionSuspensionStateConfiguration.class;
        var byId = json.readTree(((org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration) pd
                .getMethod("byProcessDefinitionId", String.class, boolean.class).invoke(null, "d1", true))
                .toCanonicalString());
        assertEquals("processDefinitionId", byId.path("by").asText());
        assertEquals("d1", byId.path("processDefinitionId").asText());
        var byKey = json.readTree(((org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration) pd
                .getMethod("byProcessDefinitionKey", String.class, boolean.class).invoke(null, "k1", true))
                .toCanonicalString());
        assertEquals("processDefinitionKey", byKey.path("by").asText());
        assertEquals("k1", byKey.path("processDefinitionKey").asText());
        assertTrue(!byKey.has("processDefinitionTenantId"), "I-27: no tenant key when no tenant was named: " + byKey);
        var byKeyAndTenant = json.readTree(((org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration) pd
                .getMethod("byProcessDefinitionKeyAndTenantId", String.class, String.class, boolean.class)
                .invoke(null, "k1", "t9", true)).toCanonicalString());
        assertEquals("processDefinitionKey", byKeyAndTenant.path("by").asText());
        assertEquals("t9", byKeyAndTenant.path("processDefinitionTenantId").asText());
        var byKeyWithoutTenant = json.readTree(((org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration) pd
                .getMethod("byProcessDefinitionKeyAndTenantId", String.class, String.class, boolean.class)
                .invoke(null, "k1", null, true)).toCanonicalString());
        assertTrue(byKeyWithoutTenant.has("processDefinitionTenantId")
                && byKeyWithoutTenant.get("processDefinitionTenantId").isNull(),
                "I-27: 'without tenant' is the key with a JSON null: " + byKeyWithoutTenant);
        var jd = org.operaton.bpm.engine.impl.jobexecutor.TimerChangeJobDefinitionSuspensionStateJobHandler
                .JobDefinitionSuspensionStateConfiguration.class;
        var byJobDefinition = json.readTree(((org.operaton.bpm.engine.impl.jobexecutor.JobHandlerConfiguration) jd
                .getMethod("byJobDefinitionId", String.class, boolean.class).invoke(null, "j1", true))
                .toCanonicalString());
        assertEquals("jobDefinitionId", byJobDefinition.path("by").asText());
        assertEquals("j1", byJobDefinition.path("jobDefinitionId").asText());
        withEngine(a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            Class<?> type = org.operaton.bpm.engine.impl.persistence.entity.JobManager.class;
            assertTrue(cfg.getCustomSessionFactories().contains(cfg.getSessionFactories().get(type)),
                    "I-27: the registered custom factory is the engine's factory for JobManager");
            Class<?> used = cfg.getCommandExecutorTxRequired().execute(cc -> cc.getJobManager().getClass());
            assertTrue(type.isAssignableFrom(used) && used != type && used.getName().startsWith("org.sangam."),
                    "I-27: commands use Sangam's JobManager, got " + used.getName());
        });
    }

    /**
     * I-18 (SL-45, round 3 item 3): Operaton's {@code TenantCommandChecker} has no check for a suspension-state change
     * by process definition key; what keeps an identity of tenant B off tenant A's rows is the tenant check inside
     * each by-key UPDATE statement ({@code queryTenantCheckWithPrefix} in the {@code processDefinitionKey} branch).
     * Sangam's task-suspension report ({@code TenantScopedCreation.Tasks}) reads by key with the same check. Pinned:
     * every by-key suspension UPDATE of the 2.2 mappings has the include inside that branch, and the fragment is the
     * check on the authenticated tenants. {@code SuspensionByKeyTenantH2Test} is the behaviour.
     */
    @Test
    void i18_byKeySuspensionUpdatesCarryTheCallersTenantCheck() throws Exception {
        String include = "org.operaton.bpm.engine.impl.persistence.entity.TenantEntity.queryTenantCheckWithPrefix";
        String[][] statements = {
                {"ProcessDefinition.xml", "<update id=\"updateProcessDefinitionSuspensionStateByParameters\""},
                {"JobDefinition.xml", "<update id=\"updateJobDefinitionSuspensionStateByParameters\""},
                {"Execution.xml", "<update id=\"updateExecutionSuspensionStateByParameters\""},
                {"Job.xml", "<update id=\"updateJobSuspensionStateByParameters\""},
                {"ExternalTask.xml", "<update id=\"updateExternalTaskSuspensionStateByParameters\""},
                {"Task.xml", "<sql id=\"updateTaskSuspensionStateByParametersSql\""}};
        for (String[] s : statements) {
            String xml = mapping(s[0]);
            int start = xml.indexOf(s[1]);
            assertTrue(start >= 0, "I-18: " + s[0] + " has " + s[1]);
            String statement = xml.substring(start, xml.indexOf(s[1].startsWith("<sql") ? "</sql>" : "</update>", start));
            String branch = ifBlock(statement, "<if test=\"parameter.processDefinitionKey != null\">");
            assertTrue(branch.contains("<include refid=\"" + include + "\""),
                    "I-18: the by-key branch of " + s[1] + " (" + s[0] + ") carries the tenant check: " + branch);
        }
        String tenant = mapping("Tenant.xml");
        String fragment = tenant.substring(tenant.indexOf("<sql id=\"queryTenantCheckWithPrefix\">"));
        fragment = fragment.substring(0, fragment.indexOf("</sql>"));
        assertTrue(fragment.contains("tenantCheck.isTenantCheckEnabled") && fragment.contains("tenantCheck.authTenantIds"),
                "I-18: the fragment checks the authenticated tenants: " + fragment);
    }

    /**
     * I-18 / decision 355: the caller's tenant check of the by-key UPDATEs also matches rows without a tenant (so a
     * tenant-scoped identity's by-key change reached shared definitions), and the manager methods Sangam's seam
     * overrides to refuse it ({@code TenantScopedCreation.ProcessDefinitions}, {@code JobDefinitions},
     * {@code DelayedStateChangeTenant.Jobs}) and the counts it reads keep their signatures. If Operaton ever stops
     * matching null tenants here, the refusal is still right (it only refuses changes of shared rows), but this pin says
     * the premise of PATCHES.md SG-09 changed.
     */
    @Test
    void i18_byKeyTenantCheckAlsoMatchesSharedRowsAndTheOverriddenMethodsExist() throws Exception {
        String tenant = mapping("Tenant.xml");
        String fragment = tenant.substring(tenant.indexOf("<sql id=\"queryTenantCheckWithPrefix\">"));
        fragment = fragment.substring(0, fragment.indexOf("</sql>"));
        assertTrue(fragment.replaceAll("\\s+", " ").contains("and (${columnPrefix}TENANT_ID_ is null"),
                "I-18: the tenant check lets rows without a tenant through: " + fragment);

        Class<?> state = org.operaton.bpm.engine.impl.persistence.entity.SuspensionState.class;
        Class<?> pd = org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionManager.class;
        Class<?> jd = org.operaton.bpm.engine.impl.persistence.entity.JobDefinitionManager.class;
        Class<?> job = org.operaton.bpm.engine.impl.persistence.entity.JobManager.class;
        Class<?> exec = org.operaton.bpm.engine.impl.persistence.entity.ExecutionManager.class;
        Class<?> task = TaskManager.class;
        Class<?> ext = org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskManager.class;
        Object[][] methods = {
                {pd, "updateProcessDefinitionSuspensionStateByKey", new Class<?>[] {String.class, state}},
                {pd, "updateProcessDefinitionSuspensionStateByKeyAndTenantId", new Class<?>[] {String.class, String.class, state}},
                {jd, "updateJobDefinitionSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {jd, "updateJobDefinitionSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {job, "updateJobSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {job, "updateJobSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {job, "updateStartTimerJobSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {job, "updateStartTimerJobSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {pd, "findProcessDefinitionCountByQueryCriteria",
                        new Class<?>[] {org.operaton.bpm.engine.impl.ProcessDefinitionQueryImpl.class}},
                {jd, "findJobDefinitionCountByQueryCriteria",
                        new Class<?>[] {org.operaton.bpm.engine.impl.JobDefinitionQueryImpl.class}},
                {job, "findJobCountByQueryCriteria", new Class<?>[] {org.operaton.bpm.engine.impl.JobQueryImpl.class}},
                // decision 364: the execution, task and external-task UPDATEs by key of a process instance state change
                {exec, "updateExecutionSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {exec, "updateExecutionSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {task, "updateTaskSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {task, "updateTaskSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {ext, "updateExternalTaskSuspensionStateByProcessDefinitionKey", new Class<?>[] {String.class, state}},
                {ext, "updateExternalTaskSuspensionStateByProcessDefinitionKeyAndTenantId",
                        new Class<?>[] {String.class, String.class, state}},
                {exec, "findProcessInstanceCountByQueryCriteria",
                        new Class<?>[] {org.operaton.bpm.engine.impl.ProcessInstanceQueryImpl.class}}};
        for (Object[] m : methods) {
            java.lang.reflect.Method method = ((Class<?>) m[0]).getDeclaredMethod((String) m[1], (Class<?>[]) m[2]);
            assertTrue(java.lang.reflect.Modifier.isPublic(method.getModifiers()), "I-18: public " + m[1]);
        }
        // the seam's managers are the ones every command gets (one replacement per session type, I-18)
        java.util.Set<Class<?>> replaced = new java.util.HashSet<>();
        org.sangam.engine.deploy.TenantScopedCreation.sessionFactories().forEach(f -> replaced.add(f.getSessionType()));
        replaced.add(org.sangam.engine.deploy.DelayedStateChangeTenant.sessionFactory().getSessionType());
        assertTrue(replaced.containsAll(java.util.List.of(pd, jd, job)), "decision 355 seam installed: " + replaced);
        assertTrue(replaced.containsAll(java.util.List.of(exec, task, ext)), "decision 364 seam installed: " + replaced);
        // the query the decision-364 count builds: process instances of a key without a tenant
        org.operaton.bpm.engine.impl.ProcessInstanceQueryImpl.class.getConstructor();
        org.operaton.bpm.engine.impl.ProcessInstanceQueryImpl.class.getMethod("withoutTenantId");
        withEngine(a -> {
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                    .getProcessEngineConfiguration();
            List<Class<?>> used = cfg.getCommandExecutorTxRequired().execute(cc -> List.<Class<?>>of(
                    cc.getExecutionManager().getClass(), cc.getExternalTaskManager().getClass()));
            for (Class<?> c : used) {
                assertTrue(c.getName().startsWith("org.sangam."), "decision 364: commands use Sangam's manager, got " + c);
            }
        });
    }

    private static String mapping(String file) throws java.io.IOException {
        try (var in = EngineInternalsPinTest.class.getClassLoader()
                .getResourceAsStream("org/operaton/bpm/engine/impl/mapping/entity/" + file)) {
            assertTrue(in != null, "I-18: mapping " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The {@code <if>} element that starts with {@code open}, up to its own {@code </if>}. */
    private static String ifBlock(String text, String open) {
        int start = text.indexOf(open);
        assertTrue(start >= 0, "I-18: " + open + " in " + text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<if\\b|</if>").matcher(text);
        int depth = 0;
        m.region(start, text.length());
        while (m.find()) {
            depth += m.group().equals("</if>") ? -1 : 1;
            if (depth == 0) {
                return text.substring(start, m.end());
            }
        }
        throw new AssertionError("I-18: unbalanced <if> in " + text);
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

    private interface ParseCall {
        void run() throws Exception;
    }

    private static boolean fails(ParseCall c) {
        try {
            c.run();
            return false;
        } catch (java.text.ParseException e) {
            return true;
        } catch (Exception e) {
            throw new AssertionError("expected a ParseException, got " + e, e);
        }
    }
}
