// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-056 (R39, SANGAM.md X.2.1) against contract {@code W2-JOB-SIDE-TABLES-CONTRACT.md}, on H2 with the contract DDL:
 * off by default (nothing installed, no table needed); on, a new job gets its {@code EXEC_JOB_SLOT} row with the
 * X.2.1.2 slot in the creating transaction and loses it when it runs; the partitioned acquisition takes a job whose slot
 * this node owns (and fences the owner onto the row), leaves a job owned by another live node alone, and takes a job
 * whose partition is not live-owned (no stranding); unsupported acquisition options are refused at start-up.
 */
class PartitionedJobsH2Test {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="m056" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="work"/>
                <serviceTask id="work" operaton:asyncBefore="true" operaton:expression="${true}"/>
                <sequenceFlow id="f2" sourceRef="work" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static SangamEngineSettings settings(boolean partitioning) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setExecutorPartitioning(partitioning);
        return s;
    }

    private static JdbcDataSource h2(boolean sideTables) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m056" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        if (sideTables) {
            try (Connection c = ds.getConnection()) {
                JobSideTablesDdl.create(c);
            }
        }
        return ds;
    }

    private static void withEngine(boolean partitioning, BiConsumer<OperatonEngineAdapter, JdbcDataSource> test)
            throws Exception {
        JdbcDataSource ds = h2(partitioning);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, settings(partitioning), "h2")) {
            test.accept(a, ds);
        }
    }

    private static ProcessEngineConfigurationImpl cfg(OperatonEngineAdapter a) {
        return (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
    }

    private static String start(OperatonEngineAdapter a) {
        a.deploy("t1", "m056-" + UUID.randomUUID(), Map.of("m056.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        return a.startProcess("t1", "m056", "M056-" + UUID.randomUUID(), Map.of());
    }

    private static Job job(OperatonEngineAdapter a, String pid) {
        return a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult();
    }

    private static <T> T sql(JdbcDataSource ds, java.util.function.Function<Connection, T> f) {
        try (Connection c = ds.getConnection()) {
            return f.apply(c);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> acquire(OperatonEngineAdapter a) {
        ProcessEngineConfigurationImpl cfg = cfg(a);
        AcquiredJobs acquired = cfg.getCommandExecutorTxRequired().execute(cfg.getJobExecutor().getAcquireJobsCmd(10));
        Set<String> ids = new HashSet<>();
        acquired.getJobIdBatches().forEach(ids::addAll);
        return ids;
    }

    private static void unlock(JdbcDataSource ds, String jobId) {
        sql(ds, c -> Jdbc.update(c, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = NULL, LOCK_EXP_TIME_ = NULL WHERE ID_ = ?", jobId));
    }

    private static int slotRowCount(JdbcDataSource ds, String jobId) {
        return sql(ds, c -> Jdbc.one(c, "SELECT COUNT(*) FROM EXEC_JOB_SLOT WHERE job_id = ?", rs -> rs.getInt(1), jobId)
                .orElse(0));
    }

    private static void partition(JdbcDataSource ds, int slot, String incarnation, long epoch, long leaseMillisFromNow) {
        Timestamp lease = new Timestamp(System.currentTimeMillis() + leaseMillisFromNow);
        sql(ds, c -> {
            Jdbc.update(c, "DELETE FROM EXEC_SLOT");
            Jdbc.update(c, "DELETE FROM EXEC_PARTITION");
            Jdbc.update(c, "INSERT INTO EXEC_PARTITION (partition_id, owner_node, owner_incarnation, owner_epoch, "
                    + "lease_expires_at, state, drain_deadline, map_epoch) VALUES (1, 'node', ?, ?, ?, 'ACTIVE', NULL, 1)",
                    incarnation, epoch, lease);
            return Jdbc.update(c, "INSERT INTO EXEC_SLOT (slot, partition_id, state, from_partition, move_started_at, "
                    + "map_epoch) VALUES (?, 1, 'STABLE', NULL, NULL, 1)", slot);
        });
    }

    @Test
    void offByDefaultNothingIsInstalledAndNoTableIsNeeded() throws Exception {
        assertFalse(new SangamEngineSettings().isExecutorPartitioning(), "X.2.1.1: off by default");
        assertEquals(ExecutorSlots.DEFAULT_SLOTS, new SangamEngineSettings().getExecutorSlots());
        withEngine(false, (a, ds) -> {
            assertNull(cfg(a).getJobExecutor().getAcquireJobsCmdFactory(), "Operaton's own acquisition");
            String pid = start(a); // no EXEC_* table exists in this database
            Job j = job(a, pid);
            a.processEngine().getManagementService().executeJob(j.getId());
            assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count());
        });
    }

    @Test
    void aNewJobGetsItsSlotRowAndLosesItWhenItRuns() throws Exception {
        withEngine(true, (a, ds) -> {
            String pid = start(a);
            Job j = job(a, pid);
            Object[] row = sql(ds, c -> Jdbc.one(c, "SELECT tenant_id, root_id, slot, owner_partition, owner_epoch, recoveries "
                    + "FROM EXEC_JOB_SLOT WHERE job_id = ?", rs -> new Object[]{rs.getString(1), rs.getString(2),
                    rs.getInt(3), rs.getObject(4), rs.getObject(5), rs.getInt(6)}, j.getId()).orElseThrow());
            assertEquals("t1", row[0]);
            assertEquals(pid, row[1], "the root of a top-level instance is the instance");
            assertEquals(ExecutorSlots.slotOf("t1", pid, ExecutorSlots.DEFAULT_SLOTS), row[2], "X.2.1.2 slot");
            assertNull(row[3]);
            assertNull(row[4]);
            assertEquals(0, row[5]);
            a.processEngine().getManagementService().executeJob(j.getId());
            assertEquals(0, slotRowCount(ds, j.getId()), "deleted with the job, in the same transaction");
        });
    }

    @Test
    void acquisitionFollowsSlotOwnershipAndNeverStrandsAJob() throws Exception {
        withEngine(true, (a, ds) -> {
            PartitionedAcquireJobsCmd cmd = assertInstanceOf(PartitionedAcquireJobsCmd.class,
                    cfg(a).getJobExecutor().getAcquireJobsCmd(10));
            String me = cmd.incarnation();
            assertNotNull(me);
            String pid = start(a);
            String jobId = job(a, pid).getId();
            int slot = ExecutorSlots.slotOf("t1", pid, ExecutorSlots.DEFAULT_SLOTS);

            // no partition owns the slot (partitioning just switched on, or every owner gone): taken here
            assertTrue(acquire(a).contains(jobId), "not live-owned: acquirable (no stranding)");
            unlock(ds, jobId);

            // another live node owns the slot: left to that node
            partition(ds, slot, "other-incarnation", 5, 3_600_000);
            assertFalse(acquire(a).contains(jobId), "owned by another live partition owner");

            // this node owns it: acquired and fenced onto the slot row
            partition(ds, slot, me, 7, 3_600_000);
            assertTrue(acquire(a).contains(jobId));
            Object[] owner = sql(ds, c -> Jdbc.one(c, "SELECT owner_partition, owner_epoch FROM EXEC_JOB_SLOT WHERE job_id = ?",
                    rs -> new Object[]{rs.getInt(1), rs.getLong(2)}, jobId).orElseThrow());
            assertEquals(1, owner[0]);
            assertEquals(7L, owner[1], "X.2.1.5: the owner's epoch at acquisition");
            assertNotNull(sql(ds, c -> Jdbc.one(c, "SELECT LOCK_OWNER_ FROM ACT_RU_JOB WHERE ID_ = ?",
                    rs -> Optional.ofNullable(rs.getString(1)), jobId).orElseThrow().orElse(null)), "locked by Operaton");
            unlock(ds, jobId);

            // the other owner's lease has expired: orphaned, so taken here
            partition(ds, slot, "other-incarnation", 5, -60_000);
            assertTrue(acquire(a).contains(jobId), "an expired owner never strands a job");
        });
    }

    @Test
    void theCandidateQueryKeepsTheStockRulesAndAddsThePartitionFilter() {
        StandaloneProcessEngineConfiguration cfg = new StandaloneProcessEngineConfiguration();
        cfg.setJobExecutorAcquireByPriority(true);
        cfg.setJobExecutorPreferTimerJobs(true);
        cfg.setJobExecutorAcquireByDueDate(true);
        cfg.setJobExecutorAcquireExclusiveOverProcessHierarchies(true);
        String sql = PartitionedAcquireJobsCmd.candidateSql(Dialect.POSTGRESQL, cfg, 10);
        for (String part : List.of("j.RETRIES_ > 0", "j.SUSPENSION_STATE_ = 1", "LOCK_EXP_TIME_ < ?", "ROOT_PROC_INST_ID_",
                "'history-cleanup'", "p.owner_incarnation = ?", "NOT EXISTS (SELECT 1 FROM EXEC_JOB_SLOT",
                "ORDER BY j.PRIORITY_ DESC, j.TYPE_ DESC, j.DUEDATE_, j.ID_", "SKIP LOCKED")) {
            assertTrue(sql.contains(part), part + " in " + sql);
        }
    }

    @Test
    void unsupportedAcquisitionOptionsAreRefusedAtStart() throws Exception {
        SangamEngineSettings s = settings(true);
        s.setJobAcquireWithSkipLocked(false);
        RuntimeException e = assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(h2(true), s, "h2").close());
        Throwable t = e;
        while (t != null && (t.getMessage() == null || !t.getMessage().contains("M-056"))) {
            t = t.getCause();
        }
        assertNotNull(t, "refused with the M-056 message: " + e);
        SangamEngineSettings badSlots = settings(true);
        badSlots.setExecutorSlots(0);
        assertThrows(RuntimeException.class, () -> SangamEngineFactory.build(h2(true), badSlots, "h2").close());
    }
}
