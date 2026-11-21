// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.rest.adapter.jdbc.DenialAudit;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;
import org.sangam.security.audit.SecurityAuditService;

/**
 * SL-132 for {@code OperatonTaskAdapter.checkSod}: the {@code SOD_VIOLATION_BLOCKED} of a refused completion commits
 * on the instance's denial chain through the leaf pool, while the completion rolls back; the request never holds two
 * main-pool connections; the timeline, export and verification of the business key still show it.
 */
class RestDenialAuditH2Test {

    static final String T = "t1";
    static final RequestContext ANIL = new RequestContext(T, "anil", Set.of("clerks"), null, null, null, null, null,
            "OIDC");

    static RestEngineFixture fx;
    static JdbcAuditAdapter audit;
    static OperatonTaskAdapter tasks;
    static final AtomicInteger leafTransactions = new AtomicInteger();

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("sl132rest");
        fx.engine.getRepositoryService().createDeployment().tenantId(T)
                .addString("sg15read.bpmn", TaskReadTransactionsH2Test.BPMN).deploy();
        RestProperties props = new RestProperties();
        audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props);
        tasks = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
        tasks.setDenialAudit(new DenialAudit(Dialect.POSTGRESQL, leaf()));
    }

    @AfterAll
    static void tearDown() {
        fx.close();
        // R9-statics: a test class stays loaded for the whole run; its statics must not pin what they held
        fx = null;
        audit = null;
        tasks = null;
    }

    /** The leaf pool: its own (uncounted) connections. */
    static Transactions leaf() {
        return new Transactions() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                leafTransactions.incrementAndGet();
                try (Connection c = fx.raw.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        R r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    static String start(String businessKey) {
        return fx.engine.getRuntimeService().createProcessInstanceByKey(TaskReadTransactionsH2Test.KEY)
                .processDefinitionTenantId(T).businessKey(businessKey)
                .setVariables(Map.of("sangamSodExclude", "anil")).execute().getId();
    }

    static long events(String chainId, String type) {
        return fx.tx.withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type=?", rs -> rs.getLong(1), T, chainId, type).orElse(0L));
    }

    @Test
    void theRestAndSecurityDenialChainsAreTheSameChain() {
        for (String chain : List.of("sec:" + T, "a3f0c9d2-0000-4000-8000-000000000001", "BK/2026/0001")) {
            assertEquals(SecurityAuditService.denialChain(T, chain), DenialAudit.chainOf(T, chain), chain);
        }
    }

    @Test
    void aRefusedCompletionKeepsItsDenialOnTheLeafPoolAndRollsBack() {
        String pi = start("BK-SOD-1");
        String taskId = fx.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        int leafBefore = leafTransactions.get();
        fx.resetCounters();
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.complete(ANIL, taskId,
                new TaskPort.CompleteTaskRequest(null, null, "APPROVED", null, null, Map.of(), null, null, null)));
        assertEquals(422, p.status());
        assertEquals("SOD_VIOLATION", p.code());
        assertEquals(1, fx.maxHeld.get(), "no second main-pool connection while the completion holds one");
        assertEquals(leafBefore + 1, leafTransactions.get(), "the denial took one leaf transaction");
        assertEquals(1, events(DenialAudit.chainOf(T, pi), "SOD_VIOLATION_BLOCKED"), "survives the rollback");
        assertEquals(0, events(pi, "SOD_VIOLATION_BLOCKED"), "never on the instance chain itself");
        assertEquals(0, events(pi, "TASK_COMPLETED"));
        assertTrue(fx.engine.getTaskService().createTaskQuery().taskId(taskId).singleResult() != null,
                "the task is still open");

        String payload = fx.tx.withConnection(c -> Jdbc.one(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=?", rs -> rs.getString(1), T,
                DenialAudit.chainOf(T, pi)).orElseThrow());
        assertTrue(payload.contains("\"processInstanceId\":\"" + pi + "\"") && payload.contains("\"task_id\":\"" + taskId)
                && payload.contains("\"principal_id\":\"anil\""), payload);
    }

    @Test
    void theTimelineExportAndVerifyOfTheBusinessKeyIncludeItsDenials() {
        String pi = start("BK-SOD-2");
        String taskId = fx.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        assertThrows(ApiProblem.class, () -> tasks.complete(ANIL, taskId,
                new TaskPort.CompleteTaskRequest(null, null, "APPROVED", null, null, Map.of(), null, null, null)));

        Page<AuditPort.AuditEntry> timeline = audit.timeline(ANIL, "BK-SOD-2", false, new Page.PageRequest(0, 50));
        AuditPort.AuditEntry denial = timeline.items().stream().filter(e -> "SOD_VIOLATION_BLOCKED".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals(DenialAudit.chainOf(T, pi), denial.chainId());
        assertEquals(pi, denial.processInstanceId(), "the payload carries the instance");

        String json = new String(audit.export(ANIL, "BK-SOD-2", "json").content(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("SOD_VIOLATION_BLOCKED"), json);

        AuditPort.VerifyResult v = audit.verify(ANIL, null, "BK-SOD-2");
        assertTrue(v.ok());
        List<String> verified = v.chains().stream().map(AuditPort.ChainVerification::chainId).toList();
        assertTrue(verified.contains(DenialAudit.chainOf(T, pi)), verified.toString());
        assertTrue(!verified.contains(DenialAudit.chainOf(T, "BK-SOD-2")), "an empty denial chain is not listed");
    }

    @Test
    void withoutALeafPoolTheDenialStillSurvivesOnTheDenialChain() {
        OperatonTaskAdapter plain = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, new RestProperties());
        String pi = start("BK-SOD-3");
        String taskId = fx.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        assertThrows(ApiProblem.class, () -> plain.complete(ANIL, taskId,
                new TaskPort.CompleteTaskRequest(null, null, "APPROVED", null, null, Map.of(), null, null, null)));
        assertEquals(1, events(DenialAudit.chainOf(T, pi), "SOD_VIOLATION_BLOCKED"));
        assertEquals(0, events(pi, "SOD_VIOLATION_BLOCKED"));
    }
}
