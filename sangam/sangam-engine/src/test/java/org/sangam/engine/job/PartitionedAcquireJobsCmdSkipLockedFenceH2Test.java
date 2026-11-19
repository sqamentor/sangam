// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * On Oracle the partitioned candidate select (SKIP LOCKED) locks rows chosen from the statement's snapshot, so it can
 * return a job another node locked (and committed) after that snapshot; locking it again would run the job on two
 * nodes. H2 cannot reproduce the snapshot, so the peer's lock is injected right after the candidate select, on the same
 * connection.
 */
class PartitionedAcquireJobsCmdSkipLockedFenceH2Test {

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

    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean raced = new AtomicBoolean();

    @Test
    void aJobAnotherNodeLockedAfterTheCandidateSelectIsNotLockedAgain() throws Exception {
        JdbcDataSource real = new JdbcDataSource();
        real.setURL("jdbc:h2:mem:fence" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setExecutorPartitioning(true);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(racing(real), s, "h2")) {
            a.deploy("t1", "m056-" + UUID.randomUUID(), Map.of("m056.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
            String pid = a.startProcess("t1", "m056", "M056-" + UUID.randomUUID(), Map.of());
            String jobId = a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).singleResult().getId();
            ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl) a.processEngine().getProcessEngineConfiguration();
            assertTrue(cfg.getJobExecutor().getAcquireJobsCmd(10) instanceof PartitionedAcquireJobsCmd);

            armed.set(true);
            AcquiredJobs acquired = cfg.getCommandExecutorTxRequired().execute(cfg.getJobExecutor().getAcquireJobsCmd(10));
            armed.set(false);
            Set<String> ids = new HashSet<>();
            acquired.getJobIdBatches().forEach(ids::addAll);

            assertTrue(raced.get(), "the peer's lock was injected");
            assertTrue(ids.isEmpty(), "a job another node holds is not in this node's batch: " + ids);
            try (Connection c = real.getConnection()) {
                assertEquals("peer-node", Jdbc.one(c, "SELECT LOCK_OWNER_ FROM ACT_RU_JOB WHERE ID_ = ?",
                        rs -> rs.getString(1), jobId).orElseThrow(), "the peer's live lock is not overwritten");
            }
        }
    }

    /**
     * After the skip-locked candidate select, before the next statement, locks every job for a peer node (as a peer
     * would have committed after the select's snapshot).
     */
    private DataSource racing(DataSource real) {
        ClassLoader cl = getClass().getClassLoader();
        AtomicBoolean afterSelect = new AtomicBoolean();
        return (DataSource) Proxy.newProxyInstance(cl, new Class<?>[]{DataSource.class}, (p, m, args) -> {
            Object out = invoke(real, m, args);
            if (out instanceof Connection conn) {
                return Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class}, (p2, m2, a2) -> {
                    if (m2.getName().equals("prepareStatement") && armed.get() && !raced.get()) {
                        String sql = String.valueOf(a2[0]);
                        if (afterSelect.get()) {
                            raced.set(true);
                            Jdbc.update(conn, "UPDATE ACT_RU_JOB SET LOCK_OWNER_ = 'peer-node',"
                                    + " LOCK_EXP_TIME_ = DATEADD('HOUR', 1, CURRENT_TIMESTAMP), REV_ = REV_ + 1");
                        } else if (sql.contains("SKIP LOCKED") && sql.contains("ACT_RU_JOB")) {
                            afterSelect.set(true);
                        }
                    }
                    return invoke(conn, m2, a2);
                });
            }
            return out;
        });
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
