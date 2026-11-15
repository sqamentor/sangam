// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.history;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.http.HttpRestConnector;
import org.sangam.integration.connector.s3.S3ObjectClient;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * M-057 external history projector, data-lake sink: each engine history event the outbox carries
 * ({@code sangam.history.*}) becomes one JSON object in an S3-compatible bucket (AWS S3, MinIO, Ceph RGW), written with
 * {@link S3ObjectClient} (egress policy, SigV4 by secret reference, no SDK). Lake engines (Spark, Trino, Athena,
 * DuckDB) read the prefix as newline-delimited JSON.
 *
 * <ul>
 *   <li><b>Idempotent by event id.</b> The object key is derived only from the event:
 *       {@code {prefix}/tenant={tenant}/date={yyyy-MM-dd}/{eventId}.json}. A relay redelivery after a lost
 *       acknowledgement writes the same key with the same bytes: an upsert, never a duplicate. The date is the
 *       millisecond time inside the time-ordered (version 7) outbox event id, so a redelivery on another day still hits
 *       the same partition; an id of another version goes to {@code date=undated} (never the wall clock).</li>
 *   <li><b>Document.</b> The same projection fields as the search-index sink ({@link SearchIndexPublisher#document}),
 *       one JSON object followed by a line feed.</li>
 *   <li><b>Outcomes.</b> 2xx done; 400, 403, 404 (no such bucket) and 411/413 permanent (configuration or document
 *       problem; dead letter); 429 or 503 with {@code Retry-After} deferred; anything else retried by the relay; an
 *       egress refusal, a credential that cannot be read or a tenant id that is not key-safe is permanent.</li>
 * </ul>
 */
public final class DataLakeHistoryPublisher implements Publisher {

    /** Outbox destination name of this sink (server wiring). */
    public static final String DESTINATION = "history-datalake";
    static final Set<Integer> PERMANENT = Set.of(400, 403, 404, 411, 413);
    static final String UNDATED = "undated";

    private final S3ObjectClient client;
    private final String prefix;

    /**
     * @param client the bucket client
     * @param prefix key prefix without leading or trailing {@code /} (S3 safe characters), or empty
     */
    public DataLakeHistoryPublisher(S3ObjectClient client, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        String p = prefix == null ? "" : prefix.trim();
        if (!p.isEmpty()) {
            S3ObjectClient.checkKey(p);
        }
        this.prefix = p;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        String key = key(prefix, event);
        byte[] body = (Json.write(SearchIndexPublisher.document(event)) + "\n").getBytes(StandardCharsets.UTF_8);
        HttpTransport.Outcome o;
        try {
            o = client.put(event.tenantId(), key, body, "application/x-ndjson", Map.of(), 0);
        } catch (HttpRestConnector.AuthUnavailableException e) {
            throw new PermanentPublishException("history data lake credential: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new PermanentPublishException("history data lake key for event " + event.eventId() + ": " + e.getMessage());
        }
        switch (o) {
            case HttpTransport.Outcome.Response r when r.is2xx() -> {
            }
            case HttpTransport.Outcome.Response r when PERMANENT.contains(r.status()) ->
                    throw new PermanentPublishException("history data lake rejected event " + event.eventId() + ": HTTP "
                            + r.status() + code(r));
            case HttpTransport.Outcome.Response r when (r.status() == 429 || r.status() == 503) && r.retryAfter().isPresent() -> {
                Duration d = r.retryAfter().get();
                throw new DeferPublishException("history data lake asked to retry after " + d.toSeconds() + " s",
                        d.compareTo(SearchIndexPublisher.MAX_RETRY_AFTER) > 0 ? SearchIndexPublisher.MAX_RETRY_AFTER : d);
            }
            case HttpTransport.Outcome.Response r -> throw new IllegalStateException("history data lake HTTP " + r.status()
                    + code(r));
            case HttpTransport.Outcome.NoEffect ne -> {
                if (ne.reason().startsWith("EGRESS_DENIED")) {
                    throw new PermanentPublishException(ne.reason());
                }
                throw new IllegalStateException(ne.reason());
            }
            case HttpTransport.Outcome.Unknown u -> throw new IllegalStateException(u.reason());
        }
    }

    /**
     * The object key of one event (see the class comment); refused when the tenant id is not key-safe. A tenant id is
     * one partition value: it holds neither the segment separator {@code /} nor the {@code name=value} separator
     * {@code =} (a value with {@code =} would read back as a different partition).
     */
    static String key(String prefix, Outbox.OutboxEvent e) throws PermanentPublishException {
        String tenant = e.tenantId() == null ? "" : e.tenantId();
        if (!S3ObjectClient.isKeySafe(tenant) || tenant.contains("/") || tenant.contains("=")) {
            throw new PermanentPublishException("tenant id is not usable in an object key: " + tenant);
        }
        String k = "tenant=" + tenant + "/date=" + date(e.eventId()) + "/" + e.eventId() + ".json";
        return prefix.isEmpty() ? k : prefix + "/" + k;
    }

    /** The UTC day inside a version 7 UUID (its first 48 bits are Unix milliseconds), else {@value #UNDATED}. */
    static String date(UUID id) {
        if (id == null || id.version() != 7) {
            return UNDATED;
        }
        long millis = id.getMostSignificantBits() >>> 16;
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static String code(HttpTransport.Outcome.Response r) {
        String c = S3ObjectClient.errorCode(r);
        return c == null ? "" : " (" + c + ")";
    }
}
