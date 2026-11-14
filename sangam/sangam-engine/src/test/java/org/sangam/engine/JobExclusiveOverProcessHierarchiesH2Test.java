// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cmd.AcquireJobsCmd;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.runtime.Job;

/**
 * M-055 (SANGAM.md 4.3 item 3): exclusive jobs are grouped per <b>root</b> instance, on by default. The default, the
 * environment override and the engine wiring are pinned, and so is the effect on Operaton's stock acquisition
 * ({@code AcquireJobsCmd}, {@code Job.xml} "AtomicExclusiveJobs"): with the default, the exclusive jobs of a parent and
 * of the child it started through a call activity form one batch, and the child's job is not acquired while the
 * parent's holds a live lock; with {@code false}, Operaton's per-instance rule applies to both.
 */
class JobExclusiveOverProcessHierarchiesH2Test {

    private static final String TREE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="m055Parent" isExecutable="true">
                <startEvent id="ps"/><sequenceFlow id="p1" sourceRef="ps" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="pa" sourceRef="fork" targetRef="parentWork"/>
                <sequenceFlow id="pb" sourceRef="fork" targetRef="call"/>
                <userTask id="parentWork" operaton:asyncBefore="true"/>
                <sequenceFlow id="pa2" sourceRef="parentWork" targetRef="pe1"/><endEvent id="pe1"/>
                <callActivity id="call" calledElement="m055Child" operaton:calledElementBinding="latest"
                              operaton:calledElementTenantId="t1"/>
                <sequenceFlow id="pb2" sourceRef="call" targetRef="pe2"/><endEvent id="pe2"/>
              </process>
              <process id="m055Child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="childWork"/>
                <userTask id="childWork" operaton:asyncBefore="true"/>
                <sequenceFlow id="c2" sourceRef="childWork" targetRef="ce"/><endEvent id="ce"/>
              </process>
            </definitions>
            """;

    private static void withEngine(Boolean exclusiveOverHierarchies, Consumer<OperatonEngineAdapter> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m055" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false); // a SANGAM_JOB_* variable on the build host must not decide the test
        s.setJobAcquireWithSkipLocked(false); // orthogonal to the exclusive rule; keeps the H2 query plain
        if (exclusiveOverHierarchies != null) {
            s.setJobExclusiveOverProcessHierarchies(exclusiveOverHierarchies);
        }
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            test.accept(a);
        }
    }

    private static ProcessEngineConfigurationImpl cfg(OperatonEngineAdapter a) {
        return (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
    }

    private static AcquiredJobs acquire(OperatonEngineAdapter a) {
        ProcessEngineConfigurationImpl cfg = cfg(a);
        return cfg.getCommandExecutorTxRequired().execute(new AcquireJobsCmd(cfg.getJobExecutor(), 10));
    }

    /** Starts the tree; returns {parent job id, child job id}. */
    private static String[] startTree(OperatonEngineAdapter a) {
        a.deploy("t1", "m055", Map.of("m055.bpmn", TREE.getBytes(StandardCharsets.UTF_8)));
        String parent = a.startProcess("t1", "m055Parent", "M055-1", Map.of());
        List<Job> jobs = a.processEngine().getManagementService().createJobQuery().list();
        assertEquals(2, jobs.size(), "one async job in the parent, one in the called child");
        Job parentJob = jobs.stream().filter(j -> parent.equals(j.getProcessInstanceId())).findFirst().orElseThrow();
        Job childJob = jobs.stream().filter(j -> !parent.equals(j.getProcessInstanceId())).findFirst().orElseThrow();
        assertEquals(parent, childJob.getRootProcessInstanceId(), "the child shares the parent's root");
        assertTrue(((JobEntity) parentJob).isExclusive() && ((JobEntity) childJob).isExclusive(),
                "async continuations are exclusive by default");
        return new String[]{parentJob.getId(), childJob.getId()};
    }

    private static void lockByOtherNode(OperatonEngineAdapter a, String jobId) {
        try (Connection c = cfg(a).getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = 'other-node', LOCK_EXP_TIME_ = ? WHERE ID_ = ?")) {
            ps.setTimestamp(1, new Timestamp(System.currentTimeMillis() + 3_600_000L));
            ps.setString(2, jobId);
            assertEquals(1, ps.executeUpdate());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> flat(AcquiredJobs acquired) {
        Set<String> ids = new HashSet<>();
        acquired.getJobIdBatches().forEach(ids::addAll);
        return ids;
    }

    @Test
    void defaultIsOnAsTheDesignSays() {
        assertTrue(new SangamEngineSettings().isJobExclusiveOverProcessHierarchies(), "SANGAM.md 4.3 item 3: on by default");
    }

    @Test
    void environmentOverrideStaysAvailable() {
        SangamEngineSettings off = new SangamEngineSettings();
        off.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        off.applyEnvironment(Map.of("SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES", "false"));
        assertFalse(off.isJobExclusiveOverProcessHierarchies(), "false switches it off");

        SangamEngineSettings blank = new SangamEngineSettings();
        blank.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        blank.applyEnvironment(Map.of("SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES", " "));
        assertTrue(blank.isJobExclusiveOverProcessHierarchies(), "a blank value keeps the default");

        SangamEngineSettings on = new SangamEngineSettings();
        on.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        on.setJobExclusiveOverProcessHierarchies(false);
        on.applyEnvironment(Map.of("SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES", "true"));
        assertTrue(on.isJobExclusiveOverProcessHierarchies(), "true switches it back on");
    }

    @Test
    void engineDefaultGroupsTheCallTreeByRoot() {
        withEngine(null, a -> {
            assertTrue(cfg(a).isJobExecutorAcquireExclusiveOverProcessHierarchies(), "the default reaches the engine");
            String[] jobs = startTree(a);
            AcquiredJobs acquired = acquire(a);
            assertEquals(1, acquired.getJobIdBatches().size(), "one batch for the whole root");
            assertEquals(Set.of(jobs[0], jobs[1]), flat(acquired));
        });
    }

    @Test
    void engineDefaultHoldsBackTheChildWhileTheParentHoldsALiveLock() {
        withEngine(null, a -> {
            String[] jobs = startTree(a);
            lockByOtherNode(a, jobs[0]);
            assertTrue(flat(acquire(a)).isEmpty(), "the child's exclusive job waits for its root's locked job");
        });
    }

    @Test
    void falseRestoresOperatonsPerInstanceRule() {
        withEngine(false, a -> {
            assertFalse(cfg(a).isJobExecutorAcquireExclusiveOverProcessHierarchies(), "the override reaches the engine");
            String[] jobs = startTree(a);
            AcquiredJobs both = acquire(a);
            assertEquals(2, both.getJobIdBatches().size(), "one batch per process instance");
            assertEquals(Set.of(jobs[0], jobs[1]), flat(both));
        });
        withEngine(false, a -> {
            String[] jobs = startTree(a);
            lockByOtherNode(a, jobs[0]);
            assertEquals(Set.of(jobs[1]), flat(acquire(a)), "per instance: the child's job is acquirable");
        });
    }
}
