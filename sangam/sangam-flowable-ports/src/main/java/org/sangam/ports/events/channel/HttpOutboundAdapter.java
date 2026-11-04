// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.sangam.ports.events.model.ChannelDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Outbound HTTP/webhook channel: {@code POST url} with the JSON body. Headers: {@code Idempotency-Key} and
 * {@code X-Sangam-Message-Id} (= message id), {@code X-Sangam-Event-Key}, and {@code X-Sangam-Signature: sha256=<hex>}
 * (HMAC-SHA256 of the body) when the channel has {@code properties.secretRef}. Non-2xx responses throw.
 */
final class HttpOutboundAdapter implements ChannelAdapters.OutboundChannelAdapter {

    private final ChannelDefinition ch;
    private final URI uri;
    private final String secret;
    private final HttpClient client;
    private final Duration timeout;

    HttpOutboundAdapter(ChannelDefinition ch, ChannelAdapters.Environment env) {
        this.ch = ch;
        this.uri = URI.create(ch.url());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"true".equals(ch.property("allowPlainHttp", "false"))) {
            throw new IllegalArgumentException("channel " + ch.key() + ": https required (set allowPlainHttp=true for a lab)");
        }
        if (!env.egressAllowed(uri)) {
            throw new IllegalArgumentException("channel " + ch.key() + ": host " + uri.getHost() + " is not on the egress allow-list");
        }
        String ref = ch.properties().get("secretRef");
        this.secret = ref == null ? null : env.secret(ref)
                .orElseThrow(() -> new IllegalStateException("channel " + ch.key() + ": secret " + ref + " not found"));
        long ms;
        try {
            ms = Long.parseLong(ch.property("timeoutMs", "10000").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("channel " + ch.key() + ": timeoutMs is not a number");
        }
        // bounded: an unbounded or zero timeout would pin a relay thread on a dead endpoint
        this.timeout = Duration.ofMillis(Math.max(1_000L, Math.min(ms, 120_000L)));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", messageId)
                .header("X-Sangam-Message-Id", messageId)
                .header("X-Sangam-Event-Key", eventKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret != null) {
            b.header("X-Sangam-Signature", "sha256=" + WebhookInboundEndpoint.hmacHex(secret, body));
        }
        HttpResponse<Void> r = client.send(b.build(), HttpResponse.BodyHandlers.discarding());   // never buffer a remote body
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("channel " + ch.key() + ": HTTP " + r.statusCode());
        }
    }
}
