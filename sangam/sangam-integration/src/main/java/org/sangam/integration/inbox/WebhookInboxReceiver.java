// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.Inbox;
import org.sangam.integration.relay.BackpressureGate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Inbound webhook → inbox (R71 "webhook inbound", R27 "authenticate callbacks", R38). Framework-neutral: the REST layer
 * (WS-REST) maps {@code POST /api/v1/events/{channel}} to {@link #receive} and returns the {@link Reply} status.
 *
 * <ul>
 *   <li>Signature {@code X-Sangam-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>} (the same
 *       scheme {@code HttpWebhookPublisher} sends), constant-time compare, timestamp tolerance (default 5 min).</li>
 *   <li>Replay inside the tolerance window is harmless: the inbox key (tenant, source, messageId) dedups it, and the
 *       reply is 200 {@code duplicate}.</li>
 *   <li>Replies: 202 accepted, 200 duplicate, 401 bad signature/stale timestamp, 403 foreign tenant, 400 unmappable,
 *       503 back-pressure (with Retry-After). Stored before the reply, so a 2xx means "durably received".</li>
 * </ul>
 */
public final class WebhookInboxReceiver {

    public record Reply(int status, String code, Duration retryAfter) {
    }

    private final ChannelBinding binding;
    private final byte[] secret;
    private final Duration tolerance;
    private final InboundMapper<byte[]> mapper;
    private final InboxService inbox;

    public WebhookInboxReceiver(ChannelBinding binding, String secret, Duration tolerance, InboundMapper<byte[]> mapper,
                                InboxService inbox) {
        this.binding = binding;
        this.secret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance == null ? Duration.ofMinutes(5) : tolerance;
        this.mapper = mapper;
        this.inbox = inbox;
    }

    public Reply receive(Map<String, String> headers, byte[] body) {
        if (secret != null && !verify(header(headers, "X-Sangam-Signature"), body)) {
            return new Reply(401, "SIGNATURE_INVALID", null);
        }
        InboundMessage m;
        try {
            m = mapper.map(body, headers, new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return new Reply(400, "UNMAPPABLE_EVENT", null);
        }
        if (m == null) {
            return new Reply(202, "IGNORED", null);
        }
        try {
            Inbox.ReceiveResult r = inbox.receive(binding, m);
            return r == Inbox.ReceiveResult.DUPLICATE ? new Reply(200, "DUPLICATE", null) : new Reply(202, "ACCEPTED", null);
        } catch (PoisonInbound.PoisonMessageException e) {
            return new Reply(400, "PAYLOAD_NOT_JSON", null);   // the sender gets a definite refusal and stops retrying
        } catch (SecurityException e) {
            return new Reply(403, "TENANT_NOT_ALLOWED", null);
        } catch (BackpressureGate.BackpressureException e) {
            return new Reply(503, "BACKPRESSURE", e.retryAfter());
        }
    }

    boolean verify(String header, byte[] body) {
        // shared with the sender; accepts any of several v1 entries (secret rotation), constant-time compare
        return org.sangam.integration.publish.WebhookSignature.verify(secret, header, body, tolerance,
                System.currentTimeMillis() / 1000);
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
