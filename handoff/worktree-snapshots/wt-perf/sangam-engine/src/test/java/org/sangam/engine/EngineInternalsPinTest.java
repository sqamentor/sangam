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
import org.sangam.core.engine.SangamEngineAdapter;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.timer.DstSafeCycleBusinessCalendar;

/**
 * SG-09: Sangam relies on four behaviours of engine-internal Operaton classes for which Operaton 2.2 has no public
 * API or extension point (PATCHES.md I-7 … I-10, upgrade checklist items 2 and 3). Each test pins one of them through
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
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:p" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
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

    /**
     * I-8: {@code CommandContext.tryCloseInternal} fires command-context close listeners before
     * {@code flushSessions}, which is where the L2 flush backstop locks the root of a command that did not go through
     * the adapter. Pinned: a plain {@code TaskService.complete} creates/locks the root's {@code ROOT_SEQ} row.
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
        withEngine(a -> {
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
                    worker.join(2000);
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
}
