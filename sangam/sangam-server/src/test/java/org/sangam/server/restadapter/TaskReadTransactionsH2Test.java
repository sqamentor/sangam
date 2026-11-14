// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import org.sangam.rest.config.SpringReadTransactions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.operaton.OperatonTaskAdapter;
import org.sangam.rest.adapter.operaton.ReadTransactions;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.sangam.rest.support.RestProperties;

/**
 * SG-15 on the real adapter, engine and audit adapter (H2, Spring-managed transactions as in sangam-server):
 * <ul>
 *   <li>task.list: every engine read in one read transaction, one commit (without it, one per read);</li>
 *   <li>task.get: the engine reads in one read transaction; the audit read and exactly one {@code AUDIT_VIEWED}
 *       after it; never two connections held at once by the request (the SL-43 pattern);</li>
 *   <li>an internal lookup ({@code detail(..., false)}) reads no audit and writes no {@code AUDIT_VIEWED};</li>
 *   <li>a problem raised inside the read transaction leaves it unchanged and commits nothing.</li>
 * </ul>
 */
class TaskReadTransactionsH2Test {

    static final String T = "t1";
    static final String KEY = "sg15read";
    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="sg15">
              <process id="sg15read" isExecutable="true" operaton:historyTimeToLive="1">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                <userTask id="review" operaton:candidateGroups="clerks"/>
                <sequenceFlow id="f2" sourceRef="review" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;
    static final RequestContext CTX = new RequestContext(T, "anil", Set.of("clerks"), null, null, null, null, null,
            "OIDC");

    static RestEngineFixture fx;
    static JdbcAuditAdapter audit;
    static OperatonTaskAdapter withReads;
    static OperatonTaskAdapter withoutReads;
    static String pi;
    static String taskId;

    @BeforeAll
    static void setUp() {
        fx = new RestEngineFixture("sg15read");
        fx.engine.getRepositoryService().createDeployment().tenantId(T).addString("sg15read.bpmn", BPMN).deploy();
        pi = fx.engine.getRuntimeService().createProcessInstanceByKey(KEY).processDefinitionTenantId(T)
                .businessKey("BK-1").setVariables(Map.of("amount", 10)).execute().getId();
        taskId = fx.engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        RestProperties props = new RestProperties();
        audit = new JdbcAuditAdapter(fx.tx, fx.engine.getHistoryService(), fx.engine.getRuntimeService(), props);
        withReads = adapter(props);
        withReads.setReadTransactions(new SpringReadTransactions(fx.txManager));
        withoutReads = adapter(props);
        // audit the extras read: a resubmission on the instance chain, a routing decision on the business-key chain
        audit.record(CTX, pi, "CASE_RESUBMITTED", Map.of("changed_fields", List.of("amount"),
                "old_values", Map.of("amount", 5), "new_values", Map.of("amount", 10)));
        audit.record(CTX, "BK-1", "ROUTING_DECISION", Map.of("decision_key", "route", "decision_version", 3));
    }

    static OperatonTaskAdapter adapter(RestProperties props) {
        return new OperatonTaskAdapter(fx.engine.getTaskService(), fx.engine.getRuntimeService(),
                fx.engine.getHistoryService(), fx.engine.getFormService(), fx.tx, audit, props);
    }

    @AfterAll
    static void tearDown() {
        fx.close();
    }

    @BeforeEach
    void reset() {
        fx.resetCounters();
    }

    long viewed() {
        return fx.tx.withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=?"
                + " AND event_type='AUDIT_VIEWED'", rs -> rs.getLong(1), T, "sec:" + T).orElse(0L));
    }

    @Test
    void theListReadsCommitOnce() {
        Page<TaskPort.Task> page = withReads.list(CTX, null, new Page.PageRequest(0, 20));
        assertTrue(page.items().stream().anyMatch(t -> taskId.equals(t.id()) && "BK-1".equals(t.businessKey())),
                page.items().toString());
        assertEquals(1, fx.commits.get(), "page, cap probe, count and business keys: one transaction");
        assertEquals(1, fx.maxHeld.get());

        fx.resetCounters();
        withoutReads.list(CTX, null, new Page.PageRequest(0, 20));
        assertTrue(fx.commits.get() >= 4, "without the read transaction each read commits: " + fx.commits.get());
    }

    @Test
    void theDetailMakesOneEngineTransactionOneAuditReadAndExactlyOneAuditViewed() {
        long before = viewed();
        fx.resetCounters();
        TaskPort.TaskDetail d = withReads.get(CTX, taskId);
        assertEquals(1, fx.maxHeld.get(), "never a second connection while one is held");
        assertEquals(3, fx.commits.get(), "engine reads, audit read, AUDIT_VIEWED");
        assertEquals(before + 1, viewed(), "exactly one AUDIT_VIEWED for the request");
        assertEquals(List.of("amount"), d.changedSinceLastSeen().stream().map(c -> c.field()).toList());
        assertEquals(1, d.ruleEvidence().size());
        assertEquals("route", d.ruleEvidence().get(0).rule());
        assertEquals(List.of("clerks"), d.candidateGroups());

        String payload = fx.tx.withConnection(c -> Jdbc.one(c, "SELECT p.payload FROM AUDIT_EVENT e JOIN AUDIT_PAYLOAD p"
                + " ON p.event_id = e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type='AUDIT_VIEWED'"
                + " ORDER BY e.seq DESC", rs -> rs.getString(1), T, "sec:" + T).orElseThrow());
        assertTrue(payload.contains("\"view\":\"task\"") && payload.contains(taskId) && payload.contains("BK-1")
                && payload.contains(pi), payload);
    }

    @Test
    void anInternalLookupReadsNoAuditAndWritesNoAuditViewed() {
        long before = viewed();
        TaskPort.TaskDetail d = withReads.detail(CTX, taskId, false);
        assertEquals(before, viewed());
        assertEquals(1, fx.commits.get(), "the engine reads only");
        assertTrue(d.changedSinceLastSeen().isEmpty() && d.ruleEvidence().isEmpty());
        assertEquals(taskId, d.task().id());
    }

    /**
     * Owner decision 2026-11-12: AUDIT_VIEWED only when audit data was actually read. A standalone task (no process
     * instance, so no instance or business-key chain) is shown without any audit read, so nothing is written.
     */
    @Test
    void aUserVisibleDetailThatReadsNoAuditWritesNoAuditViewed() {
        org.operaton.bpm.engine.task.Task standalone = fx.engine.getTaskService().newTask();
        standalone.setName("standalone");
        standalone.setTenantId(T);
        fx.engine.getTaskService().saveTask(standalone);
        fx.engine.getTaskService().addCandidateGroup(standalone.getId(), "clerks");
        long before = viewed();
        fx.resetCounters();
        TaskPort.TaskDetail d = withReads.get(CTX, standalone.getId());
        assertEquals(standalone.getId(), d.task().id());
        assertEquals(before, viewed(), "no audit read, so no AUDIT_VIEWED");
        assertEquals(1, fx.commits.get(), "the engine reads only");
        assertTrue(d.changedSinceLastSeen().isEmpty() && d.ruleEvidence().isEmpty());
    }

    @Test
    void aProblemInsideTheReadTransactionIsRethrownUnchanged() {
        long before = viewed();
        ApiProblem p = assertThrows(ApiProblem.class, () -> withReads.get(CTX, "no-such-task"));
        assertEquals(404, p.status());
        RequestContext otherTenant = new RequestContext("t2", "anil", Set.of("clerks"), null, null, null, null, null,
                "OIDC");
        assertEquals(404, assertThrows(ApiProblem.class, () -> withReads.get(otherTenant, taskId)).status());
        assertEquals(0, fx.commits.get(), "rolled back, nothing committed");
        assertEquals(before, viewed());
    }
}
