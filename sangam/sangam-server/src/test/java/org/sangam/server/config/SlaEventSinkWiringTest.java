// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.sangam.ports.sla.SlaService;

/**
 * SL-146 server wiring: SLA warning and breach events go through the integration OutboxWriter (subscribed
 * destinations only); without sangam-integration nothing is written. Before the fix the server passed a raw Outbox
 * that wrote to the unsubscribed {@code notifications} destination, where the deliveries parked forever.
 */
class SlaEventSinkWiringTest {

    @Test
    void withoutAnOutboxWriterNothingIsWritten() {
        assertSame(SlaService.EventSink.NONE, PortsWiring.slaEvents(null));
    }

    @Test
    void withAnOutboxWriterTheEventsGoThroughItsSubscriptions() {
        org.sangam.integration.relay.OutboxWriter writer = org.mockito.Mockito.mock(
                org.sangam.integration.relay.OutboxWriter.class);
        SlaService.EventSink sink = PortsWiring.slaEvents(writer);
        assertNotSame(SlaService.EventSink.NONE, sink);
        java.util.Map<String, Object> payload = java.util.Map.of("slaKey", "S1");
        sink.emit(null, "T1", "root-1", "sangam.sla.breached", payload);
        org.mockito.Mockito.verify(writer).append(null, "T1", "root-1", "sangam.sla.breached", payload);
    }
}
