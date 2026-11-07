// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.integration.connector.Json;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.relay.BackpressureGate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP telemetry ingestion (N12), framework-neutral like {@code WebhookInboxReceiver}: the REST layer maps
 * {@code POST /api/v1/telemetry/{channel}} to {@link #receive} and returns the {@link Reply} status.
 *
 * <ul>
 *   <li>Signature {@code X-Sangam-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}
 *       (the webhook scheme), constant-time compare, timestamp tolerance (default 5 minutes). A null secret disables
 *       the check (only for channels authenticated by mTLS in front of this receiver).</li>
 *   <li>Body: one reading object or a JSON array of readings (at most {@code maxBatch}). Reading fields:
 *       {@code deviceId, sensor, value, unit, observedAt} (ISO-8601 or epoch ms){@code , quality, attributes} and an
 *       optional stable {@code messageId}. Optional header {@code X-Sangam-Tenant} is a tenant claim checked against
 *       the binding.</li>
 *   <li>The whole batch is validated (shape, device admission) before anything is stored; storing then proceeds in
 *       order. A 503 in the middle leaves the earlier readings stored; the client resends the whole batch and the
 *       inbox de-duplicates them.</li>
 *   <li>Replies: 202 accepted (at least one new reading), 200 all duplicates, 400 malformed / invalid reading,
 *       401 bad signature or stale timestamp, 403 unknown/disabled/foreign-channel device or foreign tenant,
 *       503 back-pressure (with Retry-After). A 2xx means "durably received".</li>
 * </ul>
 */
public final class TelemetryHttpReceiver {

    public static final String SIGNATURE_HEADER = "X-Sangam-Signature";
    public static final String TENANT_HEADER = "X-Sangam-Tenant";

    /**
     * @param index    index of the offending reading in the batch, or -1
     * @param accepted readings newly stored
     * @param duplicates readings already stored earlier
     */
    public record Reply(int status, String code, Duration retryAfter, int index, int accepted, int duplicates, String detail) {
    }

    private final TelemetryIngestor ingestor;
    private final TelemetryIngestor.Channel channel;
    private final byte[] secret;
    private final Duration tolerance;
    private final int maxBatch;

    public TelemetryHttpReceiver(TelemetryIngestor ingestor, TelemetryIngestor.Channel channel, String secret, Duration tolerance,
                                 int maxBatch) {
        this.ingestor = ingestor;
        this.channel = channel;
        this.secret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance == null ? Duration.ofMinutes(5) : tolerance;
        this.maxBatch = maxBatch <= 0 ? 500 : maxBatch;
    }

    private record Item(TelemetryReading reading, String messageId) {
    }

    public Reply receive(Map<String, String> headers, byte[] body) {
        Map<String, String> h = headers == null ? Map.of() : headers;
        if (secret != null && !verify(header(h, SIGNATURE_HEADER), body == null ? new byte[0] : body)) {
            return reply(401, "SIGNATURE_INVALID", -1, 0, 0, null);
        }
        JsonNode root = Json.tree(body);
        if (root == null || !(root.isObject() || root.isArray())) {
            return reply(400, "PAYLOAD_NOT_JSON", -1, 0, 0, "body must be a reading object or an array of readings");
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else {
            nodes.add(root);
        }
        if (nodes.isEmpty()) {
            return reply(400, "EMPTY_BATCH", -1, 0, 0, null);
        }
        if (nodes.size() > maxBatch) {
            return reply(400, "BATCH_TOO_LARGE", -1, 0, 0, "at most " + maxBatch + " readings per request");
        }
        String claimedTenant = header(h, TENANT_HEADER);
        String tenant;
        try {
            tenant = channel.binding().tenantFor(claimedTenant);
        } catch (SecurityException e) {
            return reply(403, "TENANT_NOT_ALLOWED", -1, 0, 0, null);
        }
        List<Item> items = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode n = nodes.get(i);
            if (!n.isObject()) {
                return reply(400, "INVALID_READING", i, 0, 0, "reading is not a JSON object");
            }
            try {
                Map<String, Object> m = Json.toMap(n);
                Object id = m.get("messageId");
                items.add(new Item(TelemetryReading.fromMap(m), id == null ? null : id.toString()));
            } catch (IllegalArgumentException e) {
                return reply(400, "INVALID_READING", i, 0, 0, e.getMessage());
            }
        }
        Set<String> checked = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String device = items.get(i).reading().deviceId();
            if (checked.add(device)) {
                TelemetryIngestor.Outcome refused = ingestor.admit(channel, tenant, device);
                if (refused != null) {
                    return reply(403, refused.status().name(), i, 0, 0, refused.detail());
                }
            }
        }
        int accepted = 0;
        int duplicates = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            TelemetryIngestor.Outcome o;
            try {
                o = ingestor.ingest(channel, it.reading(), it.messageId(), claimedTenant);
            } catch (BackpressureGate.BackpressureException e) {
                return new Reply(503, "BACKPRESSURE", e.retryAfter(), i, accepted, duplicates, null);
            } catch (SecurityException e) {
                return reply(403, "TENANT_NOT_ALLOWED", i, accepted, duplicates, null);
            } catch (PoisonInbound.PoisonMessageException e) {
                return reply(400, "PAYLOAD_NOT_JSON", i, accepted, duplicates, e.getMessage());
            }
            switch (o.status()) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                case INVALID -> {
                    return reply(400, "INVALID_READING", i, accepted, duplicates, o.detail());
                }
                default -> {
                    return reply(403, o.status().name(), i, accepted, duplicates, o.detail());
                }
            }
        }
        return accepted > 0 ? reply(202, "ACCEPTED", -1, accepted, duplicates, null)
                : reply(200, "DUPLICATE", -1, accepted, duplicates, null);
    }

    private static Reply reply(int status, String code, int index, int accepted, int duplicates, String detail) {
        return new Reply(status, code, null, index, accepted, duplicates, detail);
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
            return MessageDigest.isEqual(expected, v1.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
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
