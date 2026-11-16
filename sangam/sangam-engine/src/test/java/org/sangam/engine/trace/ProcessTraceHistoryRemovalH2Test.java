// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * SL-177: a PROCESS_TRACE row goes with its instance's history on every Operaton history-removal path (by ids: the
 * history service, its batch, the end-time cleanup, a deployment's cascade delete; by removal time: the removal-time
 * cleanup), in the same transaction; rows of instances whose history stays are kept, and so is a running instance's.
 */
class ProcessTraceHistoryRemovalH2Test {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final AtomicReference<String> current = new AtomicReference<>(TRACE);
    private JdbcDataSource ds;

    private static final String MODELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="ce"/><endEvent id="ce"/>
              </process>
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="child" operaton:calledElementBinding="deployment"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="waiting" isExecutable="true">
                <startEvent id="ws"/><sequenceFlow id="w1" sourceRef="ws" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="w2" sourceRef="work" targetRef="we"/><endEvent id="we"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        s.setTraceIdSource(current::get);
        // RM-52: these trees count as archived; the archive gate itself is HistoryArchiveGuardH2Test's subject
        s.setHistoryArchiveGate((c, tenant, root) -> null);
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sl177" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.deploy("t1", "sl177-" + UUID.randomUUID(), Map.of("sl177.bpmn", MODELS.getBytes(StandardCharsets.UTF_8)));
        return a;
    }

    private Optional<String> trace(String processInstanceId) throws Exception {
        try (Connection c = ds.getConnection()) {
            return ProcessTraces.read(c, processInstanceId);
        }
    }

    private String childOf(OperatonEngineAdapter a, String parent) {
        return a.processEngine().getHistoryService().createHistoricProcessInstanceQuery().superProcessInstanceId(parent)
                .singleResult().getId();
    }

    private void setRemovalTime(String rootProcessInstanceId, Instant removalTime) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ACT_HI_PROCINST SET REMOVAL_TIME_ = ? WHERE ROOT_PROC_INST_ID_ = ?")) {
            ps.setTimestamp(1, Timestamp.from(removalTime));
            ps.setString(2, rootProcessInstanceId);
            assertTrue(ps.executeUpdate() > 0);
        }
    }

    private static <T> T inCommand(OperatonEngineAdapter a, Command<T> command) {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine()
                .getProcessEngineConfiguration();
        return cfg.getCommandExecutorTxRequired().execute(command);
    }

    @Test
    void theEngineUsesTheTraceAwareHistoricProcessInstanceManager() {
        try (OperatonEngineAdapter a = engine()) {
            Object manager = inCommand(a, cc -> cc.getHistoricProcessInstanceManager());
            assertInstanceOf(ProcessTraceHistoryRemoval.class, manager, "B-36: the custom session factory wins");
        }
    }

    @Test
    void deletingHistoryByIdRemovesExactlyThoseTraceRows() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            String removed = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            String removedChild = childOf(a, removed);
            String kept = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            String running = a.startProcess("t1", "waiting", "B-" + UUID.randomUUID(), Map.of());
            assertEquals(Optional.of(TRACE), trace(removed));
            assertEquals(Optional.of(TRACE), trace(removedChild));

            a.processEngine().getHistoryService().deleteHistoricProcessInstancesIfExists(List.of(removed));

            assertTrue(trace(removed).isEmpty(), "the removed instance's trace row is gone");
            assertEquals(Optional.of(TRACE), trace(removedChild), "the child's history stays, so does its trace row");
            assertEquals(Optional.of(TRACE), trace(kept));
            assertEquals(Optional.of(TRACE), trace(running));

            a.processEngine().getHistoryService().deleteHistoricProcessInstance(removedChild);
            assertTrue(trace(removedChild).isEmpty());
        }
    }

    @Test
    void aFailedHistoryDeletionKeepsTheTraceRow() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            String running = a.startProcess("t1", "waiting", "B-" + UUID.randomUUID(), Map.of());
            try {
                // Operaton refuses to delete the history of a running instance; the command rolls back
                a.processEngine().getHistoryService().deleteHistoricProcessInstance(running);
            } catch (RuntimeException expected) {
                // refused
            }
            assertEquals(Optional.of(TRACE), trace(running), "rolled back with the refused deletion");
        }
    }

    @Test
    void removalTimeCleanupRemovesTheTraceRowsOfDueHistoryOnly() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            String due = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            String dueChild = childOf(a, due);
            String notDue = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            Instant now = Instant.now();
            setRemovalTime(due, now.minusSeconds(3600));
            setRemovalTime(notDue, now.plusSeconds(86_400));

            // what HistoryCleanupRemovalTime.performProcessCleanup calls, with the whole hour (no minute filter)
            inCommand(a, cc -> cc.getHistoricProcessInstanceManager()
                    .deleteHistoricProcessInstancesByRemovalTime(Date.from(now), 0, 59, 500));

            assertTrue(trace(due).isEmpty());
            assertTrue(trace(dueChild).isEmpty(), "the child carries the root's removal time");
            assertEquals(Optional.of(TRACE), trace(notDue));
            assertEquals(0L, a.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                    .processInstanceId(due).count(), "the engine removed the history in the same run");
        }
    }

    @Test
    void removalTimeCleanupHonoursTheJobsMinuteRange() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            String inRange = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            String outOfRange = a.startProcess("t1", "parent", "B-" + UUID.randomUUID(), Map.of());
            Instant base = Instant.parse("2026-01-01T00:00:00Z");
            setRemovalTime(inRange, base.plusSeconds(10 * 60));   // minute 10
            setRemovalTime(outOfRange, base.plusSeconds(40 * 60)); // minute 40

            inCommand(a, cc -> cc.getHistoricProcessInstanceManager()
                    .deleteHistoricProcessInstancesByRemovalTime(Date.from(base.plusSeconds(3600)), 0, 29, 500));

            assertTrue(trace(inRange).isEmpty());
            assertEquals(Optional.of(TRACE), trace(outOfRange), "another cleanup job's minute range");
        }
    }

    @Test
    void minuteFilterUsesTheEnginesExpressionPerDatabase() {
        assertNull(ProcessTraceHistoryRemoval.minuteFilter("postgres", 0, 59), "the whole hour: no filter");
        String pg = ProcessTraceHistoryRemoval.minuteFilter("postgres", 0, 29);
        assertTrue(pg.contains("h.REMOVAL_TIME_") && pg.toUpperCase().contains("MINUTE"), pg);
        String oracle = ProcessTraceHistoryRemoval.minuteFilter("oracle", 0, 29);
        assertTrue(oracle.indexOf("h.REMOVAL_TIME_") >= 0, oracle);
        for (String db : List.of("h2", "mysql", "mssql")) {
            assertTrue(ProcessTraceHistoryRemoval.minuteFilter(db, 30, 59).contains("h.REMOVAL_TIME_"), db);
        }
        assertEquals("DELETE FROM PROCESS_TRACE WHERE process_instance_id IN (SELECT h.ID_ FROM ACT_HI_PROCINST h"
                        + " WHERE h.REMOVAL_TIME_ <= ?)",
                ProcessTraceHistoryRemoval.Jdbc.dueSql("ACT_HI_PROCINST", null));
    }
}
