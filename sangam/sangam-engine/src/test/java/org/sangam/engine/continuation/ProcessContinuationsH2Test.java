// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.continuation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.core.security.SecurityPorts;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 149 (M-060) continue-as-new on H2: one transaction, exact typed carry, idempotent retry, pending work ends
 * with the predecessor, chain and audit, every refusal changes nothing. Oracle, SQL Server, MySQL and PostgreSQL
 * NOT_RUN. Written, NOT_RUN.
 */
class ProcessContinuationsH2Test {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <message id="m" name="poke"/>
              <process id="loop" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="f2" sourceRef="fork" targetRef="review"/>
                <sequenceFlow id="f3" sourceRef="fork" targetRef="work"/>
                <sequenceFlow id="f4" sourceRef="fork" targetRef="wait"/>
                <sequenceFlow id="f5" sourceRef="fork" targetRef="msg"/>
                <userTask id="review"/>
                <serviceTask id="work" operaton:type="external" operaton:topic="loop.work"/>
                <intermediateCatchEvent id="wait"><timerEventDefinition><timeDuration>P30D</timeDuration></timerEventDefinition></intermediateCatchEvent>
                <intermediateCatchEvent id="msg"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="f6" sourceRef="review" targetRef="e1"/>
                <endEvent id="e1"/>
                <sequenceFlow id="f7" sourceRef="work" targetRef="e2"/>
                <endEvent id="e2"/>
                <sequenceFlow id="f8" sourceRef="wait" targetRef="e3"/>
                <endEvent id="e3"/>
                <sequenceFlow id="f9" sourceRef="msg" targetRef="e4"/>
                <endEvent id="e4"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter engine;
    private RuntimeService runtime;
    private final List<String> denied = new ArrayList<>();

    private final SecurityPorts.Authorization authz = new SecurityPorts.Authorization() {
        @Override
        public org.sangam.core.layer.SqlPredicate visible(Connection c, SecurityIdentity identity,
                                                             String resourceType, String permission, String alias) {
            throw new UnsupportedOperationException("not used by continue-as-new");
        }

        @Override
        public long policyVersion(Connection c, String tenantId) {
            return 1L;
        }

        @Override
        public boolean allow(Connection c, SecurityIdentity identity, String resourceType, String permission,
                             String resourceId) {
            return !denied.contains(resourceType + "/" + permission);
        }

        @Override
        public void require(Connection c, SecurityIdentity identity, String resourceType, String permission,
                            String resourceId) {
            if (!allow(c, identity, resourceType, permission, resourceId)) {
                throw new org.sangam.core.security.SangamSecurityException.AccessDenied(
                        resourceType + "/" + permission + " denied", 0);
            }
        }
    };

    private static SecurityIdentity user(String tenant) {
        return new SecurityIdentity(tenant, "p-1", "user:p-1", Set.of(), Set.of(), Set.of(), Set.of(), "PASSWORD",
                null, null, false, Map.of());
    }

    @BeforeEach
    void start() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setApplyEnvironment(false);
        s.setAllowMultipleEnginesPerJvm(true);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:can" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        engine = SangamEngineFactory.build(ds, s, "h2");
        runtime = engine.processEngine().getRuntimeService();
        engine.deploy("t1", "loop", Map.of("loop.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    private String startLoop() {
        return runtime.createProcessInstanceByKey("loop").processDefinitionTenantId("t1").businessKey("case-7")
                .setVariable("limit", new BigDecimal("1234.50")).setVariable("round", 3L)
                .setVariable("note", "keep me out").execute().getId();
    }

    private static ProcessContinuations.Request req(String id, List<String> carry, Map<String, Object> vars) {
        return new ProcessContinuations.Request(id, "history too long", null, carry, vars);
    }

    @Test
    void continuesInOneTransactionKeepingKeyDefinitionAndExactTypes() {
        String pred = startLoop();
        String def = runtime.createProcessInstanceQuery().processInstanceId(pred).singleResult().getProcessDefinitionId();
        ProcessContinuations pc = new ProcessContinuations(engine, authz);

        ProcessContinuations.Result r = pc.continueAsNew(user("t1"), pred,
                req("req-1", List.of("limit", "round"), Map.of("extra", "x")));

        assertEquals(ProcessContinuations.Outcome.CREATED, r.outcome());
        assertNotEquals(pred, r.successorId());
        assertEquals(pred, r.chainId());
        assertEquals(1, r.generation());
        assertEquals(def, r.processDefinitionId(), "the predecessor's definition id, never the latest");
        assertEquals("case-7", r.businessKey());
        assertNull(runtime.createProcessInstanceQuery().processInstanceId(pred).singleResult(), "predecessor ended");
        HistoricProcessInstance h = engine.processEngine().getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(pred).singleResult();
        assertEquals(ProcessContinuations.DELETE_REASON_PREFIX + r.successorId(), h.getDeleteReason(), "history kept");

        TypedValue limit = runtime.getVariableTyped(r.successorId(), "limit");
        assertEquals(new BigDecimal("1234.50"), limit.getValue(), "scale kept: exact decimal (decision 138)");
        assertEquals("sangam-decimal", limit.getType().getName());
        assertEquals(3L, runtime.getVariable(r.successorId(), "round"));
        assertEquals("x", runtime.getVariable(r.successorId(), "extra"));
        assertNull(runtime.getVariable(r.successorId(), "note"), "only named variables are carried");
        assertEquals("case-7", runtime.createProcessInstanceQuery().processInstanceId(r.successorId()).singleResult()
                .getBusinessKey());

        Map<ProcessContinuations.EndedKind, Integer> ended = new java.util.EnumMap<>(ProcessContinuations.EndedKind.class);
        r.ended().forEach(e -> ended.put(e.kind(), e.ended()));
        assertEquals(1, ended.get(ProcessContinuations.EndedKind.USER_TASK));
        assertEquals(1, ended.get(ProcessContinuations.EndedKind.EXTERNAL_TASK));
        assertEquals(1, ended.get(ProcessContinuations.EndedKind.TIMER));
        assertEquals(1, ended.get(ProcessContinuations.EndedKind.MESSAGE_SUBSCRIPTION));
        assertEquals(0, engine.processEngine().getTaskService().createTaskQuery().processInstanceId(pred).count());
        assertEquals(0, engine.processEngine().getExternalTaskService().createExternalTaskQuery()
                .processInstanceId(pred).count(), "a stale worker on the predecessor finds nothing");

        int rows = engine.inEngineTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM PROCESS_CONTINUATION WHERE "
                + "tenant_id = 't1' AND request_id = 'req-1' AND predecessor_id = ? AND successor_id = ?",
                rs -> rs.getInt(1), pred, r.successorId()).orElse(0));
        assertEquals(1, rows);
    }

    @Test
    void retryWithTheSameRequestIdReturnsTheCommittedSuccessor() {
        String pred = startLoop();
        ProcessContinuations pc = new ProcessContinuations(engine, authz);
        ProcessContinuations.Result first = pc.continueAsNew(user("t1"), pred, req("req-2", List.of("limit"), Map.of()));
        ProcessContinuations.Result again = pc.continueAsNew(user("t1"), pred, req("req-2", List.of("limit"), Map.of()));
        assertEquals(ProcessContinuations.Outcome.EXISTING, again.outcome());
        assertEquals(first.successorId(), again.successorId());
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceBusinessKey("case-7").count(),
                "exactly one successor");
    }

    @Test
    void secondGenerationExtendsTheChain() {
        String pred = startLoop();
        ProcessContinuations pc = new ProcessContinuations(engine, authz);
        ProcessContinuations.Result g1 = pc.continueAsNew(user("t1"), pred, req("a", List.of("limit"), Map.of()));
        ProcessContinuations.Result g2 = pc.continueAsNew(user("t1"), g1.successorId(), req("b", List.of("limit"), Map.of()));
        assertEquals(2, g2.generation());
        assertEquals(pred, g2.chainId());
        ProcessContinuations.Chain chain = pc.chain(user("t1"), g2.successorId());
        assertEquals(List.of(pred, g1.successorId(), g2.successorId()), chain.instanceIds());
        assertEquals(2, chain.links().size());
        assertEquals(List.of(1, 2), chain.links().stream().map(ProcessContinuations.Link::generation).toList());
    }

    @Test
    void neverContinuedInstanceHasAChainOfOne() {
        String pred = startLoop();
        ProcessContinuations.Chain chain = new ProcessContinuations(engine, authz).chain(user("t1"), pred);
        assertEquals(List.of(pred), chain.instanceIds());
        assertTrue(chain.links().isEmpty());
    }

    @Test
    void alreadyContinuedInstanceIsRefusedWithItsSuccessor() {
        String pred = startLoop();
        ProcessContinuations pc = new ProcessContinuations(engine, authz);
        ProcessContinuations.Result r = pc.continueAsNew(user("t1"), pred, req("c1", List.of(), Map.of()));
        ContinuationNotAllowedException e = assertThrows(ContinuationNotAllowedException.class,
                () -> pc.continueAsNew(user("t1"), pred, req("c2", List.of(), Map.of())));
        assertEquals(ContinuationNotAllowedException.Reason.ALREADY_CONTINUED, e.reason());
        assertEquals(r.successorId(), e.successorId());
        assertEquals(ContinuationNotAllowedException.CODE, e.code());
    }

    @Test
    void suspendedInstanceIsRefusedAndNothingChanges() {
        String pred = startLoop();
        runtime.suspendProcessInstanceById(pred);
        ContinuationNotAllowedException e = assertThrows(ContinuationNotAllowedException.class,
                () -> new ProcessContinuations(engine, authz).continueAsNew(user("t1"), pred, req("s", List.of(), Map.of())));
        assertEquals(ContinuationNotAllowedException.Reason.SUSPENDED, e.reason());
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pred).count());
    }

    @Test
    void anotherTenantsInstanceIsNotFound() {
        String pred = startLoop();
        assertThrows(NotFoundException.class, () -> new ProcessContinuations(engine, authz)
                .continueAsNew(user("t2"), pred, req("x", List.of(), Map.of())));
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pred).count());
    }

    @Test
    void missingPermissionRollsEverythingBack() {
        String pred = startLoop();
        denied.add("PROCESS_INSTANCE/MODIFY");
        assertThrows(RuntimeException.class, () -> new ProcessContinuations(engine, authz)
                .continueAsNew(user("t1"), pred, req("d", List.of(), Map.of())));
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceBusinessKey("case-7").count());
        assertEquals(Integer.valueOf(0), engine.inEngineTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM PROCESS_CONTINUATION",
                rs -> rs.getInt(1)).orElse(0)));
    }

    @Test
    void noAuthorizationPortFailsClosed() {
        String pred = startLoop();
        assertThrows(RuntimeException.class, () -> new ProcessContinuations(engine, null)
                .continueAsNew(user("t1"), pred, req("n", List.of(), Map.of())));
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pred).count());
    }

    @Test
    void unknownCarryVariableRollsBack() {
        String pred = startLoop();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new ProcessContinuations(
                engine, authz).continueAsNew(user("t1"), pred, req("u", List.of("nope"), Map.of())));
        assertTrue(e.getMessage().startsWith("VALIDATION_FAILED"), e.getMessage());
        assertEquals(1, runtime.createProcessInstanceQuery().processInstanceId(pred).count());
    }

    @Test
    void requestValidation() {
        ProcessContinuations pc = new ProcessContinuations(engine, authz);
        String pred = startLoop();
        assertThrows(IllegalArgumentException.class, () -> pc.continueAsNew(user("t1"), pred,
                new ProcessContinuations.Request("r", " ", null, List.of(), Map.of())), "reason is required");
        assertThrows(IllegalArgumentException.class, () -> pc.continueAsNew(user("t1"), pred,
                new ProcessContinuations.Request(null, "why", null, List.of(), Map.of())), "request id is required");
        assertThrows(IllegalArgumentException.class, () -> pc.continueAsNew(user("t1"), pred,
                req("r", List.of("limit"), Map.of("limit", 1))), "a name both carried and given");
        assertFalse(runtime.createProcessInstanceQuery().processInstanceId(pred).list().isEmpty());
    }
}
