// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.ports.UniqueStartPort;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.InboundEventProcessor.StoredEvent;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;
import org.sangam.ports.support.PortsProblem;

/** FC-02 correlation/start, FC-04 unique start, D-35 branch addressing, FW-29/31/32 guards. */
class EventDispatcherH2Test {

    private static final String WAIT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="paid"/>
              <process id="order" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="w" targetRef="after"/>
                <userTask id="after"/><sequenceFlow id="f3" sourceRef="after" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    private static final String BRANCHES = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <message id="m" name="branchDone"/>
              <process id="branches" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="fork"/>
                <parallelGateway id="fork"/>
                <sequenceFlow id="fa" sourceRef="fork" targetRef="wa"/><sequenceFlow id="fb" sourceRef="fork" targetRef="wb"/>
                <intermediateCatchEvent id="wa">
                  <extensionElements><operaton:inputOutput><operaton:inputParameter name="branch">A</operaton:inputParameter></operaton:inputOutput></extensionElements>
                  <messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <intermediateCatchEvent id="wb">
                  <extensionElements><operaton:inputOutput><operaton:inputParameter name="branch">B</operaton:inputParameter></operaton:inputOutput></extensionElements>
                  <messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="fa2" sourceRef="wa" targetRef="ta"/><sequenceFlow id="fb2" sourceRef="wb" targetRef="tb"/>
                <userTask id="ta"/><userTask id="tb"/>
              </process>
            </definitions>""";

    private static final String START = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="applied"/>
              <process id="application" isExecutable="true">
                <startEvent id="s"><messageEventDefinition messageRef="m"/></startEvent>
                <sequenceFlow id="f1" sourceRef="s" targetRef="review"/><userTask id="review"/>
              </process>
            </definitions>""";

    /** In-memory stand-in for WS-INTEGRATION's UNIQUE_START guard. */
    static final class MemUniqueStart implements UniqueStartPort {
        final Map<String, String> rows = new HashMap<>();

        @Override
        public boolean claim(Connection c, String tenantId, String definitionKey, String businessKey, String source, String messageId) {
            return rows.putIfAbsent(tenantId + "|" + definitionKey + "|" + businessKey, "") == null;
        }

        @Override
        public void bind(Connection c, String tenantId, String definitionKey, String businessKey, String instanceId) {
            rows.put(tenantId + "|" + definitionKey + "|" + businessKey, instanceId);
        }

        @Override
        public Optional<String> existingInstance(Connection c, String tenantId, String definitionKey, String businessKey) {
            return Optional.ofNullable(rows.get(tenantId + "|" + definitionKey + "|" + businessKey));
        }
    }

    private PortsH2Engine h2;
    private EventRegistry reg;
    private EventDispatcher dispatcher;
    private MemUniqueStart unique;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        reg = new EventRegistry(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), Duration.ZERO);
        unique = new MemUniqueStart();
        dispatcher = new EventDispatcher(h2.engine, reg, new EventLog(Dialect.POSTGRESQL, false), h2.plugin.links(), unique);
        h2.engine.getRepositoryService().createDeployment().addString("w.bpmn", WAIT).addString("b.bpmn", BRANCHES)
                .addString("s.bpmn", START).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private EventDispatcher.Delivery deliver(String tenant, String key, Map<String, Object> payload, Map<String, Object> corr, String bk) {
        StoredEvent ev = new StoredEvent("ch", tenant, key, "msg-" + System.nanoTime(), payload, corr, bk);
        var def = reg.event(tenant, key).orElseThrow().definition();
        return EngineTx.execute(h2.engine, cc -> dispatcher.deliver(EngineTx.connection(), ev, def));
    }

    private long tasks(String key) {
        return h2.engine.getTaskService().createTaskQuery().taskDefinitionKey(key).count();
    }

    @Test
    void correlatesOnlyTheInstanceWhoseVariableMatchesAndCopiesPayload() {
        reg.deployEvent("", "{\"key\":\"paid\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"}],"
                + "\"correlationParameters\":[{\"name\":\"orderId\",\"type\":\"string\"}]}", null);
        String p1 = h2.engine.getRuntimeService().startProcessInstanceByKey("order", Map.of("orderId", "O-1")).getId();
        h2.engine.getRuntimeService().startProcessInstanceByKey("order", Map.of("orderId", "O-2"));

        EventDispatcher.Delivery d = deliver("", "paid", Map.of("amount", 1250L), Map.of("orderId", "O-1"), null);
        assertEquals(1, d.correlated(), "default tenant '' maps to the engine's null tenant (FW-29)");
        assertEquals(1, tasks("after"));
        assertEquals(1250L, h2.engine.getRuntimeService().getVariable(p1, "amount"));
        assertEquals(List.of("event-delivery"), h2.plugin.links().incoming("", "process", p1).stream()
                .map(org.sangam.ports.links.InstanceLinkService.Link::linkType).toList());
    }

    @Test
    void eventWithoutAnyCorrelationValueIsNotBroadcast() {
        reg.deployEvent("", "{\"key\":\"paid\",\"correlationParameters\":[{\"name\":\"orderId\"}]}", null);
        h2.engine.getRuntimeService().startProcessInstanceByKey("order", Map.of("orderId", "O-1"));
        h2.engine.getRuntimeService().startProcessInstanceByKey("order", Map.of("orderId", "O-2"));
        EventDispatcher.Delivery d = deliver("", "paid", Map.of(), Map.of(), null);
        assertFalse(d.matched(), "FW-31: stays buffered instead of advancing every waiting instance");
        assertEquals(0, tasks("after"));
    }

    @Test
    void localScopeParameterAddressesOneParallelBranch() {
        reg.deployEvent("", "{\"key\":\"branchDone\",\"correlationParameters\":[{\"name\":\"branch\",\"scope\":\"local\"}]}", null);
        h2.engine.getRuntimeService().startProcessInstanceByKey("branches", "BK-1");
        EventDispatcher.Delivery d = deliver("", "branchDone", Map.of(), Map.of("branch", "B"), "BK-1");
        assertEquals(1, d.correlated());
        assertEquals(0, tasks("ta"));
        assertEquals(1, tasks("tb"));
    }

    @Test
    void uniqueStartStartsOnceAndReportsTheDuplicate() {
        reg.deployEvent("", "{\"key\":\"applied\",\"correlationParameters\":[{\"name\":\"appId\"}],"
                + "\"startUniqueReference\":\"appId\"}", null);
        EventDispatcher.Delivery first = deliver("", "applied", Map.of(), Map.of("appId", "A-7"), "A-7");
        assertNotNull(first.startedProcessInstanceId());
        assertEquals("A-7", h2.engine.getRuntimeService().getVariable(first.startedProcessInstanceId(), "appId"),
                "correlation values become start variables");
        EventDispatcher.Delivery again = deliver("", "applied", Map.of(), Map.of("appId", "A-7"), "A-7");
        assertTrue(again.duplicateStart());
        assertNull(again.startedProcessInstanceId());
        assertEquals(1, h2.engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("application").count());
        assertEquals(first.startedProcessInstanceId(), unique.rows.get("null|application|A-7"));
        assertNotNull(deliver("", "applied", Map.of(), Map.of("appId", "A-8"), "A-8").startedProcessInstanceId());

        PortsProblem p = assertThrows(PortsProblem.class, () -> deliver("", "applied", Map.of(), Map.of(), null),
                "FW-32: a blank reference is never claimed");
        assertEquals(422, p.status());
    }

    @Test
    void handleBuffersAnEventWhoseDefinitionIsMissingAndLogsIt() throws Exception {
        StoredEvent ev = new StoredEvent("ch", "", "unknown", "m-1", Map.of(), Map.of(), null);
        Inbox.Message m = new Inbox.Message("", "evt:ch", "m-1", "unknown", Json.write(ev), 0);
        assertTrue(EventDispatcher.owns(m));
        Boolean handled = EngineTx.execute(h2.engine, cc -> dispatcher.handle(EngineTx.connection(), m));
        assertFalse(handled);
        try (Connection c = h2.connection()) {
            List<EventLog.Entry> log = new EventLog(Dialect.POSTGRESQL, false).recent(c, "", null, 10);
            assertEquals("BUFFERED", log.get(0).outcome());
        }
    }
}
