// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.jobs.FilteredAcquireJobsCmd.JobClass;

/**
 * On Oracle the skip-locked candidate select locks rows chosen from the statement's snapshot, so it can return a job
 * another node locked (and committed) after that snapshot; locking it again would run the job on two nodes. H2 cannot
 * reproduce the snapshot, so the peer's lock is injected right after the candidate select, on the same connection.
 */
class FilteredAcquireJobsCmdSkipLockedFenceH2Test {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="timerP" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="tm"/>
                <intermediateCatchEvent id="tm"><timerEventDefinition><timeDate>2000-01-01T00:00:00Z</timeDate></timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="tm" targetRef="u"/><userTask id="u"/>
              </process>
            </definitions>""";

    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean raced = new AtomicBoolean();
    private PortsH2Engine h2;

    @AfterEach
    void tearDown() {
        if (h2 != null) {
            h2.close();
        }
    }

    @Test
    void aJobAnotherNodeLockedAfterTheCandidateSelectIsNotLockedAgain() throws Exception {
        h2 = PortsH2Engine.start(null, new ProcessEnginePlugin() {
            @Override
            public void preInit(ProcessEngineConfigurationImpl cfg) {
                JdbcDataSource real = new JdbcDataSource();
                real.setURL(cfg.getJdbcUrl());
                real.setUser("sa");
                real.setPassword("");
                cfg.setDataSource(racing(real));
            }

            @Override
            public void postInit(ProcessEngineConfigurationImpl cfg) {
            }

            @Override
            public void postProcessEngineBuild(org.operaton.bpm.engine.ProcessEngine engine) {
            }
        });
        h2.engine.getRepositoryService().createDeployment().addString("m.bpmn", MODEL).deploy();
        h2.engine.getRuntimeService().startProcessInstanceByKey("timerP");
        String jobId = h2.engine.getManagementService().createJobQuery().singleResult().getId();

        armed.set(true);
        var cfg = h2.configuration();
        AcquiredJobs acquired = cfg.getCommandExecutorTxRequired()
                .execute(new FilteredAcquireJobsCmd(cfg.getJobExecutor(), 10, JobClass.TIMERS, Dialect.POSTGRESQL, null));
        Set<String> ids = acquired.getJobIdBatches().stream().flatMap(List::stream).collect(Collectors.toSet());

        assertTrue(raced.get(), "the peer's lock was injected");
        assertTrue(ids.isEmpty(), "a job another node holds is not in this node's batch: " + ids);
        try (Connection c = h2.connection()) {
            assertEquals("peer-node", Jdbc.one(c, "SELECT LOCK_OWNER_ FROM ACT_RU_JOB WHERE ID_ = ?", rs -> rs.getString(1), jobId)
                    .orElseThrow(), "the peer's live lock is not overwritten");
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
