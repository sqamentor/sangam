// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.OpsPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;

/**
 * Round 6 item 4: incident skip moves the token past the failed activity (the external task of an incident, and the
 * transition instance of a failed asynchronous continuation), cancel goes through ProcessPort's business cancel; both
 * need a reason, audit their event once and change nothing when refused.
 */
class OperatonIncidentActionsH2Test {

    private static final String OP = "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\"";
    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" %s targetNamespace="t">
              <process id="pay" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <serviceTask id="call" operaton:type="external" operaton:topic="bank"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="check"/>
                <userTask id="check"/><sequenceFlow id="f3" sourceRef="check" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="async" isExecutable="true">
                <startEvent id="s2"/><sequenceFlow id="g1" sourceRef="s2" targetRef="broken"/>
                <serviceTask id="broken" operaton:asyncBefore="true" operaton:expression="${notSet}"/>
                <sequenceFlow id="g2" sourceRef="broken" targetRef="review"/>
                <userTask id="review"/><sequenceFlow id="g3" sourceRef="review" targetRef="e2"/><endEvent id="e2"/>
              </process>
            </definitions>""".formatted(OP);

    static final RequestContext CTX = new RequestContext("t1", "ops-1", Set.of(), null, null, null, null, null, "OIDC");

    record Audited(String chain, String type, Map<String, ?> payload) {
    }

    private OperatonEngineAdapter adapter;
    private ProcessEngine engine;
    private final List<Audited> audited = new ArrayList<>();
    private final List<String> cancelled = new ArrayList<>();
    private OperatonIncidentActions actions;

    @BeforeEach
    void setUp() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:inc" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        adapter = SangamEngineFactory.build(ds, s, "h2");
        engine = adapter.processEngine();
        adapter.deploy("t1", "inc-" + UUID.randomUUID(), Map.of("inc.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        OpsPort ops = (OpsPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OpsPort.class},
                (p, m, a) -> {
                    if (!"incident".equals(m.getName())) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    Incident i = engine.getRuntimeService().createIncidentQuery().incidentId((String) a[1]).singleResult();
                    if (i == null || !"t1".equals(((RequestContext) a[0]).tenantId())) {
                        throw ApiProblem.notFound("incident", (String) a[1]);
                    }
                    return new OpsPort.Incident(i.getId(), i.getIncidentType(), i.getIncidentMessage(),
                            i.getProcessInstanceId(), i.getProcessDefinitionId(), i.getActivityId(), null, null, null,
                            null, null, null, i.getTenantId());
                });
        ProcessPort processes = (ProcessPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ProcessPort.class}, (p, m, a) -> {
                    if (!"cancel".equals(m.getName())) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    String id = (String) a[1];
                    cancelled.add(id + ":" + ((ProcessPort.TerminateRequest) a[2]).reason());
                    engine.getRuntimeService().deleteProcessInstance(id, ((ProcessPort.TerminateRequest) a[2]).reason());
                    return new ProcessPort.TerminationResult(id, "CANCELLED", List.of("op-1"), 0, 0);
                });
        AuditPort audit = (AuditPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AuditPort.class},
                (p, m, a) -> {
                    if (!"record".equals(m.getName())) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, ?> payload = (Map<String, ?>) a[3];
                    audited.add(new Audited((String) a[1], (String) a[2], payload));
                    return "e-" + audited.size();
                });
        TxSupport tx = new TxSupport(null, null) {
            @Override
            public <T> T inTxWithStatus(Function<TxStatus, T> work) {
                return work.apply(null);
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                return work.get();
            }

            @Override
            public Connection connection() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void afterCommit(Runnable work) {
                work.run();
            }
        };
        actions = new OperatonIncidentActions(engine.getRuntimeService(), ops, processes, tx, audit);
    }

    @AfterEach
    void tearDown() {
        adapter.close();
    }

    private String externalTaskIncident() {
        String pid = adapter.startProcess("t1", "pay", "P-1", Map.of());
        List<LockedExternalTask> locked = engine.getExternalTaskService().fetchAndLock(1, "w").topic("bank", 60_000)
                .execute();
        engine.getExternalTaskService().handleFailure(locked.get(0).getId(), "w", "bank down", 0, 0);
        return engine.getRuntimeService().createIncidentQuery().processInstanceId(pid).singleResult().getId();
    }

    private static OpsPort.IncidentAction because(String reason) {
        return new OpsPort.IncidentAction(reason, null, null);
    }

    @Test
    void skipMovesTheTokenPastTheFailedExternalTask() {
        String incident = externalTaskIncident();
        // started after the failure, so the worker above could only have locked the first instance's task
        String other = adapter.startProcess("t1", "pay", "P-2", Map.of());
        Incident i = engine.getRuntimeService().createIncidentQuery().incidentId(incident).singleResult();

        OpsPort.Incident result = actions.skipIncident(CTX, incident, because("bank confirmed the payment by phone"));

        assertEquals(incident, result.id());
        String pid = i.getProcessInstanceId();
        assertEquals("check", engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult()
                .getTaskDefinitionKey());
        assertEquals(0, engine.getRuntimeService().createIncidentQuery().processInstanceId(pid).count());
        assertEquals(0, engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(pid).count());
        assertEquals(1, engine.getExternalTaskService().createExternalTaskQuery().processInstanceId(other).count(),
                "another instance of the activity is untouched");
        assertEquals(1, audited.size());
        assertEquals(OperatonIncidentActions.SKIPPED, audited.get(0).type());
        assertEquals(pid, audited.get(0).chain());
        assertEquals("bank confirmed the payment by phone", audited.get(0).payload().get("reason"));
        assertTrue(audited.get(0).payload().containsKey("activityInstanceId"));
    }

    @Test
    void skipMovesPastAFailedAsynchronousContinuation() {
        String pid = adapter.startProcess("t1", "async", "A-1", Map.of());
        var job = engine.getManagementService().createJobQuery().processInstanceId(pid).singleResult();
        engine.getManagementService().setJobRetries(job.getId(), 1);
        assertThrows(RuntimeException.class, () -> engine.getManagementService().executeJob(job.getId()));
        Incident i = engine.getRuntimeService().createIncidentQuery().processInstanceId(pid).singleResult();
        assertEquals("failedJob", i.getIncidentType());

        actions.skipIncident(CTX, i.getId(), because("delegate retired; step done by hand"));

        assertEquals("review", engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult()
                .getTaskDefinitionKey());
        assertEquals(0, engine.getManagementService().createJobQuery().processInstanceId(pid).count());
        assertTrue(audited.get(0).payload().containsKey("transitionInstanceId"));
    }

    @Test
    void cancelGoesThroughTheBusinessCancel() {
        String incident = externalTaskIncident();
        String pid = engine.getRuntimeService().createIncidentQuery().incidentId(incident).singleResult()
                .getProcessInstanceId();

        actions.cancelIncident(CTX, incident, because("duplicate application"));

        assertEquals(List.of(pid + ":duplicate application"), cancelled);
        assertEquals(0, engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count());
        assertEquals(OperatonIncidentActions.CANCELLED, audited.get(0).type());
        assertEquals(List.of("op-1"), audited.get(0).payload().get("openOperationIds"));
    }

    @Test
    void aRefusedActionChangesNothing() {
        String incident = externalTaskIncident();
        for (String blank : new String[] {null, " "}) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> actions.skipIncident(CTX, incident, because(blank)));
            assertEquals("REASON_REQUIRED", p.code());
            p = assertThrows(ApiProblem.class, () -> actions.cancelIncident(CTX, incident, because(blank)));
            assertEquals("REASON_REQUIRED", p.code());
        }
        ApiProblem foreign = assertThrows(ApiProblem.class, () -> actions.skipIncident(
                new RequestContext("t2", "ops-2", Set.of(), null, null, null, null, null, "OIDC"), incident,
                because("not mine")));
        assertEquals(404, foreign.status());
        assertEquals(1, engine.getRuntimeService().createIncidentQuery().incidentId(incident).count());
        assertTrue(audited.isEmpty());
        assertTrue(cancelled.isEmpty());
    }
}
