// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.EventRegistry;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Inbound webhook channel (FLOWABLE-INTO-OPERATON FC-03, ARCHITECTURE §4 {@code POST /events/{channel}}): WS-REST maps the request onto
 * {@link #receive}. When the channel has {@code properties.secretRef}, the {@code X-Sangam-Signature: sha256=<hex>}
 * header must be the HMAC-SHA256 of the raw body (constant-time compare). The message id is the value at the
 * channel's {@code messageIdJsonPointer} when the body has one (the pipeline's rule for every adapter), else the
 * {@code Idempotency-Key} header, else {@code X-Sangam-Message-Id}.
 */
public class WebhookInboundEndpoint {

    private final EventRegistry registry;
    private final InboundEventProcessor processor;
    private final ChannelAdapters.Environment env;

    public WebhookInboundEndpoint(EventRegistry registry, InboundEventProcessor processor, ChannelAdapters.Environment env) {
        this.registry = registry;
        this.processor = processor;
        this.env = env;
    }

    public InboundEventProcessor.Result receive(String tenant, String channelKey, Map<String, String> headers, String body) {
        ChannelDefinition ch = registry.channel(tenant, channelKey).map(EventRegistry.DeployedChannel::definition)
                .orElseThrow(() -> new IllegalArgumentException("unknown channel " + channelKey));
        if (ch.direction() != ChannelDefinition.Direction.INBOUND || !"webhook".equals(ch.adapterType())) {
            throw new IllegalArgumentException("channel " + channelKey + " is not an inbound webhook channel");
        }
        if (body == null || body.isEmpty()) {
            throw new org.sangam.ports.support.PortsProblem("INVALID_INPUT", 422, "channel " + channelKey + ": empty body");
        }
        if (body.length() > processor.maxPayloadBytes()) {
            // checked before the HMAC so an oversized body costs no hashing (FW-02)
            throw org.sangam.ports.support.PortsProblem.tooLarge("PAYLOAD_TOO_LARGE", "webhook body", body.length(), processor.maxPayloadBytes());
        }
        Map<String, String> h = new java.util.HashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null) {
                    h.put(k.toLowerCase(Locale.ROOT), v);
                }
            });
        }
        String ref = ch.properties().get("secretRef");
        if (ref != null) {
            String secret = env.secret(ref).orElseThrow(() -> new IllegalStateException("secret " + ref + " not found"));
            String sig = h.get("x-sangam-signature");
            String expected = "sha256=" + hmacHex(secret, body);
            if (sig == null || !MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("channel " + channelKey + ": webhook signature invalid");
            }
        }
        String messageId = h.getOrDefault("idempotency-key", h.get("x-sangam-message-id"));
        return processor.receive(tenant, channelKey, body, h, messageId);
    }

    static String hmacHex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}
