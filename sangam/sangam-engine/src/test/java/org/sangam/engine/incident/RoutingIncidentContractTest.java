// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.runtime.Incident;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.RouteDecision;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.deploy.StrictExclusiveGatewayBehavior;

/**
 * <b>[CONTRACT] ROUTING-INCIDENT v1</b> (sent to IFMS with SG-35-ADAPTER, ACKed; IFMS P-452 maps it in WP-04). The
 * wire-visible parts of a strict exclusive gateway's routing incident are pinned here, so any change to them fails a
 * build instead of silently breaking a consumer's mapping (SG-35 implementation: commit 0d04fea):
 * <ul>
 *   <li>Operaton incident type {@code sangamRouting};</li>
 *   <li>stable codes {@code NO_MATCHING_ROUTE} and {@code AMBIGUOUS_ROUTE}, each the prefix of the incident message
 *       ({@code "<CODE>: ..."});</li>
 *   <li>audit event type {@code ROUTING_INCIDENT}, appended once per opened incident in the same command;</li>
 *   <li>audit payload keys, in this order: {@code code, incidentId, processInstanceId, processDefinitionId, gateway,
 *       message}.</li>
 * </ul>
 * A deliberate change is a v2 of the contract: bump it with the consumers first, then change this test.
 */
class RoutingIncidentContractTest {

    static final List<String> PAYLOAD_KEYS_V1 = List.of("code", "incidentId", "processInstanceId",
            "processDefinitionId", "gateway", "message");

    // ------------------------------------------------------------------ constants

    @Test
    void incidentTypeAndAuditEventAreV1() {
        assertEquals("sangamRouting", StrictExclusiveGatewayBehavior.INCIDENT_TYPE);
        assertEquals("ROUTING_INCIDENT", RoutingIncidentHandler.AUDIT_EVENT);
    }

    @Test
    void codesAreV1AndPrefixTheMessage() {
        assertEquals("NO_MATCHING_ROUTE", RouteDecision.Outcome.NO_MATCHING_ROUTE.name());
        assertEquals("AMBIGUOUS_ROUTE", RouteDecision.Outcome.AMBIGUOUS_ROUTE.name());
        assertEquals("NO_MATCHING_ROUTE", RoutingIncidentHandler.codeOf("NO_MATCHING_ROUTE: exclusive gateway g ..."));
        assertEquals("AMBIGUOUS_ROUTE", RoutingIncidentHandler.codeOf("AMBIGUOUS_ROUTE: exclusive gateway g ..."));
    }

    @Test
    void aMessageWithoutAStableCodeFailsClosed() {
        for (String m : new String[] {null, "", "no code here", "ROUTE: gw -> f1", "no_matching_route: lower case",
                " NO_MATCHING_ROUTE: leading space"}) {
            assertThrows(ProcessEngineException.class, () -> RoutingIncidentHandler.codeOf(m), String.valueOf(m));
        }
    }

    // ------------------------------------------------------------------ end to end on the engine (H2)

    private record Appended(String tenant, String root, String type, Map<String, Object> payload) {
    }

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="ri" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f0" sourceRef="s" targetRef="prep"/>
                <userTask id="prep"/>
                <sequenceFlow id="f1" sourceRef="prep" targetRef="gwBand"/>
                <exclusiveGateway id="gwBand"/>
                <sequenceFlow id="fLow" sourceRef="gwBand" targetRef="low"><conditionExpression xsi:type="tFormalExpression">${amount &lt;= 100}</conditionExpression></sequenceFlow>
                <sequenceFlow id="fMid" sourceRef="gwBand" targetRef="mid"><conditionExpression xsi:type="tFormalExpression">${amount &gt; %s &amp;&amp; amount &lt;= 1000}</conditionExpression></sequenceFlow>
                <userTask id="low"/><userTask id="mid"/>
              </process>
            </definitions>
            """;

    private static void run(String lowerMidBound, long amount, String expectedCode) {
        List<Appended> appended = Collections.synchronizedList(new ArrayList<>());
        AuditSink recording = (c, tenant, root, type, payload) ->
                appended.add(new Appended(tenant, root, type, new LinkedHashMap<>(payload)));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ri" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setAuditSink(recording);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            a.deploy("t1", "ri", Map.of("ri.bpmn", MODEL.formatted(lowerMidBound).getBytes(StandardCharsets.UTF_8)));
            ProcessEngine e = a.processEngine();
            String pi = a.startProcess("t1", "ri", "RI-" + expectedCode, Map.of("amount", amount));
            e.getTaskService().complete(e.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId());

            List<Incident> incidents = e.getRuntimeService().createIncidentQuery().processInstanceId(pi)
                    .incidentType("sangamRouting").list();
            assertEquals(1, incidents.size(), "one incident of type sangamRouting");
            Incident inc = incidents.get(0);
            assertTrue(inc.getIncidentMessage().startsWith(expectedCode + ":"), inc.getIncidentMessage());

            List<Appended> routing = appended.stream().filter(x -> "ROUTING_INCIDENT".equals(x.type())).toList();
            assertEquals(1, routing.size(), "exactly one ROUTING_INCIDENT audit event: " + routing);
            Appended ev = routing.get(0);
            assertEquals("t1", ev.tenant());
            assertEquals(pi, ev.root(), "audited on the root instance's chain");
            assertEquals(PAYLOAD_KEYS_V1, List.copyOf(ev.payload().keySet()), "v1 payload keys, in order");
            assertEquals(expectedCode, ev.payload().get("code"));
            assertEquals(inc.getId(), ev.payload().get("incidentId"));
            assertEquals(pi, ev.payload().get("processInstanceId"));
            assertEquals(inc.getProcessDefinitionId(), ev.payload().get("processDefinitionId"));
            assertEquals("gwBand", ev.payload().get("gateway"));
            assertEquals(inc.getIncidentMessage(), ev.payload().get("message"));
        }
    }

    @Test
    void noMatchingRouteIsAnIncidentAndAnAuditEventOfV1Shape() {
        run("100", 5_000L, "NO_MATCHING_ROUTE");
    }

    @Test
    void ambiguousRouteIsAnIncidentAndAnAuditEventOfV1Shape() {
        run("50", 80L, "AMBIGUOUS_ROUTE");
    }
}
