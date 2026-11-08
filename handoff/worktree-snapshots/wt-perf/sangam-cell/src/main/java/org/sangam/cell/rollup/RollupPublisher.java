// SPDX-License-Identifier: Apache-2.0
package org.sangam.cell.rollup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.sangam.cell.id.CellId;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;

/**
 * Outbox relay publisher for the national roll-up stream, destination {@value #DESTINATION} (IR-3).
 *
 * <p>At-least-once (relay semantics); the roll-up deduplicates on the CloudEvents {@code id}. A 4xx answer (other
 * than 408/429) is a poison event and raises {@link PoisonEventException}; 5xx and I/O errors are retryable.
 *
 * <p>Data residency (SCALE-ARCHITECTURE.md section 9): only allow-listed payload fields leave the cell
 * ({@link #DEFAULT_FIELDS}); no personal data, no free text.
 */
public final class RollupPublisher implements Publisher {

    public static final String DESTINATION = "rollup:national";

    public static final Set<String> DEFAULT_FIELDS = Set.of("eventType", "processDefinitionKey", "taskDefinitionKey",
            "activityType", "incidentType", "durationMs", "outcome", "amountMinor", "currency", "category",
            "targetTenant", "operationId");

    private final CellId cell;
    private final URI ingestUri;
    private final TenantPathResolver paths;
    private final String bearerToken;
    private final Set<String> allowedFields;
    private final HttpClient http;

    public RollupPublisher(CellId cell, URI ingestUri, TenantPathResolver paths, String bearerToken,
                           Set<String> allowedFields) {
        this.cell = cell;
        this.ingestUri = ingestUri;
        this.paths = paths;
        this.bearerToken = bearerToken;
        this.allowedFields = allowedFields == null || allowedFields.isEmpty() ? DEFAULT_FIELDS : Set.copyOf(allowedFields);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * Event types forwarded nationally. Everything else (activity-level history, variable updates) is acknowledged
     * without sending: the national read model needs only these, and they bound the national stream to roughly
     * 3 events per workflow (start, task completions, end) instead of every history event.
     */
    public static final Set<String> DEFAULT_EVENT_TYPES = Set.of("sangam.history.process.start",
            "sangam.history.process.end", "sangam.history.task.complete", "sangam.history.incident.create",
            "sangam.xcell.sent", "sangam.xcell.received", "sangam.rollup.metric");

    private Set<String> eventTypes = DEFAULT_EVENT_TYPES;

    /** Replaces the forwarded event-type allow-list ({@code *} forwards everything). */
    public RollupPublisher withEventTypes(Set<String> types) {
        this.eventTypes = types == null || types.isEmpty() ? DEFAULT_EVENT_TYPES : Set.copyOf(types);
        return this;
    }

    public boolean forwards(String eventType) {
        return eventTypes.contains("*") || eventTypes.contains(eventType);
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        if (!forwards(event.eventType())) {
            return;
        }
        String json = envelope(event).toJson();
        HttpRequest.Builder b = HttpRequest.newBuilder(ingestUri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/cloudevents+json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int sc = r.statusCode();
        if (sc >= 200 && sc < 300) {
            return;
        }
        if (sc >= 400 && sc < 500 && sc != 408 && sc != 429) {
            throw new PoisonEventException("roll-up rejected event " + event.eventId() + ": HTTP " + sc);
        }
        throw new IOException("roll-up unavailable: HTTP " + sc);
    }

    public RollupEnvelope envelope(Outbox.OutboxEvent event) {
        String path = paths.pathOf(event.tenantId()).orElse(event.tenantId());
        Map<String, Object> data = new LinkedHashMap<>();
        String operationId = null;
        if (event.payload() != null && !event.payload().isBlank()) {
            Object parsed = MiniJson.read(event.payload());
            if (parsed instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (allowedFields.contains(k)) {
                        data.put(k, e.getValue());
                    }
                }
                Object op = m.get("operationId");
                operationId = op == null ? null : op.toString();
            }
        }
        return RollupEnvelope.of(cell.value(), path, event, Instant.now().toString(), data, operationId);
    }

    /** A rejection no retry can fix; the integrator maps it to PermanentPublishException (IR-3). */
    public static final class PoisonEventException extends RuntimeException {
        public PoisonEventException(String message) {
            super(message);
        }
    }
}
