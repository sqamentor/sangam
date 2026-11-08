// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.relay.PermanentPublishException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Outbound webhook publisher (R71 "webhook", VI.2.3; unordered by default, VI.2.3 rule 5).
 *
 * <ul>
 *   <li>CloudEvents 1.0 binary mode: {@code ce-*} headers, body = payload JSON. {@code ce-id} is the event id, the
 *       receiver's dedup key.</li>
 *   <li>Signature: {@code X-Sangam-Signature: t=<epochSeconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}, so the
 *       receiver can authenticate the call and reject replays outside its tolerance window (R27 callback rule, same
 *       scheme as {@code WebhookInboxReceiver}).</li>
 *   <li>Egress control and timeouts through {@link HttpTransport}; no in-transport retry (the relay owns retries).</li>
 *   <li>2xx → done; 410 Gone, 400, 404, 413, 415, 422 → {@link PermanentPublishException} (poison); anything else →
 *       retryable failure.</li>
 * </ul>
 */
public final class HttpWebhookPublisher implements Publisher {

    /** Endpoint of one destination. {@code secret} is the resolved signing secret (null = unsigned). */
    public record Endpoint(URI url, String secret, Map<String, String> headers) {
        public Endpoint {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private final HttpTransport transport;
    private final Function<String, Endpoint> endpoints;
    private final String source;

    /**
     * @param endpoints destination name → endpoint (null when unknown → poison)
     * @param source    CloudEvents {@code source}, e.g. {@code urn:sangam:<installation>}
     */
    public HttpWebhookPublisher(HttpTransport transport, Function<String, Endpoint> endpoints, String source) {
        this.transport = transport;
        this.endpoints = endpoints;
        this.source = source;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        Endpoint ep = endpoints.apply(destination);
        if (ep == null) {
            throw new PermanentPublishException("no webhook endpoint configured for " + destination);
        }
        byte[] body = (event.payload() == null ? "null" : event.payload()).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>(ep.headers());
        CloudEvents.attributes(source, destination, event).forEach((k, v) -> h.put("ce-" + k, v));
        h.put("Content-Type", "application/json");
        if (ep.secret() != null) {
            h.put("X-Sangam-Signature", signature(ep.secret(), System.currentTimeMillis() / 1000, body));
        }
        HttpTransport.Outcome o = transport.execute(event.tenantId(),
                new HttpTransport.Call("POST", ep.url(), h, body, null, true), 0);
        switch (o) {
            case HttpTransport.Outcome.Response r when r.is2xx() -> {
            }
            case HttpTransport.Outcome.Response r when r.status() == 400 || r.status() == 404 || r.status() == 410
                    || r.status() == 413 || r.status() == 415 || r.status() == 422 ->
                    throw new PermanentPublishException("webhook " + destination + " rejected event: HTTP " + r.status());
            case HttpTransport.Outcome.Response r -> throw new IllegalStateException("webhook HTTP " + r.status());
            case HttpTransport.Outcome.NoEffect ne -> {
                if (ne.reason().startsWith("EGRESS_DENIED")) {
                    throw new PermanentPublishException(ne.reason());
                }
                throw new IllegalStateException(ne.reason());
            }
            case HttpTransport.Outcome.Unknown u -> throw new IllegalStateException(u.reason());
        }
    }

    /** {@code t=<t>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. */
    public static String signature(String secret, long epochSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + epochSeconds + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
