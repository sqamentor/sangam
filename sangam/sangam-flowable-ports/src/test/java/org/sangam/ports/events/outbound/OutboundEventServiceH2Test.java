// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.EventLog;
import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

/**
 * FC-02 send-event path: {@link OutboundEventService} (one outbox row, one delivery per channel, deterministic message
 * id R36, tenant-bound channels R38) and {@link org.sangam.ports.events.SendEventDelegate} inside the engine
 * transaction (R4: a rolled-back command sends nothing).
 */
class OutboundEventServiceH2Test {

    private static final String SEND = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                         targetNamespace="t">
              <process id="notify" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="send"/>
                <serviceTask id="send" operaton:class="org.sangam.ports.events.SendEventDelegate">
                  <extensionElements>
                    <operaton:field name="eventKey" stringValue="paid"/>
                    <operaton:field name="channels" stringValue=" hook , audit ,"/>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="f2" sourceRef="send" targetRef="wait"/><userTask id="wait"/>
              </process>
              <process id="noFields" isExecutable="true">
                <startEvent id="s2"/><sequenceFlow id="g1" sourceRef="s2" targetRef="send2"/>
                <serviceTask id="send2" operaton:class="org.sangam.ports.events.SendEventDelegate"/>
                <sequenceFlow id="g2" sourceRef="send2" targetRef="e2"/><endEvent id="e2"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private EventRegistry registry;
    private OutboundEventService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(s -> s.setOutbox(new Outbox(Dialect.POSTGRESQL)));
        registry = h2.plugin.eventRegistry();
        svc = new OutboundEventService(registry, new Outbox(Dialect.POSTGRESQL), new EventLog(Dialect.POSTGRESQL, true));
        registry.deployEvent("acc", "{\"key\":\"paid\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\",\"required\":true},"
                + "{\"name\":\"orderId\",\"type\":\"string\"}]}", null);
        for (String k : new String[]{"hook", "audit"}) {
            registry.deployChannel("acc", "{\"key\":\"" + k + "\",\"channelType\":\"webhook\",\"type\":\"outbound\",\"url\":\"https://x.example/" + k + "\"}", null);
        }
        registry.deployChannel("acc", "{\"key\":\"in\",\"channelType\":\"webhook\",\"type\":\"inbound\"}", null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private Outbox.Appended send(String tenant, List<String> channels, Map<String, Object> values, String scope) {
        return EngineTx.execute(h2.engine, cc -> svc.send(EngineTx.connection(), tenant, "root-1", "paid", channels, values, scope));
    }

    private List<String> strings(String sql, Object... p) throws SQLException {
        try (Connection c = h2.connection()) {
            return Jdbc.query(c, sql, rs -> rs.getString(1), p);
        }
    }

    @Test
    void oneOutboxRowWithOneOrderedDeliveryPerChannelAndOnlyDeclaredFields() throws Exception {
        Outbox.Appended a = send("acc", List.of("hook", "audit"), Map.of("amount", 9L, "secret", "do-not-send"), "op-1");
        assertEquals(List.of("channel:audit", "channel:hook"),
                strings("SELECT destination FROM OUTBOX_DELIVERY WHERE outbox_id = ? ORDER BY destination", a.outboxId()));
        assertEquals(List.of("acc|sangam.event.paid|root-1"), strings("SELECT tenant_id || '|' || event_type || '|' || root_id FROM OUTBOX"));
        JsonNode body = Json.tree(strings("SELECT payload FROM OUTBOX").get(0));
        assertEquals("paid", body.get("eventKey").asText());
        assertEquals(9L, body.at("/payload/amount").asLong());
        assertEquals(1, body.get("payload").size(), "undeclared variables never leave the system; absent optional fields are omitted");
        assertEquals(List.of("QUEUED"), strings("SELECT outcome FROM EVT_LOG WHERE direction = 'OUTBOUND'"));
        assertEquals(List.of("hook,audit"), strings("SELECT channel_key FROM EVT_LOG"));
    }

    @Test
    void theMessageIdIsDeterministicPerOperationScope() throws Exception {
        send("acc", List.of("hook"), Map.of("amount", 1L), "op-1");
        send("acc", List.of("hook"), Map.of("amount", 1L), "op-1");     // a retried command
        send("acc", List.of("hook"), Map.of("amount", 1L), "op-2");
        List<String> ids = strings("SELECT payload FROM OUTBOX ORDER BY id").stream()
                .map(p -> Json.tree(p).get("messageId").asText()).toList();
        assertEquals(ids.get(0), ids.get(1), "R36: same scope, same message id, so consumers dedupe the retry");
        assertNotEquals(ids.get(0), ids.get(2));
    }

    @Test
    void invalidSendsAreRefusedAndWriteNothing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> send("acc", List.of("hook"), Map.of("orderId", "x"), "a"), "required field");
        assertThrows(IllegalArgumentException.class, () -> send("acc", List.of(), Map.of("amount", 1L), "b"));
        assertThrows(IllegalArgumentException.class, () -> send("acc", null, Map.of("amount", 1L), "b"));
        assertThrows(IllegalArgumentException.class, () -> send("acc", List.of("in"), Map.of("amount", 1L), "c"), "inbound channel");
        assertThrows(IllegalArgumentException.class, () -> send("acc", List.of("hook", "ghost"), Map.of("amount", 1L), "d"));
        assertThrows(IllegalArgumentException.class, () -> send("acc", List.of("hook", "hook"), Map.of("amount", 1L), "e"), "duplicate");
        // R38: the event and channels of tenant acc do not exist for tenant ops
        assertThrows(IllegalArgumentException.class, () -> send("ops", List.of("hook"), Map.of("amount", 1L), "f"));
        assertEquals(List.of("0"), strings("SELECT CAST(COUNT(*) AS VARCHAR) FROM OUTBOX"));
        assertEquals(List.of("0"), strings("SELECT CAST(COUNT(*) AS VARCHAR) FROM OUTBOX_DELIVERY"));
    }

    @Test
    void theSendEventTaskWritesTheOutboxInTheEngineTransactionOfItsTenant() throws Exception {
        h2.engine.getRepositoryService().createDeployment().tenantId("acc").addString("send.bpmn", SEND).deploy();
        String pi = h2.engine.getRuntimeService().createProcessInstanceByKey("notify").processDefinitionTenantId("acc")
                .setVariable("amount", 42L).setVariable("orderId", "O-7").execute().getId();
        assertEquals(List.of("acc|" + pi), strings("SELECT tenant_id || '|' || root_id FROM OUTBOX"));
        assertEquals(List.of("channel:audit", "channel:hook"), strings("SELECT destination FROM OUTBOX_DELIVERY ORDER BY destination"));
        JsonNode body = Json.tree(strings("SELECT payload FROM OUTBOX").get(0));
        assertEquals(42L, body.at("/payload/amount").asLong());
        assertEquals("O-7", body.at("/payload/orderId").asText());

        String pi2 = h2.engine.getRuntimeService().createProcessInstanceByKey("notify").processDefinitionTenantId("acc")
                .setVariable("amount", 42L).execute().getId();
        List<String> ids = strings("SELECT payload FROM OUTBOX ORDER BY id").stream().map(p -> Json.tree(p).get("messageId").asText()).toList();
        assertNotEquals(ids.get(0), ids.get(1), "two activity instances, two message ids");
        assertTrue(strings("SELECT root_id FROM OUTBOX").contains(pi2));
    }

    @Test
    void aFailedSendRollsBackTheWholeCommandAndAMissingConfigurationIsAnError() throws Exception {
        h2.engine.getRepositoryService().createDeployment().tenantId("acc").addString("send.bpmn", SEND).deploy();
        RuntimeException missing = assertThrows(RuntimeException.class, () -> h2.engine.getRuntimeService().createProcessInstanceByKey("notify")
                .processDefinitionTenantId("acc").setVariable("orderId", "no amount").execute());
        assertTrue(String.valueOf(missing.getMessage()).contains("required payload field 'amount'"), missing.toString());
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().count(), "R4: instance start rolled back");
        assertEquals(List.of("0"), strings("SELECT CAST(COUNT(*) AS VARCHAR) FROM OUTBOX"));

        Exception e = assertThrows(Exception.class, () -> h2.engine.getRuntimeService().createProcessInstanceByKey("noFields")
                .processDefinitionTenantId("acc").execute());
        assertTrue(e instanceof IllegalStateException || e instanceof ProcessEngineException, e.toString());
        assertTrue(String.valueOf(e.getMessage()).contains("eventKey and channels are required"), e.getMessage());
    }
}
