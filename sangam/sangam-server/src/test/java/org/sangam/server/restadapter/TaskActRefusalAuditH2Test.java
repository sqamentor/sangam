// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;

/**
 * P-6 finding 3 (CX-1, X.2.3; the SL-132 denial path): every refusal of {@code OperatonTaskAdapter.ensureMayAct}
 * (the task is held by another officer; the caller is not a candidate) is recorded as {@code AUTH_DENIED} on the
 * instance's denial chain through the same {@link DenialAudit} leaf path as {@code SOD_VIOLATION_BLOCKED}, so it
 * survives the rollback of the refused command. The answer itself (403 {@code ACTION_NOT_PERMITTED}) is unchanged.
 * Normal case: an allowed completion writes no {@code AUTH_DENIED}.
 */
class TaskActRefusalAuditH2Test {

    static final String T = "t1";
    static final RequestContext ANIL = ctx("anil", Set.of("clerks"));
    static final RequestContext BINA = ctx("bina", Set.of("clerks"));
    /** Sees every task (sangam-operator) but is no candidate of the step. */
    static final RequestContext OPS = ctx("ops", Set.of("sangam-operator"));

    static RestEngineFixture fx;
    static OperatonTaskAdapter tasks;
    static final AtomicInteger leafTransactions = new AtomicInteger();

    static RequestContext ctx(String who, Set<String> groups) {
        return new RequestContext(T, who, groups, null, null, null, null, null, "OIDC");
    }

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("p6f3");
        fx.engine.getRepositoryService().createDeployment().tenantId(T)
                .addString("sg15read.bpmn", TaskReadTransactionsH2Test.BPMN).deploy();
        RestProperties props = new RestProperties();
        JdbcAuditAdapter audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props);
        tasks = new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
        tasks.setDenialAudit(new DenialAudit(Dialect.POSTGRESQL, leaf()));
    }

    @AfterAll
    static void tearDown() {
        fx.close();
        fx = null;
        tasks = null;
    }

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

            /** Read work in a leaf transaction of its own (Transactions contract: not through inTransaction). */
            @Override
            public <R> R read(org.sangam.core.api.ReadWork<R> work) {
                leafTransactions.incrementAndGet();
                try (Connection c = fx.raw.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        R r = work.read(c);
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

    static String[] start(String businessKey) {
        String pi = fx.engine.getRuntimeService().createProcessInstanceByKey(TaskReadTransactionsH2Test.KEY)
                .processDefinitionTenantId(T).businessKey(businessKey).execute().getId();
        String task = fx.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        return new String[] {pi, task};
    }

    static long events(String chainId, String type) {
        return fx.tx.withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type=?", rs -> rs.getLong(1), T, chainId, type).orElse(0L));
    }

    static List<String> payloads(String chainId, String type) {
        return fx.tx.withConnection(c -> Jdbc.query(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=?",
                rs -> rs.getString(1), T, chainId, type));
    }

    static TaskPort.CompleteTaskRequest approve() {
        return new TaskPort.CompleteTaskRequest(null, null, "APPROVED", null, null, Map.of(), null, null, null);
    }

    /** The single AUTH_DENIED of the instance: on its denial chain only, with the action, the reason and the actor. */
    static void assertOneDenial(String pi, String task, String who, String action, String reason) {
        assertEquals(1, events(DenialAudit.chainOf(T, pi), "AUTH_DENIED"), "AUTH_DENIED survives the rollback");
        assertEquals(0, events(pi, "AUTH_DENIED"), "never on the instance chain itself");
        String p = payloads(DenialAudit.chainOf(T, pi), "AUTH_DENIED").get(0);
        for (String part : List.of("\"action\":\"" + action + "\"", "\"reason\":\"" + reason + "\"",
                "\"outcome\":\"DENIED\"", "\"resource_type\":\"task\"", "\"task_id\":\"" + task + "\"",
                "\"principal_id\":\"" + who + "\"", "\"processInstanceId\":\"" + pi + "\"")) {
            assertTrue(p.contains(part), part + " in " + p);
        }
    }

    @Test
    void aCompletionOfATaskHeldByAnotherOfficerIsRefusedAndAudited() {
        String[] s = start("P6F3-HELD-1");
        tasks.claim(ANIL, s[1]);
        int leafBefore = leafTransactions.get();
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.complete(BINA, s[1], approve()));
        assertEquals(403, p.status());
        assertEquals("ACTION_NOT_PERMITTED", p.code());
        assertEquals(leafBefore + 1, leafTransactions.get(), "the denial took one leaf transaction");
        assertOneDenial(s[0], s[1], "bina", "task.complete", "TASK_HELD_BY_ANOTHER");
        assertEquals("anil", fx.engine.getTaskService().createTaskQuery().taskId(s[1]).singleResult().getAssignee(),
                "the task stays with its holder");
        assertEquals(0, events(s[0], "TASK_COMPLETED"));
    }

    @Test
    void aCompletionByACallerWhoIsNoCandidateIsRefusedAndAudited() {
        String[] s = start("P6F3-NOCAND-1");
        ApiProblem p = assertThrows(ApiProblem.class, () -> tasks.complete(OPS, s[1], approve()));
        assertEquals(403, p.status());
        assertEquals("ACTION_NOT_PERMITTED", p.code());
        assertOneDenial(s[0], s[1], "ops", "task.complete", "NOT_A_CANDIDATE");
        assertNotNull(fx.engine.getTaskService().createTaskQuery().taskId(s[1]).singleResult(), "the task is still open");
    }

    @Test
    void aRefusedReturnIsAudited() {
        String[] s = start("P6F3-RETURN-1");
        assertThrows(ApiProblem.class, () -> tasks.returnTask(OPS, s[1],
                new TaskPort.ReturnTaskRequest("Not mine to return.", null, null, null, null, null, null, null)));
        assertOneDenial(s[0], s[1], "ops", "task.return", "NOT_A_CANDIDATE");
    }

    @Test
    void aRefusedDelegationIsAudited() {
        String[] s = start("P6F3-DELEGATE-1");
        tasks.claim(ANIL, s[1]);
        assertThrows(ApiProblem.class, () -> tasks.delegate(BINA, s[1],
                new TaskPort.DelegateTaskRequest("carl", null, "Not mine to hand on.")));
        assertOneDenial(s[0], s[1], "bina", "task.delegate", "TASK_HELD_BY_ANOTHER");
    }

    @Test
    void anAllowedCompletionWritesNoDenial() {
        String[] s = start("P6F3-OK-1");
        tasks.complete(ANIL, s[1], approve());
        assertEquals(0, events(DenialAudit.chainOf(T, s[0]), "AUTH_DENIED"));
        assertEquals(1, events(s[0], "TASK_COMPLETED"));
    }
}
