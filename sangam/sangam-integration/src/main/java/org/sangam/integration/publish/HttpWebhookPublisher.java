// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Outbound webhook publisher (R71 "webhook", VI.2.3, SL-39). One {@link #publish} = one HTTP attempt; retries,
 * backoff with jitter, dead letters and per-key ordering belong to the outbox relay ({@code OutboxRelayService} on
 * core {@code Relay}): register the destination as {@code Outbox.Destination.ordered(name)} for per-root ordering.
 *
 * <ul>
 *   <li><b>Payload.</b> CloudEvents 1.0 binary mode: {@code ce-*} headers, body = payload JSON.</li>
 *   <li><b>Idempotency.</b> {@code Idempotency-Key} and {@code ce-id} = the outbox event id, identical on every retry,
 *       so the receiver deduplicates redeliveries (at-least-once delivery; Stripe's guidance: log processed event ids).</li>
 *   <li><b>Signature.</b> {@link WebhookSignature}: {@code X-Sangam-Signature: t=<epochSeconds>,v1=<hex
 *       HMAC-SHA256(secret, t + "." + body)>}, computed per attempt (fresh timestamp, so a delayed retry is still inside
 *       the receiver's tolerance). The per-subscription secret is a {@code secret://} reference resolved through the
 *       {@link SecretResolver} port on every attempt (rotation takes effect without a restart); a comma-separated
 *       value signs with each secret (rotation overlap). Unsigned endpoints are refused.</li>
 *   <li><b>Egress / SSRF.</b> Through {@link HttpTransport}: per-tenant allow-list, private/link-local/metadata
 *       addresses blocked, every redirect hop re-checked. A refusal is permanent (dead-lettered), never retried.</li>
 *   <li><b>Outcome.</b> 2xx done; 400/404/413/415/422 permanent (the event is the problem); 410 Gone permanent and the
 *       destination is disabled; 429/503 with {@code Retry-After} deferred by that delay without consuming an attempt
 *       (still counted as a failure); other answers and transport failures retryable.</li>
 *   <li><b>Auto-disable.</b> With a {@link WebhookHealth}: failures count towards {@code disableAfter}; a disabled
 *       destination's deliveries are held (deferred, no attempt consumed, order kept) for {@code holdWhileDisabled}
 *       after the disable, then dead-lettered. {@link WebhookHealth#enable} is the re-enable path.</li>
 * </ul>
 */
public final class HttpWebhookPublisher implements Publisher {

    /** Endpoint of one subscription. {@code secretRef} is a {@code secret://} reference (a literal only in dev). */
    public record Endpoint(URI url, String secretRef, Map<String, String> headers) {
        public Endpoint {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Set<Integer> PERMANENT = Set.of(400, 404, 410, 413, 415, 422);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);
    private static final Duration HOLD_RECHECK = Duration.ofMinutes(1);

    private final HttpTransport transport;
    private final Function<String, Endpoint> endpoints;
    private final SecretResolver secrets;
    private final WebhookHealth health;
    private final Duration holdWhileDisabled;
    private final String source;
    private final Clock clock;

    /**
     * @param endpoints         destination name → endpoint (null when unknown → poison)
     * @param secrets           secret port resolving {@link Endpoint#secretRef()}
     * @param healthOrNull      destination health (auto-disable); null = never disabled
     * @param holdWhileDisabled how long deliveries of a disabled destination are held before they are dead-lettered
     *                          (default 72 h, the length of Stripe's automatic retry period)
     * @param source            CloudEvents {@code source}, e.g. {@code urn:sangam:<installation>}
     */
    public HttpWebhookPublisher(HttpTransport transport, Function<String, Endpoint> endpoints, SecretResolver secrets,
                                WebhookHealth healthOrNull, Duration holdWhileDisabled, String source, Clock clock) {
        this.transport = transport;
        this.endpoints = endpoints;
        this.secrets = secrets;
        this.health = healthOrNull;
        this.holdWhileDisabled = holdWhileDisabled == null ? Duration.ofHours(72) : holdWhileDisabled;
        this.source = source;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        Endpoint ep = endpoints.apply(destination);
        if (ep == null) {
            throw new PermanentPublishException("no webhook endpoint configured for " + destination);
        }
        if (ep.secretRef() == null || ep.secretRef().isBlank()) {
            throw new PermanentPublishException("webhook " + destination + " has no signing secret; unsigned webhooks are refused");
        }
        holdIfDisabled(destination);
        byte[] body = (event.payload() == null ? "null" : event.payload()).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>(ep.headers());
        CloudEvents.attributes(source, destination, event).forEach((k, v) -> h.put("ce-" + k, v));
        h.put("Content-Type", "application/json");
        h.put(IDEMPOTENCY_HEADER, event.eventId().toString());
        h.put(WebhookSignature.HEADER, WebhookSignature.header(clock.millis() / 1000, body, secretsOf(ep.secretRef())));
        HttpTransport.Outcome o = transport.execute(event.tenantId(),
                new HttpTransport.Call("POST", ep.url(), h, body, null, true), 0);
        switch (o) {
            case HttpTransport.Outcome.Response r when r.is2xx() -> success(destination);
            case HttpTransport.Outcome.Response r when r.status() == 410 -> {
                if (health != null) {
                    health.disable(event.tenantId(), destination, "receiver", "receiver answered 410 Gone");
                }
                throw new PermanentPublishException("webhook " + destination + " is gone: HTTP 410");
            }
            case HttpTransport.Outcome.Response r when PERMANENT.contains(r.status()) -> {
                success(destination);   // the receiver is up and answered; the event is the problem
                throw new PermanentPublishException("webhook " + destination + " rejected event: HTTP " + r.status());
            }
            case HttpTransport.Outcome.Response r when (r.status() == 429 || r.status() == 503) && r.retryAfter().isPresent() -> {
                failure(event, destination, "HTTP " + r.status());
                Duration d = r.retryAfter().get();
                throw new DeferPublishException("webhook " + destination + " asked to retry after " + d.toSeconds() + " s",
                        d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d);
            }
            case HttpTransport.Outcome.Response r -> {
                failure(event, destination, "HTTP " + r.status());
                throw new IllegalStateException("webhook HTTP " + r.status());
            }
            case HttpTransport.Outcome.NoEffect ne -> {
                if (ne.reason().startsWith("EGRESS_DENIED")) {
                    throw new PermanentPublishException(ne.reason());
                }
                failure(event, destination, ne.reason());
                throw new IllegalStateException(ne.reason());
            }
            case HttpTransport.Outcome.Unknown u -> {
                failure(event, destination, u.reason());
                throw new IllegalStateException(u.reason());
            }
        }
    }

    private void holdIfDisabled(String destination) throws PermanentPublishException {
        if (health == null) {
            return;
        }
        WebhookHealth.Status s = health.status(destination).orElse(null);
        if (s == null || s.state() != WebhookHealth.State.DISABLED) {
            return;
        }
        Instant since = s.disabledAt() == null ? clock.instant() : s.disabledAt();
        Duration left = Duration.between(clock.instant(), since.plus(holdWhileDisabled));
        if (left.isNegative() || left.isZero()) {
            throw new PermanentPublishException("WEBHOOK_DISABLED: " + destination + " disabled since " + since
                    + " (hold of " + holdWhileDisabled + " elapsed)");
        }
        throw new DeferPublishException("WEBHOOK_DISABLED: " + destination + " held until re-enabled",
                left.compareTo(HOLD_RECHECK) < 0 ? left : HOLD_RECHECK);
    }

    private List<String> secretsOf(String ref) {
        String value = secrets.require(ref);
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("signing secret " + ref + " is empty");
        }
        return out;
    }

    private void success(String destination) {
        if (health != null) {
            health.success(destination);
        }
    }

    private void failure(Outbox.OutboxEvent event, String destination, String error) {
        if (health != null) {
            health.failure(event.tenantId(), destination, error);
        }
    }

    /** {@code t=<t>,v1=<hex HMAC-SHA256(secret, t + "." + body)>} (kept for callers of the earlier API). */
    public static String signature(String secret, long epochSeconds, byte[] body) {
        return WebhookSignature.header(epochSeconds, body, List.of(secret));
    }
}
