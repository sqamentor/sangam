// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter;
import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.rest.adapter.operaton.EngineAdapterWorkAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.WorkPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SG-15 / atomicity: {@code EXT_OP_ACKNOWLEDGED} of a work completion is appended in the completion's engine
 * transaction, on its connection, so it commits if and only if the completion commits. Before, it was a separate
 * transaction after the completion (through the {@link AuditPort}), which could fail and leave a completed work item
 * without its acknowledgement. A fake {@link EngineAdapter} runs each engine transaction on its own H2 connection and
 * lets the test look inside it before the commit.
 */
class WorkCompleteAcknowledgementH2Test {

    static final String T = "t1";
    static final RequestContext WORKER = new RequestContext(T, "worker-1", Set.of("sangam-worker"), null, null, null,
            null, null, "API_TOKEN");

    String url;
    Connection keepAlive;
    FakeEngine engine;
    EngineAdapterWorkAdapter work;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:ack" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, Dialect.POSTGRESQL);
        Jdbc.update(keepAlive, "CREATE TABLE TEST_COMPLETION (work_id VARCHAR(64) PRIMARY KEY)");
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        TxSupport tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        engine = new FakeEngine();
        work = new EngineAdapterWorkAdapter(engine, tx, new NoSeparateAudit(), new RestProperties());
    }

    @AfterEach
    void close() throws Exception {
        keepAlive.close();
    }

    long acknowledgements(Connection c, String workId) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? AND event_type=?",
                rs -> rs.getLong(1), T, "work:" + workId, "EXT_OP_ACKNOWLEDGED").orElse(0L);
    }

    static WorkPort.CompleteRequest request(long lockVersion) {
        return new WorkPort.CompleteRequest("worker-1", lockVersion, "attempt-1", Map.of("ok", true), null, null);
    }

    @Test
    void theAcknowledgementIsWrittenInTheCompletionTransaction() {
        work.complete(WORKER, "w1", request(3));
        assertEquals(List.of(1L), engine.acknowledgementsSeenBeforeCommit,
                "the completion's own transaction already holds its acknowledgement before it commits");
        assertEquals(1, acknowledgements(keepAlive, "w1"));
        String payload = Jdbc.one(keepAlive, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p ON p.event_id ="
                + " e.event_id WHERE e.chain_id='work:w1'", rs -> rs.getString(1)).orElseThrow();
        assertTrue(payload.contains("\"work_id\":\"w1\"") && payload.contains("\"worker_id\":\"worker-1\"")
                && payload.contains("\"lock_version\":3") && payload.contains("\"attempt_id\":\"attempt-1\"")
                && payload.contains("\"principal_id\":\"worker-1\""), "same payload as before: " + payload);
    }

    @Test
    void aCompletionThatDoesNotCommitLeavesNoAcknowledgement() {
        engine.failCommit = true;
        assertThrows(IllegalStateException.class, () -> work.complete(WORKER, "w2", request(1)));
        assertEquals(0, acknowledgements(keepAlive, "w2"));
        assertEquals(0L, Jdbc.one(keepAlive, "SELECT COUNT(*) FROM TEST_COMPLETION WHERE work_id='w2'",
                rs -> rs.getLong(1)).orElse(0L));
    }

    @Test
    void aStaleLeaseIsAProblemWithoutAnAcknowledgement() {
        engine.stale = true;
        ApiProblem p = assertThrows(ApiProblem.class, () -> work.complete(WORKER, "w3", request(1)));
        assertEquals(409, p.status());
        assertEquals(0, acknowledgements(keepAlive, "w3"));
    }

    /** The AuditPort must not be used by a completion any more: the acknowledgement is not a separate transaction. */
    static final class NoSeparateAudit implements AuditPort {
        @Override
        public String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload) {
            throw new AssertionError("separate audit transaction for " + eventType);
        }

        @Override
        public Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory,
                                         Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerifyResult verify(RequestContext ctx, String chainId, String businessKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Download export(RequestContext ctx, String businessKey, String format) {
            throw new UnsupportedOperationException();
        }
    }

    /** Engine transactions on their own H2 connection; completeExternal records the completion on it. */
    final class FakeEngine implements EngineAdapter {
        final List<Long> acknowledgementsSeenBeforeCommit = new ArrayList<>();
        boolean failCommit;
        boolean stale;
        private Connection current;

        @Override
        public <R> R inEngineTransaction(Function<Connection, R> work) {
            if (current != null) {
                return work.apply(current);   // context reuse, as OperatonEngineAdapter does
            }
            try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                c.setAutoCommit(false);
                current = c;
                try {
                    R r = work.apply(c);
                    if (completing) {
                        acknowledgementsSeenBeforeCommit.add(acknowledgements(c, completingWork));
                        completing = false;
                    }
                    if (failCommit) {
                        throw new IllegalStateException("commit failed");
                    }
                    c.commit();
                    return r;
                } catch (RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    current = null;
                    completing = false;
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private boolean completing;
        private String completingWork;

        @Override
        public void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables) {
            if (stale) {
                throw new StaleLeaseException(workId, StaleLeaseException.Reason.STALE_VERSION, "lock version moved on");
            }
            Jdbc.update(current, "INSERT INTO TEST_COMPLETION(work_id) VALUES (?)", workId);
            completing = true;
            completingWork = workId;
        }

        @Override
        public String engineId() {
            return "fake";
        }

        @Override
        public String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String correlateMessage(String tenantId, String messageName, String businessKey, Map<String, Object> v) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TaskView> queryTasks(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countTasks(TaskQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void claimTask(String taskId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeTask(String taskId, Map<String, Object> variables) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void extendLock(String workId, String workerId, long lockVersion, Duration newDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void failExternal(String workId, String workerId, long lockVersion, String errorMessage,
                                 int retriesLeft, Duration retryBackoff) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                                      Map<String, Object> variables) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unlockExternal(String workId, String workerId, long lockVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelInstance(String instanceId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InstanceView getInstance(String instanceId) {
            return null;
        }

        @Override
        public int executeDueJobs() {
            return 0;
        }

        @Override
        public void setClock(java.time.Instant now) {
        }

        @Override
        public String jdbcUrl() {
            return url;
        }

        @Override
        public void close() {
        }
    }
}
