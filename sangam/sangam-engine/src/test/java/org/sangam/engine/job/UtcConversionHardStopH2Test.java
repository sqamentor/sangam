// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.db.EngineDialect;

/**
 * Decision 150, coordinator rulings 3 and 4: the UTC conversion gate ({@code OPS_UTC_GATE}, W4-G changeset with the
 * seeded {@code ENGINE_WORK} row; these tests need it). A closed gate gives an idle acquisition and an empty fetch and
 * writes no lock; an acquisition in flight holds the gate row, so the tool's close waits for its commit; a close in
 * flight makes the acquisition wait and then see it closed; a missing row fails closed. H2 only (the H2 read is
 * {@code FOR UPDATE}); PostgreSQL, MySQL, Oracle and SQL Server NOT_RUN. Written, NOT_RUN.
 */
class UtcConversionHardStopH2Test {

    private static final String JOB_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="utcStop" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="wait"/>
                <intermediateCatchEvent id="wait"><timerEventDefinition><timeDuration>PT0S</timeDuration>
                </timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="wait" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final String TASK_MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="utcTask" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="utc-stop"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final String CLOSE = "UPDATE OPS_UTC_GATE SET state = ?, run_id = ?, changed_by = ?, changed_at = ?,"
            + " version = version + 1 WHERE gate_id = ?";

    private static OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:utcstop" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    private static ProcessEngineConfigurationImpl cfg(OperatonEngineAdapter a) {
        return (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
    }

    /** Sets the gate in its own committed transaction, as the tool does. */
    static void gate(DataSource ds, String state, String runId) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(true);
            assertEquals(1, Jdbc.update(c, CLOSE, state, runId, "ops-1", Timestamp.from(Instant.now()),
                    UtcConversionHardStop.GATE_ID));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AcquiredJobs acquire(OperatonEngineAdapter a) {
        ProcessEngineConfigurationImpl cfg = cfg(a);
        return cfg.getCommandExecutorTxRequired().execute(new AcquireJobsCmd(cfg.getJobExecutor(), 10));
    }

    private static Job job(OperatonEngineAdapter a, String pid) {
        return a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
    }

    private static String lockOwner(OperatonEngineAdapter a, String jobId) {
        return a.inEngineTransaction(c -> Jdbc.one(c, "SELECT LOCK_OWNER_ FROM ACT_RU_JOB WHERE ID_ = ?",
                rs -> String.valueOf(rs.getString(1)), jobId).orElseThrow());
    }

    @Test
    void theGateReadTakesALockOnEveryDialect() {
        Duration wait = Duration.ofSeconds(7);
        assertTrue(UtcConversionHardStop.gateSql(EngineDialect.POSTGRESQL, wait).endsWith(" FOR SHARE"));
        assertTrue(UtcConversionHardStop.gateSql(EngineDialect.MYSQL, wait).endsWith(" LOCK IN SHARE MODE"));
        assertTrue(UtcConversionHardStop.gateSql(EngineDialect.SQLSERVER, wait)
                .contains("OPS_UTC_GATE WITH (REPEATABLEREAD, ROWLOCK) WHERE"));
        assertTrue(UtcConversionHardStop.gateSql(EngineDialect.ORACLE, wait).endsWith(" FOR UPDATE WAIT 7"));
        assertTrue(UtcConversionHardStop.gateSql(EngineDialect.H2, wait).endsWith(" FOR UPDATE"));
    }

    @Test
    void aClosedGateStopsJobAcquisitionAsAnIdlePoll() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "utcStop", Map.of("utcStop.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "utcStop", "U-1", Map.of());
            gate(cfg(a).getDataSource(), UtcConversionHardStop.CLOSED, "run-1");
            AcquiredJobs none = acquire(a);
            assertEquals(0, none.size());
            assertTrue(none.getNumberOfJobsAttemptedToAcquire() >= 1, "idle, not busy: the executor backs off");
            assertEquals("null", lockOwner(a, job(a, pid).getId()), "nothing was locked");
            gate(cfg(a).getDataSource(), UtcConversionHardStop.OPEN, null);
            assertTrue(acquire(a).contains(job(a, pid).getId()), "acquisition resumes once the gate is open");
        }
    }

    @Test
    void aClosedGateStopsTheExternalTaskFetch() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "utcTask", Map.of("utcTask.bpmn", TASK_MODEL.getBytes(StandardCharsets.UTF_8)));
            a.startProcess("t1", "utcTask", "U-3", Map.of());
            gate(cfg(a).getDataSource(), UtcConversionHardStop.CLOSED, "run-3"); // ruling 4: also for ROLLING_BACK
            var ext = a.processEngine().getExternalTaskService();
            List<LockedExternalTask> none = ext.fetchAndLock(10, "worker-1").topic("utc-stop", 60_000L).execute();
            assertTrue(none.isEmpty());
            assertNull(ext.createExternalTaskQuery().topicName("utc-stop").singleResult().getWorkerId(), "not locked");
            gate(cfg(a).getDataSource(), UtcConversionHardStop.OPEN, null);
            assertEquals(1, ext.fetchAndLock(10, "worker-1").topic("utc-stop", 60_000L).execute().size());
        }
    }

    /** An acquisition during which the tool tries to close the gate from its own connection. */
    static final class CloseDuringAcquisition extends AcquireJobsCmd {
        private final DataSource ds;
        SQLException closeFailure;

        CloseDuringAcquisition(ProcessEngineConfigurationImpl cfg) {
            super(cfg.getJobExecutor(), 10);
            this.ds = cfg.getDataSource();
        }

        @Override
        public AcquiredJobs execute(CommandContext commandContext) {
            AcquiredJobs acquired = super.execute(commandContext);
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(true);
                Jdbc.update(c, "SET LOCK_TIMEOUT 200");
                Jdbc.update(c, CLOSE, UtcConversionHardStop.CLOSED, "run-race", "ops-1", Timestamp.from(Instant.now()),
                        UtcConversionHardStop.GATE_ID);
            } catch (SQLException | RuntimeException e) {
                closeFailure = e instanceof SQLException s ? s : new SQLException(e);
            }
            return acquired;
        }
    }

    @Test
    void anAcquisitionInFlightHoldsTheGateSoTheCloseWaitsForItsCommit() {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "utcStop", Map.of("utcStop.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "utcStop", "U-4", Map.of());
            ProcessEngineConfigurationImpl cfg = cfg(a);
            CloseDuringAcquisition cmd = new CloseDuringAcquisition(cfg);
            AcquiredJobs acquired = cfg.getCommandExecutorTxRequired().execute(cmd);
            assertNotNull(cmd.closeFailure, "the close could not take the gate while the acquisition held it");
            assertTrue(acquired.contains(job(a, pid).getId()));
            assertNotEquals("null", lockOwner(a, job(a, pid).getId()), "the lock committed before any close");
            gate(cfg.getDataSource(), UtcConversionHardStop.CLOSED, "run-race"); // now the close succeeds
        }
    }

    @Test
    void aCloseInFlightMakesTheAcquisitionWaitAndSeeItClosed() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            a.deploy("t1", "utcStop", Map.of("utcStop.bpmn", JOB_MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "utcStop", "U-5", Map.of());
            DataSource ds = cfg(a).getDataSource();
            try (Connection tool = ds.getConnection()) {
                tool.setAutoCommit(false);
                Jdbc.update(tool, CLOSE, UtcConversionHardStop.CLOSED, "run-5", "ops-1", Timestamp.from(Instant.now()),
                        UtcConversionHardStop.GATE_ID); // not committed yet
                CompletableFuture<AcquiredJobs> acquisition = CompletableFuture.supplyAsync(() -> acquire(a));
                Thread.sleep(200); // the acquisition is waiting on the gate row (H2 lock timeout 1 s by default)
                tool.commit();
                AcquiredJobs none = acquisition.get(10, TimeUnit.SECONDS);
                assertEquals(0, none.size(), "the acquisition read the committed close");
            }
            assertEquals("null", lockOwner(a, job(a, pid).getId()));
        }
    }

    @Test
    void aMissingGateRowFailsClosed() {
        try (OperatonEngineAdapter a = engine()) {
            a.inEngineTransaction(c -> Jdbc.update(c, "DELETE FROM OPS_UTC_GATE WHERE gate_id = ?",
                    UtcConversionHardStop.GATE_ID));
            RuntimeException e = assertThrows(RuntimeException.class, () -> acquire(a));
            assertTrue(String.valueOf(e.getMessage()).contains("OPS_UTC_GATE") || String.valueOf(e.getCause())
                    .contains("OPS_UTC_GATE"), String.valueOf(e));
        }
    }
}
