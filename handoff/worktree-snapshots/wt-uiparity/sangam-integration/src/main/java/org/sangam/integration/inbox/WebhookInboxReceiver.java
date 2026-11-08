// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.Inbox;
import org.sangam.integration.relay.BackpressureGate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
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
        if (header == null) {
            return false;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv.length == 2 && kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - t) > tolerance.toSeconds()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
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
