// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.InboundEventProcessor.Outcome;
import org.sangam.ports.support.EngineTx;

/** Adapter entry point to engine effect: receive (dedup, reject), buffer before subscription (D-33), dispatch. */
class InboundEventFlowH2Test {

    private static final String WAIT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="paid"/>
              <process id="order" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="w" targetRef="after"/><userTask id="after"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private InboundEventProcessor processor;
    private EventDispatcher dispatcher;
    private Inbox inbox;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        Transactions tx = EngineTx.transactions(h2.engine);
        EventRegistry reg = new EventRegistry(Dialect.POSTGRESQL, tx, Duration.ZERO);
        EventLog log = new EventLog(Dialect.POSTGRESQL, false);
        reg.deployChannel("", "{\"key\":\"bank\",\"channelType\":\"webhook\",\"channelEventKeyDetection\":{\"fixedValue\":\"paid\"},"
                + "\"messageIdJsonPointer\":\"/id\"}", null);
        reg.deployEvent("", "{\"key\":\"paid\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"}],"
                + "\"correlationParameters\":[{\"name\":\"orderId\",\"type\":\"string\",\"required\":true},"
                + "{\"name\":\"orderNo\",\"type\":\"long\"}]}", null);
        inbox = new Inbox(Dialect.POSTGRESQL, tx, "node-1", Duration.ofMinutes(1), Duration.ZERO, null);
        processor = new InboundEventProcessor(reg, inbox, log, tx, 4096);
        dispatcher = new EventDispatcher(h2.engine, reg, log, null, null);
        h2.engine.getRepositoryService().createDeployment().addString("w.bpmn", WAIT).deploy();
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    @Test
    void earlyEventIsBufferedThenDeliveredOnceAndRedeliveryIsADuplicate() {
        String raw = "{\"id\":\"tx-1\",\"amount\":500,\"orderId\":\"O-1\",\"orderNo\":42}";
        assertEquals(Outcome.ACCEPTED, processor.receive("", "bank", raw, Map.of(), null).outcome());
        assertEquals(Outcome.DUPLICATE, processor.receive("", "bank", raw, Map.of(), null).outcome(), "broker redelivery");

        inbox.processOnce(10, dispatcher);                       // no instance waits yet: buffered
        String pi = h2.engine.getRuntimeService().startProcessInstanceByKey("order", Map.of("orderId", "O-1", "orderNo", 42L)).getId();
        inbox.processOnce(10, dispatcher);
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).count(), "delivered after subscription; a long correlation value matches a Long variable after the inbox JSON round trip");
        assertEquals(500L, h2.engine.getRuntimeService().getVariable(pi, "amount"));
        inbox.processOnce(10, dispatcher);
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pi).count());
    }

    @Test
    void poisonPayloadsAreRejectedNotThrown() {
        assertEquals("PAYLOAD_TOO_LARGE", processor.receive("", "bank", "{\"id\":\"x\",\"pad\":\"" + "a".repeat(5000) + "\"}",
                Map.of(), null).code());
        assertEquals(Outcome.REJECTED, processor.receive("", "bank", "{not json", Map.of(), null).outcome());
        assertEquals(Outcome.REJECTED, processor.receive("", "bank", "{\"id\":\"tx-2\",\"amount\":1}", Map.of(), null).outcome(),
                "missing correlation parameter");
    }
}
