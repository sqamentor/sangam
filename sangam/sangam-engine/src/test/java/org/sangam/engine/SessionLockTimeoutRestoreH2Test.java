// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.Job;

/**
 * SL-99: a pooled connection leaves a Sangam command with the session lock wait it had before. The engine runs on ONE
 * physical H2 connection that is never closed (a pool of size one), whose session lock wait is 7777 ms; Sangam's
 * bounded wait is 1234 ms. Before the fix the connection kept 1234 ms after every Sangam command and every job, so
 * whatever used it next (Operaton's own commands, Spring transactions) waited 1234 ms or forever depending on who had
 * used it last.
 */
class SessionLockTimeoutRestoreH2Test {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="asyncJob" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="m"/>
                <manualTask id="m" operaton:asyncBefore="true"/>
                <sequenceFlow id="f2" sourceRef="m" targetRef="ut"/>
                <userTask id="ut"/>
                <sequenceFlow id="f3" sourceRef="ut" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private static final int BASELINE = 7777;
    private static final int SANGAM = 1234;

    private Connection physical;
    private OperatonEngineAdapter adapter;

    @BeforeEach
    void engineOnOneConnection() throws SQLException {
        String url = "jdbc:h2:mem:sl99_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        physical = DriverManager.getConnection(url);
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.setLockTimeout(Duration.ofMillis(SANGAM));
        adapter = SangamEngineFactory.build(singleConnection(physical), settings, url);
        exec("SET LOCK_TIMEOUT " + BASELINE);
    }

    @AfterEach
    void close() throws SQLException {
        if (adapter != null) {
            adapter.close();
        }
        physical.close();
    }

    @Test
    void aCommittedCommandRestoresTheSessionLockWait() {
        int inside = adapter.inEngineTransaction(c -> lockTimeout(c));
        assertEquals(SANGAM, inside, "the command runs with Sangam's bounded wait");
        assertEquals(BASELINE, lockTimeout(physical), "and leaves the pooled session as it found it");
    }

    @Test
    void aRolledBackCommandRestoresTheSessionLockWait() {
        assertThrows(IllegalStateException.class, () -> adapter.inEngineTransaction(c -> {
            throw new IllegalStateException("command fails after setting the wait");
        }));
        assertEquals(BASELINE, lockTimeout(physical));
    }

    @Test
    void aNestedCommandRestoresTheOuterSessionValueNotSangamsOwn() {
        adapter.inEngineTransaction(c -> adapter.inEngineTransaction(c2 -> lockTimeout(c2)));
        assertEquals(BASELINE, lockTimeout(physical));
    }

    @Test
    void aGuardedJobRestoresTheSessionLockWait() {
        adapter.deploy("t1", "sl99", Map.of("j.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        adapter.startProcess("t1", "asyncJob", "SL99-1", Map.of());
        exec("SET LOCK_TIMEOUT " + BASELINE);
        Job job = adapter.processEngine().getManagementService().createJobQuery().singleResult();
        assertNotNull(job, "asyncBefore created a job");

        adapter.processEngine().getManagementService().executeJob(job.getId());

        assertEquals(BASELINE, lockTimeout(physical), "GuardedJobHandler's bounded wait does not outlive the job");
    }

    private void exec(String sql) {
        try (Statement s = physical.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int lockTimeout(Connection c) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT LOCK_TIMEOUT()")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A DataSource that always hands out the same physical connection and ignores close (a pool of one). */
    private static DataSource singleConnection(Connection physical) {
        Connection handle = (Connection) Proxy.newProxyInstance(SessionLockTimeoutRestoreH2Test.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> {
                    if ("close".equals(m.getName())) {
                        return null;
                    }
                    try {
                        return m.invoke(physical, a);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return handle;
            }

            @Override
            public Connection getConnection(String user, String password) {
                return handle;
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}