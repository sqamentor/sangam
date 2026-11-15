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
 * API or extension point (PATCHES.md I-7 … I-10, I-17, I-18, I-19, I-26, upgrade checklist items 2, 3, 6, 7 and 19). Each test pins one of them through
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
