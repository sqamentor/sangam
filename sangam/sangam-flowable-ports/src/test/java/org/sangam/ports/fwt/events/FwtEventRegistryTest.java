// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sangam.ports.fwt.events.FwtEventsFixture.catchModel;
import static org.sangam.ports.fwt.events.FwtEventsFixture.noHeaders;
import static org.sangam.ports.fwt.events.FwtEventsFixture.webhookChannel;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.sangam.core.layer.Inbox;
import org.sangam.ports.events.InboundEventProcessor.Outcome;
import org.sangam.ports.events.InboundEventProcessor.Result;

/**
 * FLOWABLE-WEAKNESS-AUDIT §5 scenarios of the event registry (FW-02, FW-03, FW-28 .. FW-32, FW-42, FW-43) on a real
 * Operaton engine (H2) with the ports plugin: intake through {@code InboundEventProcessor}, dispatch through the
 * inbox loop and {@code EventDispatcher}.
 */
class FwtEventRegistryTest {

    private FwtEventsFixture f;

    @BeforeEach
    void setUp() {
        f = new FwtEventsFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private static String fixedKey(String eventKey) {
        return "\"channelEventKeyDetection\":{\"fixedValue\":\"" + eventKey + "\"}";
    }

    private long rejectedLogRows(String tenant, String channel) {
        return f.count("SELECT COUNT(*) FROM EVT_LOG WHERE tenant_id = ? AND channel_key = ? AND direction = 'INBOUND'"
                + " AND outcome = 'REJECTED'", tenant, channel);
    }

    private long inboxRows() {
        return f.count("SELECT COUNT(*) FROM INBOX");
    }

    private List<String> activities(String pid) {
        return f.engine.getRuntimeService().getActiveActivityIds(pid).stream().sorted().toList();
    }

    // ------------------------------------------------------------------ FW-02

    // FWT-005
    @Test
    void fwt005_hostilePayloadsAreRejectedAsInvalidInputAndAcknowledged() {
        f.close();
        // the raw-size guard (1 MiB default) would stop a 20 MB string before parsing; a 64 MiB intake limit lets the
        // payload reach the parser, so the Jackson stream constraints (FW-02) are what is exercised here
        f = new FwtEventsFixture(64 * 1024 * 1024);
        f.registry.deployEvent("acc", "{\"key\":\"order\",\"payload\":[{\"name\":\"data\",\"type\":\"json\"}]}", null);
        f.registry.deployChannel("acc", webhookChannel("orders", fixedKey("order")), null);

        String nested = "{\"id\":\"m-nest\",\"data\":" + "[".repeat(500) + "]".repeat(500) + "}";
        String bigString = "{\"id\":\"m-str\",\"data\":\"" + "x".repeat(20 * 1024 * 1024) + "\"}";
        String bigNumber = "{\"id\":\"m-num\",\"data\":" + "9".repeat(1000) + "}";
        for (String raw : List.of(nested, bigString, bigNumber)) {
            Result r = f.processor.receive("acc", "orders", raw, noHeaders(), null);   // returns: the adapter acks
            assertEquals(Outcome.REJECTED, r.outcome(), r.toString());
            assertEquals("INVALID_INPUT", r.code(), r.reason());
        }
        assertEquals(3, rejectedLogRows("acc", "orders"));
        assertEquals(0, inboxRows(), "nothing reached the inbox");
        // and with the default intake limit the 20 MB body is refused even earlier, still as a REJECTED result
        FwtEventsFixture d = new FwtEventsFixture();
        try {
            d.registry.deployEvent("acc", "{\"key\":\"order\",\"payload\":[{\"name\":\"data\",\"type\":\"json\"}]}", null);
            d.registry.deployChannel("acc", webhookChannel("orders", fixedKey("order")), null);
            Result r = d.processor.receive("acc", "orders", bigString, noHeaders(), null);
            assertEquals(Outcome.REJECTED, r.outcome());
            assertEquals("PAYLOAD_TOO_LARGE", r.code(), r.reason());
        } finally {
            d.close();
        }
    }

    // ------------------------------------------------------------------ FW-28

    // FWT-025
    @Test
    void fwt025_messageIdOf300CharsIsRejectedAsInvalidInput() {
        f.registry.deployEvent("acc", "{\"key\":\"order\"}", null);
        f.registry.deployChannel("acc", webhookChannel("orders", fixedKey("order")), null);
        String longId = "m".repeat(300);
        Result fromPayload = f.processor.receive("acc", "orders", "{\"id\":\"" + longId + "\"}", noHeaders(), null);
        assertEquals(Outcome.REJECTED, fromPayload.outcome());
        assertEquals("INVALID_INPUT", fromPayload.code(), fromPayload.reason());
        Result fromTransport = f.processor.receive("acc", "orders", "{\"x\":1}", noHeaders(), longId);
        assertEquals(Outcome.REJECTED, fromTransport.outcome());
        assertEquals("INVALID_INPUT", fromTransport.code(), fromTransport.reason());
        assertEquals(0, inboxRows());
        assertEquals(2, rejectedLogRows("acc", "orders"));
    }

    // ------------------------------------------------------------------ FW-29

    // FWT-026
    @Test
    void fwt026_payloadTenantDifferentFromChannelTenantIsTenantMismatch() {
        f.registry.deployEvent("A", "{\"key\":\"order\"}", null);
        f.registry.deployEvent("B", "{\"key\":\"order\"}", null);
        f.registry.deployChannel("A", webhookChannel("orders", fixedKey("order")
                + ",\"channelEventTenantJsonPointerExpression\":\"/tenant\",\"allowPayloadTenant\":false"), null);
        Result r = f.processor.receive("A", "orders", "{\"id\":\"m1\",\"tenant\":\"B\"}", noHeaders(), null);
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals("TENANT_MISMATCH", r.code(), r.reason());
        assertEquals(0, inboxRows());
    }

    // FWT-027
    @Test
    void fwt027_allowPayloadTenantWithoutAllowedTenantsIsRefusedAtDeploy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> f.registry.deployChannel("A",
                webhookChannel("shared", fixedKey("order") + ",\"channelEventTenantJsonPointerExpression\":\"/tenant\","
                        + "\"allowPayloadTenant\":true"), null));
        assertTrue(e.getMessage().contains("allowedTenants"), e.getMessage());
        assertTrue(f.registry.channel("A", "shared").isEmpty(), "nothing deployed");
    }

    // FWT-028
    @Test
    void fwt028_allowedTenantsListDecidesAndDeliveryStaysInTheNamedTenant() {
        for (String t : List.of("A", "B")) {
            f.registry.deployEvent(t, "{\"key\":\"order\",\"businessKeyField\":\"bk\"}", null);
            f.deploy(t, "wait.bpmn", catchModel("waiter", "order"));
        }
        f.registry.deployChannel("A", webhookChannel("shared", fixedKey("order")
                + ",\"channelEventTenantJsonPointerExpression\":\"/tenant\",\"allowPayloadTenant\":true,"
                + "\"properties\":{\"allowedTenants\":\"B,C\"}"), null);
        ProcessInstance inA = f.engine.getRuntimeService().createProcessInstanceByKey("waiter").processDefinitionTenantId("A")
                .businessKey("k1").execute();
        ProcessInstance inB = f.engine.getRuntimeService().createProcessInstanceByKey("waiter").processDefinitionTenantId("B")
                .businessKey("k1").execute();

        Result d = f.processor.receive("A", "shared", "{\"id\":\"m-d\",\"tenant\":\"D\",\"bk\":\"k1\"}", noHeaders(), null);
        assertEquals(Outcome.REJECTED, d.outcome());
        assertEquals("TENANT_MISMATCH", d.code(), d.reason());

        Result b = f.processor.receive("A", "shared", "{\"id\":\"m-b\",\"tenant\":\"B\",\"bk\":\"k1\"}", noHeaders(), null);
        assertEquals(Outcome.ACCEPTED, b.outcome(), String.valueOf(b));
        assertEquals(List.of("B"), f.strings("SELECT tenant_id FROM INBOX"));
        assertEquals(1, f.dispatch());
        assertEquals(List.of("after"), activities(inB.getId()), "delivered in tenant B");
        assertEquals(List.of("wait"), activities(inA.getId()), "tenant A untouched");
    }

    // FWT-029
    @Test
    void fwt029_defaultTenantChannelCorrelatesAndStartsWithoutTenant() {
        f.registry.deployEvent("", "{\"key\":\"order\",\"businessKeyField\":\"bk\"}", null);
        f.registry.deployEvent("", "{\"key\":\"kickoff\",\"businessKeyField\":\"bk\"}", null);
        f.registry.deployChannel("", webhookChannel("orders", "\"channelEventKeyDetection\":{\"jsonField\":\"type\"}"), null);
        f.deploy("wait.bpmn", catchModel("waiter", "order"));
        f.deploy("start.bpmn", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="k" name="kickoff"/>
                  <process id="started" isExecutable="true">
                    <startEvent id="s"><messageEventDefinition messageRef="k"/></startEvent>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <userTask id="t1"/>
                  </process>
                </definitions>
                """);
        String pid = f.engine.getRuntimeService().startProcessInstanceByKey("waiter", "k1").getId();
        assertEquals(Outcome.ACCEPTED, f.processor.receive("", "orders", "{\"id\":\"m1\",\"type\":\"order\",\"bk\":\"k1\"}",
                noHeaders(), null).outcome());
        assertEquals(Outcome.ACCEPTED, f.processor.receive("", "orders", "{\"id\":\"m2\",\"type\":\"kickoff\",\"bk\":\"k2\"}",
                noHeaders(), null).outcome());
        assertEquals(2, f.dispatch());
        assertEquals(List.of("after"), activities(pid), "correlation in the default tenant");
        List<ProcessInstance> starts = f.engine.getRuntimeService().createProcessInstanceQuery().processDefinitionKey("started")
                .list();
        // regression (found by this scenario): correlateAll also fired the message start, then the start step
        // started a second instance
        assertEquals(1, starts.size(), "exactly one instance per message-start event: " + starts);
        ProcessInstance started = starts.get(0);
        assertNotNull(started, "message start in the default tenant");
        assertEquals("k2", started.getBusinessKey());
        assertNull(started.getTenantId());
    }

    // ------------------------------------------------------------------ FW-30

    // FWT-030
    @Test
    void fwt030_reservedPayloadFieldIsRefusedAtDeploy() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> f.registry.deployEvent("acc",
                "{\"key\":\"order\",\"payload\":[{\"name\":\"_SANGAM_SKIP_EXPRESSION_ENABLED\",\"type\":\"boolean\"}]}", null));
        assertTrue(e.getMessage().contains("reserved"), e.getMessage());
        assertTrue(f.registry.event("acc", "order").isEmpty(), "nothing deployed");
    }

    // ------------------------------------------------------------------ FW-31

    // FWT-032
    @Test
    void fwt032_eventWithoutCorrelationValuesIsNotBroadcastAndExpires() throws Exception {
        f.registry.deployEvent("acc", "{\"key\":\"order\",\"ttlSeconds\":1,"
                + "\"correlationParameters\":[{\"name\":\"orderId\",\"type\":\"string\"}]}", null);
        f.registry.deployChannel("acc", webhookChannel("orders", fixedKey("order")), null);
        f.deploy("acc", "wait.bpmn", catchModel("waiter", "order"));
        for (int i = 0; i < 50; i++) {
            f.engine.getRuntimeService().createProcessInstanceByKey("waiter").processDefinitionTenantId("acc")
                    .setVariable("orderId", "o" + i).execute();
        }
        assertEquals(Outcome.ACCEPTED, f.processor.receive("acc", "orders", "{\"id\":\"m1\"}", noHeaders(), null).outcome());
        assertEquals(0, f.dispatch(), "not processed");
        assertEquals(Inbox.State.BUFFERED, f.inboxState("acc", "orders", "m1"));
        assertEquals(50, f.engine.getRuntimeService().createExecutionQuery().activityId("wait").count(), "0 correlated");
        assertEquals(0, f.engine.getTaskService().createTaskQuery().taskDefinitionKey("after").count());
        Thread.sleep(1_500);
        assertEquals(1, f.dispatchInbox.expire(), "expired at its TTL");
        assertEquals(Inbox.State.EXPIRED, f.inboxState("acc", "orders", "m1"));
        assertEquals(50, f.engine.getRuntimeService().createExecutionQuery().activityId("wait").count());
    }

    // ------------------------------------------------------------------ FW-32

    // FWT-033
    @Test
    void fwt033_uniqueStartWithoutReferenceIsRejectedAtIntake() {
        f.registry.deployEvent("acc", "{\"key\":\"apply\",\"startProcessKey\":\"application\",\"startUniqueReference\":\"ref\","
                + "\"correlationParameters\":[{\"name\":\"ref\",\"type\":\"string\"}]}", null);
        f.registry.deployChannel("acc", webhookChannel("applications", fixedKey("apply")), null);
        f.deploy("acc", "app.bpmn", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <process id="application" isExecutable="true">
                    <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="t1"/><userTask id="t1"/>
                  </process>
                </definitions>
                """);
        Result r = f.processor.receive("acc", "applications", "{\"id\":\"m1\"}", noHeaders(), null);
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals("INVALID_INPUT", r.code(), r.reason());
        assertEquals(0, inboxRows());
        f.dispatch();
        assertEquals(0, f.count("SELECT COUNT(*) FROM UNIQUE_START"), "no row at all, in particular no \"null\" row");
        assertEquals(0, f.count("SELECT COUNT(*) FROM UNIQUE_START WHERE business_key = 'null'"));
        assertEquals(0, f.engine.getRuntimeService().createProcessInstanceQuery().count());
        // control: with the value the event starts one instance and claims exactly that value
        assertEquals(Outcome.ACCEPTED, f.processor.receive("acc", "applications", "{\"id\":\"m2\",\"ref\":\"R-1\"}",
                noHeaders(), null).outcome());
        assertEquals(1, f.dispatch());
        assertEquals(List.of("R-1"), f.strings("SELECT business_key FROM UNIQUE_START"));
    }

    // ------------------------------------------------------------------ FW-42

    // FWT-047
    @Test
    void fwt047_xmlDeserializerChannelIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> f.registry.deployChannel("acc",
                webhookChannel("xmlin", fixedKey("order") + ",\"deserializerType\":\"xml\""), null));
        assertTrue(e.getMessage().contains("xml"), e.getMessage());
        assertTrue(f.registry.channel("acc", "xmlin").isEmpty(), "nothing deployed");
    }

    // ------------------------------------------------------------------ FW-43

    // FWT-048
    @Test
    void fwt048_earlyEventIsBufferedAndDeliveredWhenTheInstanceReachesTheCatch() {
        f.registry.deployEvent("acc", "{\"key\":\"order\",\"businessKeyField\":\"bk\"}", null);
        f.registry.deployChannel("acc", webhookChannel("orders", fixedKey("order")), null);
        f.deploy("acc", "early.bpmn", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="m1" name="order"/>
                  <process id="early" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="prepare"/>
                    <userTask id="prepare"/>
                    <sequenceFlow id="f1" sourceRef="prepare" targetRef="wait"/>
                    <intermediateCatchEvent id="wait"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                    <sequenceFlow id="f2" sourceRef="wait" targetRef="after"/>
                    <userTask id="after"/>
                  </process>
                </definitions>
                """);
        String pid = f.engine.getRuntimeService().createProcessInstanceByKey("early").processDefinitionTenantId("acc")
                .businessKey("k1").execute().getId();
        assertEquals(Outcome.ACCEPTED, f.processor.receive("acc", "orders", "{\"id\":\"m1\",\"bk\":\"k1\"}", noHeaders(), null)
                .outcome());
        assertEquals(0, f.dispatch());
        assertEquals(Inbox.State.BUFFERED, f.inboxState("acc", "orders", "m1"), "no subscription yet: buffered");
        f.engine.getTaskService().complete(f.engine.getTaskService().createTaskQuery().processInstanceId(pid).singleResult().getId());
        assertEquals(List.of("wait"), activities(pid));
        assertEquals(1, f.dispatch());
        assertEquals(Inbox.State.PROCESSED, f.inboxState("acc", "orders", "m1"));
        assertEquals(List.of("after"), activities(pid), "delivered once the instance reached the catch");
    }

    // ------------------------------------------------------------------ FW-03

    // FWT-007
    @Test
    void fwt007_messageCatchInParallelBranchIsDeliveredToThatInstanceOnly() {
        f.registry.deployEvent("acc", "{\"key\":\"approval\",\"businessKeyField\":\"bk\"}", null);
        f.registry.deployChannel("acc", webhookChannel("approvals", fixedKey("approval")), null);
        f.deploy("acc", "par.bpmn", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
                  <message id="m1" name="approval"/>
                  <process id="par" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f0" sourceRef="s" targetRef="fork"/>
                    <parallelGateway id="fork"/>
                    <sequenceFlow id="f1" sourceRef="fork" targetRef="wait"/>
                    <sequenceFlow id="f2" sourceRef="fork" targetRef="work"/>
                    <intermediateCatchEvent id="wait"><messageEventDefinition messageRef="m1"/></intermediateCatchEvent>
                    <sequenceFlow id="f3" sourceRef="wait" targetRef="approved"/>
                    <userTask id="approved"/>
                    <userTask id="work"/>
                  </process>
                </definitions>
                """);
        String p1 = f.engine.getRuntimeService().createProcessInstanceByKey("par").processDefinitionTenantId("acc")
                .businessKey("bk-1").execute().getId();
        String p2 = f.engine.getRuntimeService().createProcessInstanceByKey("par").processDefinitionTenantId("acc")
                .businessKey("bk-2").execute().getId();
        assertEquals(Outcome.ACCEPTED, f.processor.receive("acc", "approvals", "{\"id\":\"m1\",\"bk\":\"bk-1\"}", noHeaders(),
                null).outcome());
        assertEquals(1, f.dispatch());
        assertEquals(List.of("approved", "work"), activities(p1), "delivered to the branch of bk-1");
        assertEquals(List.of("wait", "work"), activities(p2), "the other instance is untouched");
        assertTrue(f.strings("SELECT detail FROM EVT_LOG WHERE direction = 'DISPATCH'").get(0).startsWith("correlated=1"));
    }
}
