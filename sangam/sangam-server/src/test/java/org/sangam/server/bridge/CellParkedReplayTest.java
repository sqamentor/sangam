// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sangam.core.layer.Inbox;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.rest.port.SangamPrincipal;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SL-142 / owner decision 61: a parked message travels from the old cell ({@link CellParkedReplayTransport}) to the
 * tenant's current cell ({@link CellParkedReplayInboxFilter}) keeping its ORIGINAL source, message id, event type and
 * correlation key, so the target inbox deduplicates a repeat; only a cell service principal (role XCELL) may send it.
 */
class CellParkedReplayTest {

    static final PoisonInbound.Parked PARKED = new PoisonInbound.Parked(7L, "T1", 3L, "kafka:payments", "m-1",
            "PaymentAck", "corr-1", "T1", "{\"a\":1}", "MOVED", "cell-b", "mv-1");

    @Test
    void theTransportPostsTheOriginalMessageThroughTheGateway() {
        CellParkedReplayTransport t = new CellParkedReplayTransport(URI.create("http://gateway:8000"), "svc-token", "cell-a");
        HttpRequest r = t.request(PARKED);
        assertEquals(URI.create("http://gateway:8000/api/v1/messages/replay"), r.uri());
        assertEquals("POST", r.method());
        assertEquals("T1", r.headers().firstValue("X-Sangam-Tenant").orElseThrow(), "the gateway routes by tenant");
        assertEquals("cell-a", r.headers().firstValue("X-Sangam-Source-Cell").orElseThrow());
        assertEquals("kafka:payments", r.headers().firstValue(CellParkedReplayInboxFilter.SOURCE_HEADER).orElseThrow());
        assertEquals("m-1", r.headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals("PaymentAck", r.headers().firstValue(CellParkedReplayInboxFilter.EVENT_TYPE_HEADER).orElseThrow());
        assertEquals("corr-1", r.headers().firstValue(CellParkedReplayInboxFilter.CORRELATION_HEADER).orElseThrow());
        assertEquals("Bearer svc-token", r.headers().firstValue("Authorization").orElseThrow());
        assertEquals(CellParkedReplayInboxFilter.PATH, CellParkedReplayTransport.PATH, "sender and receiver agree");
    }

    @Test
    void aParkedItemWithoutItsOriginalIdentityIsNeverSent() {
        CellParkedReplayTransport t = new CellParkedReplayTransport(URI.create("http://gateway:8000"), null, "cell-a");
        PoisonInbound.Parked noId = new PoisonInbound.Parked(7L, "T1", 3L, "kafka:payments", null, "PaymentAck", null,
                "T1", "{}", "MOVED", "cell-b", "mv-1");
        assertThrows(IllegalStateException.class, () -> t.request(noId));
        assertThrows(IllegalArgumentException.class, () -> new CellParkedReplayTransport(null, null, "cell-a"));
    }

    static MockHttpServletRequest replayRequest(Set<String> groups) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/messages/replay");
        req.setAttribute(SangamPrincipal.ATTRIBUTE, new SangamPrincipal(null, "svc-cell-a", groups, "token", Map.of()));
        req.addHeader("X-Sangam-Tenant", "T1");
        req.addHeader("X-Sangam-Source-Cell", "cell-a");
        req.addHeader(CellParkedReplayInboxFilter.SOURCE_HEADER, "kafka:payments");
        req.addHeader("Idempotency-Key", "m-1");
        req.addHeader(CellParkedReplayInboxFilter.EVENT_TYPE_HEADER, "PaymentAck");
        req.addHeader(CellParkedReplayInboxFilter.CORRELATION_HEADER, "corr-1");
        req.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        return req;
    }

    @Test
    void theReceiverStoresTheMessageUnderItsOriginalSourceIdAndType() throws Exception {
        InboxService inbox = mock(InboxService.class);
        when(inbox.receive(any(), any())).thenReturn(Inbox.ReceiveResult.ACCEPTED);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new CellParkedReplayInboxFilter(inbox).doFilter(replayRequest(Set.of("XCELL")), res, chain);

        ArgumentCaptor<ChannelBinding> binding = ArgumentCaptor.forClass(ChannelBinding.class);
        ArgumentCaptor<InboundMessage> message = ArgumentCaptor.forClass(InboundMessage.class);
        verify(inbox).receive(binding.capture(), message.capture());
        assertEquals("kafka:payments", binding.getValue().source(), "original source, not xcell:<cell>");
        assertEquals("T1", binding.getValue().tenantId());
        assertEquals("m-1", message.getValue().messageId());
        assertEquals("PaymentAck", message.getValue().eventType(), "the event type is kept, not sangam.xcell.message");
        assertEquals("corr-1", message.getValue().correlationKey());
        assertEquals("{\"a\":1}", message.getValue().payloadJson());
        assertEquals(202, res.getStatus());
        assertNull(chain.getRequest(), "answered by the filter");
    }

    @Test
    void aRepeatedReplayIsADuplicateNotASecondMessage() throws Exception {
        InboxService inbox = mock(InboxService.class);
        when(inbox.receive(any(), any())).thenReturn(Inbox.ReceiveResult.DUPLICATE);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new CellParkedReplayInboxFilter(inbox).doFilter(replayRequest(Set.of("XCELL")), res, new MockFilterChain());
        assertEquals(200, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"DUPLICATE\""));
    }

    @Test
    void onlyACellServicePrincipalMayReplay() throws Exception {
        InboxService inbox = mock(InboxService.class);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new CellParkedReplayInboxFilter(inbox).doFilter(replayRequest(Set.of("sangam-admin")), res, new MockFilterChain());
        assertEquals(403, res.getStatus());
        verify(inbox, never()).receive(any(), any());
    }

    @Test
    void theOriginalIdentityHeadersAreRequired() throws Exception {
        InboxService inbox = mock(InboxService.class);
        MockHttpServletRequest req = replayRequest(Set.of("XCELL"));
        req.removeHeader(CellParkedReplayInboxFilter.SOURCE_HEADER);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new CellParkedReplayInboxFilter(inbox).doFilter(req, res, new MockFilterChain());
        assertEquals(400, res.getStatus());
        verify(inbox, never()).receive(any(), any());
    }

    @Test
    void otherPathsAreNotTouched() throws Exception {
        InboxService inbox = mock(InboxService.class);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/messages");
        MockFilterChain chain = new MockFilterChain();
        new CellParkedReplayInboxFilter(inbox).doFilter(req, new MockHttpServletResponse(), chain);
        assertFalse(chain.getRequest() == null, "passed on");
        verify(inbox, never()).receive(any(), any());
    }
}
