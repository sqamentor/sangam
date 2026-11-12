// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.sangam.cell.rollup.MiniJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * State-level roll-up tier, delivery side (M-063, SCALE-ARCHITECTURE.md 8.4 a): posts the queued explicit metric
 * events of {@code RU_UPSTREAM_OUTBOX} to the upper roll-up's {@code POST /api/v1/ingest} as one JSON array, with the
 * relay's rules (at least once; the upper tier deduplicates on the event id):
 * <ul>
 *   <li>2xx with {@code rejected = 0}: every event is projected or a duplicate; the rows are deleted;</li>
 *   <li>2xx with rejections, or 400/413/415/422: the events are re-sent one by one to find the poison ones; a
 *       single event rejected that way is parked ({@code parked = 1}, alerted through
 *       {@code sangam.rollup.upstream.events{result="parked"}}), never dropped;</li>
 *   <li>any other status or an I/O error: the rows stay and are retried after 2^attempts seconds (at most 5
 *       minutes).</li>
 * </ul>
 */
@Component
public class RollupUpstreamSender {

    private static final Logger LOG = LoggerFactory.getLogger(RollupUpstreamSender.class);
    static final long MAX_BACKOFF_MS = 300_000L;

    /** One HTTP call to the upper tier (a seam for tests). */
    @FunctionalInterface
    public interface UpstreamTransport {
        Response post(String json) throws IOException, InterruptedException;

        record Response(int status, String body) {
        }
    }

    private final JdbcTemplate jdbc;
    private final RollupProperties props;
    private final RollupMetrics metrics;
    private final UpstreamTransport transport;

    @Autowired
    public RollupUpstreamSender(JdbcTemplate jdbc, RollupProperties props, RollupMetrics metrics) {
        this(jdbc, props, metrics, http(props.upstream()));
    }

    RollupUpstreamSender(JdbcTemplate jdbc, RollupProperties props, RollupMetrics metrics, UpstreamTransport transport) {
        this.jdbc = jdbc;
        this.props = props;
        this.metrics = metrics;
        this.transport = transport;
    }

    static UpstreamTransport http(RollupProperties.Upstream up) {
        if (!up.enabled()) {
            return json -> {
                throw new IOException("sangam.rollup.upstream.url is not set");
            };
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(up.timeout()).build();
        URI uri = URI.create(up.url());
        return json -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(up.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (up.token() != null && !up.token().isBlank()) {
                b.header("Authorization", "Bearer " + up.token());
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new UpstreamTransport.Response(r.statusCode(), r.body());
        };
    }

    @Scheduled(fixedDelayString = "${sangam.rollup.upstream.send-interval:PT5S}",
            initialDelayString = "${sangam.rollup.upstream.send-interval:PT5S}")
    public void scheduled() {
        if (props.upstream().enabled()) {
            while (sendDue() == props.upstream().maxBatch()) {
                // a full batch went out: more may be due
            }
        }
    }

    /** Sends one batch of due events; returns the number of events attempted. */
    public int sendDue() {
        long now = System.currentTimeMillis();
        JdbcTemplate limited = new JdbcTemplate(jdbc.getDataSource());
        limited.setMaxRows(props.upstream().maxBatch());
        List<Row> rows = limited.query("SELECT event_id, payload, attempts FROM RU_UPSTREAM_OUTBOX"
                        + " WHERE parked = 0 AND next_attempt_ms <= ? ORDER BY next_attempt_ms, created_at_ms",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3)), now);
        if (rows.isEmpty()) {
            return 0;
        }
        long t0 = System.nanoTime();
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            body.append(i == 0 ? "" : ",").append(rows.get(i).payload());
        }
        body.append(']');
        try {
            UpstreamTransport.Response r = transport.post(body.toString());
            if (r.status() >= 200 && r.status() < 300 && rejected(r.body()) == 0) {
                rows.forEach(this::delete);
                metrics.upstream("sent", rows.size());
            } else if (r.status() >= 200 && r.status() < 300 || permanent(r.status())) {
                rows.forEach(row -> sendOne(row, now));
            } else {
                rows.forEach(row -> retryLater(row, now, "HTTP " + r.status()));
            }
        } catch (IOException e) {
            rows.forEach(row -> retryLater(row, now, e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rows.forEach(row -> retryLater(row, now, "interrupted"));
        } finally {
            metrics.send(System.nanoTime() - t0);
        }
        return rows.size();
    }

    record Row(String eventId, String payload, int attempts) {
    }

    private void sendOne(Row row, long now) {
        try {
            UpstreamTransport.Response r = transport.post("[" + row.payload() + "]");
            int rejected = r.status() >= 200 && r.status() < 300 ? rejected(r.body()) : -1;
            if (rejected == 0) {
                delete(row);
                metrics.upstream("sent", 1);
            } else if (rejected > 0 || permanent(r.status())) {
                jdbc.update("UPDATE RU_UPSTREAM_OUTBOX SET parked = 1, last_error = ? WHERE event_id = ?",
                        clip("rejected by the upper tier: HTTP " + r.status() + " " + r.body()), row.eventId());
                metrics.upstream("parked", 1);
                LOG.error("Roll-up tier event {} parked: the upper tier rejected it (HTTP {})", row.eventId(), r.status());
            } else {
                retryLater(row, now, "HTTP " + r.status());
            }
        } catch (IOException e) {
            retryLater(row, now, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retryLater(row, now, "interrupted");
        }
    }

    private void delete(Row row) {
        jdbc.update("DELETE FROM RU_UPSTREAM_OUTBOX WHERE event_id = ?", row.eventId());
    }

    private void retryLater(Row row, long now, String error) {
        jdbc.update("UPDATE RU_UPSTREAM_OUTBOX SET attempts = attempts + 1, next_attempt_ms = ?, last_error = ?"
                + " WHERE event_id = ?", now + backoffMs(row.attempts()), clip(error), row.eventId());
        metrics.upstream("retry", 1);
    }

    static long backoffMs(int attempts) {
        return attempts >= 18 ? MAX_BACKOFF_MS : Math.min(MAX_BACKOFF_MS, 1000L << attempts);
    }

    /**
     * An answer about the event itself, which no retry can fix: 400, 413, 415, 422. Every other status is retried,
     * including 401/403/404: those are this instance's configuration (token, URL), and parking every event for them
     * would stall the tier until an operator replays the parked rows.
     */
    static boolean permanent(int status) {
        return status == 400 || status == 413 || status == 415 || status == 422;
    }

    /** The {@code rejected} count of an ingest answer; an answer without it counts as one rejection (look closer). */
    static int rejected(String body) {
        try {
            Object v = MiniJson.readObject(body == null ? "" : body).get("rejected");
            return v instanceof Long l ? l.intValue() : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static String clip(String s) {
        String t = s == null ? "" : s;
        return t.length() > 1000 ? t.substring(0, 1000) : t;
    }

    /** Exposed for the admin view and tests: queued, due-later and parked events. */
    public Map<String, Long> queue() {
        Long queued = jdbc.queryForObject("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE parked = 0", Long.class);
        Long parked = jdbc.queryForObject("SELECT COUNT(*) FROM RU_UPSTREAM_OUTBOX WHERE parked = 1", Long.class);
        return Map.of("queued", queued == null ? 0L : queued, "parked", parked == null ? 0L : parked);
    }
}
