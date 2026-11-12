// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.InboundEventProcessor.Disposition;
import org.sangam.ports.events.InboundEventProcessor.Outcome;
import org.sangam.ports.events.InboundTenantAdmission.Verdict;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Json;

/**
 * C3-06 / SL-142 broker entry ({@link InboundEventProcessor#deliver}) and M-065 inbound CloudEvents: the tenant
 * admission is asked after the pipeline resolved the tenant and before the inbox write. ADMIT stores and acks; REDELIVER
 * (or a failing check) stores nothing and does not ack; PARKED acks without storing; a rejected payload is acked as
 * before (poison is never redelivered for ever); the HTTP entry {@code receive} does not ask the admission.
 */
class BrokerDeliveryH2Test {

    private PortsH2Engine h2;
    private InboundEventProcessor processor;
    private final AtomicReference<Verdict> verdict = new AtomicReference<>(Verdict.ADMIT);
    private final List<InboundTenantAdmission.Message> asked = new ArrayList<>();

    static final String RAW = "{\"id\":\"tx-1\",\"amount\":500,\"orderId\":\"O-1\"}";

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        Transactions tx = EngineTx.transactions(h2.engine);
        EventRegistry reg = new EventRegistry(Dialect.POSTGRESQL, tx, Duration.ZERO);
        reg.deployChannel("", "{\"key\":\"bank\",\"channelType\":\"webhook\",\"channelEventKeyDetection\":{\"fixedValue\":\"paid\"},"
                + "\"messageIdJsonPointer\":\"/id\"}", null);
        reg.deployChannel("", "{\"key\":\"ce\",\"channelType\":\"webhook\",\"deserializerType\":\"cloudevents\"}", null);
        reg.deployEvent("", "{\"key\":\"paid\",\"payload\":[{\"name\":\"amount\",\"type\":\"long\"}],"
                + "\"correlationParameters\":[{\"name\":\"orderId\",\"type\":\"string\",\"required\":true}]}", null);
        Inbox inbox = new Inbox(Dialect.POSTGRESQL, tx, "node-1", Duration.ofMinutes(1), Duration.ZERO, null);
        processor = new InboundEventProcessor(reg, inbox, new EventLog(Dialect.POSTGRESQL, false), tx, 4096)
                .withTenantAdmission((tenant, m) -> {
                    asked.add(m);
                    Verdict v = verdict.get();
                    if (v == null) {
                        throw new IllegalStateException("state store down");
                    }
                    return v;
                });
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    @Test
    void admittedMessagesAreStoredAndAcknowledged() {
        InboundEventProcessor.Delivery d = processor.deliver("", "bank", RAW, Map.of(), null);
        assertTrue(d.ack());
        assertEquals(Outcome.ACCEPTED, d.resultOrNull().outcome());
        assertEquals(Outcome.DUPLICATE, processor.deliver("", "bank", RAW, Map.of(), null).resultOrNull().outcome());
        InboundTenantAdmission.Message m = asked.get(0);
        assertEquals("bank", m.channelKey());
        assertEquals("tx-1", m.messageId());
        assertEquals("paid", m.eventKey());
        assertTrue(Json.tree(m.storedEventJson()).path("payload").path("amount").isNumber(), m.storedEventJson());
    }

    @Test
    void aTenantThatIsNotAdmittedIsRedeliveredAndNothingIsStored() {
        verdict.set(Verdict.REDELIVER);
        InboundEventProcessor.Delivery d = processor.deliver("", "bank", RAW, Map.of(), null);
        assertFalse(d.ack());
        assertEquals(Disposition.REDELIVER, d.disposition());
        assertNull(d.resultOrNull());
        assertTrue(d.reason().startsWith("TENANT_NOT_ADMITTED"), d.reason());
        verdict.set(Verdict.ADMIT);
        assertEquals(Outcome.ACCEPTED, processor.deliver("", "bank", RAW, Map.of(), null).resultOrNull().outcome(),
                "nothing was stored by the redelivered attempt");
    }

    @Test
    void aFailingCheckIsARedeliveryNeverADrop() {
        verdict.set(null);
        assertFalse(processor.deliver("", "bank", RAW, Map.of(), null).ack());
    }

    @Test
    void aParkedMessageIsAcknowledgedWithoutAnInboxRow() {
        verdict.set(Verdict.PARKED);
        InboundEventProcessor.Delivery d = processor.deliver("", "bank", RAW, Map.of(), null);
        assertTrue(d.ack());
        assertNull(d.resultOrNull());
        assertTrue(d.reason().startsWith("PARKED"), d.reason());
        verdict.set(Verdict.ADMIT);
        assertEquals(Outcome.ACCEPTED, processor.deliver("", "bank", RAW, Map.of(), null).resultOrNull().outcome());
    }

    @Test
    void poisonIsAcknowledgedAsRejectedWhateverTheAdmission() {
        verdict.set(Verdict.REDELIVER);
        InboundEventProcessor.Delivery d = processor.deliver("", "bank", "{not json", Map.of(), null);
        assertTrue(d.ack());
        assertEquals(Outcome.REJECTED, d.resultOrNull().outcome());
    }

    @Test
    void theHttpEntryDoesNotAskTheAdmission() {
        verdict.set(Verdict.REDELIVER);
        assertEquals(Outcome.ACCEPTED, processor.receive("", "bank", RAW, Map.of(), null).outcome());
        assertTrue(asked.isEmpty());
    }

    @Test
    void cloudEventsChannelsTakeTheKeyAndIdFromTheEvent() {
        String structured = "{\"specversion\":\"1.0\",\"id\":\"ce-1\",\"source\":\"urn:bank\",\"type\":\"paid\","
                + "\"datacontenttype\":\"application/json\",\"data\":{\"amount\":7,\"orderId\":\"O-2\"}}";
        InboundEventProcessor.Delivery d = processor.deliver("", "ce", structured, Map.of(), null);
        assertEquals(Outcome.ACCEPTED, d.resultOrNull().outcome(), String.valueOf(d));
        assertEquals("paid", d.resultOrNull().eventKey());
        assertEquals("ce-1", d.resultOrNull().messageId());

        Map<String, String> binary = Map.of("ce_specversion", "1.0", "ce_id", "ce-2", "ce_source", "urn:bank", "ce_type", "paid",
                "content-type", "application/json");
        InboundEventProcessor.Delivery b = processor.deliver("", "ce", "{\"amount\":8,\"orderId\":\"O-3\"}", binary, null);
        assertEquals(Outcome.ACCEPTED, b.resultOrNull().outcome(), String.valueOf(b));
        assertEquals("ce-2", b.resultOrNull().messageId());

        assertEquals(Outcome.REJECTED, processor.deliver("", "ce", "{\"amount\":1}", Map.of(), null).resultOrNull().outcome(),
                "not a CloudEvent on a CloudEvents channel");
    }
}
