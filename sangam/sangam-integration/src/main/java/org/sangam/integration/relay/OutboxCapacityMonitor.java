// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;

/**
 * Outbox capacity and drain measurement (SANGAM.md VI.2.4, R40, A-5).
 *
 * <ul>
 *   <li>{@code outboxMaxBytes} is the configured budget (default 20% of planned DB storage; sized as event rate ×
 *       average payload × allowed outage hours × 1.5).</li>
 *   <li>Used bytes are measured per dialect from the catalog (table + index + LOB segments of {@code OUTBOX} and
 *       {@code OUTBOX_DELIVERY}).</li>
 *   <li>Levels: ≥ 50% {@link Level#ALERT}; ≥ 80% {@link Level#BACKPRESSURE} (new starts and inbound events get
 *       {@code 503 BACKPRESSURE}, continuations still commit); ≥ 95% {@link Level#CRITICAL} (only completions and
 *       cancellations).</li>
 *   <li>Drain: arrival rate λ (outbox rows appended per second), service rate μ (open deliveries retired per second,
 *       derived from the open-count change plus arrivals, so it is correct with any number of relay nodes), backlog B
 *       (open deliveries) and drain time ≈ B / (μ − λ), reported only when μ &gt; λ (A-5).</li>
 * </ul>
 * {@link #sample()} is cheap enough to run every few seconds; {@link BackpressureGate} reads the last sample.
 */
public final class OutboxCapacityMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxCapacityMonitor.class);

    public enum Level { NORMAL, ALERT, BACKPRESSURE, CRITICAL }

    /** One measurement. {@code drainSeconds} is null when μ ≤ λ (the backlog does not drain). */
    public record Sample(Instant at, long usedBytes, long maxBytes, double usedRatio, Level level, long openDeliveries,
                         double arrivalPerSecond, double servicePerSecond, Double drainSeconds) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final long maxBytes;
    private final Duration window;
    private volatile Sample last;
    private volatile long prevOpen = -1;
    private volatile long prevAt;

    public OutboxCapacityMonitor(Dialect dialect, Transactions tx, long outboxMaxBytes, Duration rateWindow) {
        if (outboxMaxBytes <= 0) {
            throw new IllegalArgumentException("outboxMaxBytes must be configured (R40)");
        }
        this.dialect = dialect;
        this.tx = tx;
        this.maxBytes = outboxMaxBytes;
        this.window = rateWindow == null ? Duration.ofSeconds(60) : rateWindow;
        this.last = new Sample(Instant.now(), 0, maxBytes, 0, Level.NORMAL, 0, 0, 0, null);
    }

    public Sample last() {
        return last;
    }

    public Sample sample() {
        Sample s = tx.inTransaction(c -> {
            long used = usedBytes(c);
            long open = Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE state IN ('PENDING','CLAIMED','PARKED')",
                    rs -> rs.getLong(1)).orElse(0L);
            long appended = Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX WHERE created_at > " + dialect.nowPlusMillisParam(),
                    rs -> rs.getLong(1), -window.toMillis()).orElse(0L);
            long now = System.currentTimeMillis();
            double lambda = appended / (double) window.toSeconds();
            double mu = lambda;
            if (prevOpen >= 0 && now > prevAt) {
                double secs = (now - prevAt) / 1000.0;
                double arrivalsSince = lambda * secs;
                mu = Math.max(0, (prevOpen + arrivalsSince - open) / secs);
            }
            prevOpen = open;
            prevAt = now;
            double ratio = used / (double) maxBytes;
            Double drain = mu > lambda ? open / (mu - lambda) : (open == 0 ? 0.0 : null);
            return new Sample(Instant.now(), used, maxBytes, ratio, level(ratio), open, lambda, mu, drain);
        });
        if (s.level() != last.level()) {
            LOG.warn("outbox capacity level {} -> {} ({}% of {} bytes, backlog {}, drain {} s)", last.level(), s.level(),
                    Math.round(s.usedRatio() * 100), maxBytes, s.openDeliveries(), s.drainSeconds());
        }
        last = s;
        return s;
    }

    static Level level(double ratio) {
        if (ratio >= 0.95) {
            return Level.CRITICAL;
        }
        if (ratio >= 0.80) {
            return Level.BACKPRESSURE;
        }
        if (ratio >= 0.50) {
            return Level.ALERT;
        }
        return Level.NORMAL;
    }

    private long usedBytes(Connection c) {
        String sql = switch (dialect) {
            case POSTGRESQL -> "SELECT COALESCE(pg_total_relation_size(to_regclass('outbox')),0)"
                    + " + COALESCE(pg_total_relation_size(to_regclass('outbox_delivery')),0)";
            case MYSQL -> "SELECT COALESCE(SUM(data_length + index_length),0) FROM information_schema.TABLES"
                    + " WHERE table_schema = DATABASE() AND UPPER(table_name) IN ('OUTBOX','OUTBOX_DELIVERY')";
            case SQLSERVER -> "SELECT COALESCE(SUM(a.total_pages),0) * 8192 FROM sys.tables t"
                    + " JOIN sys.partitions p ON p.object_id = t.object_id"
                    + " JOIN sys.allocation_units a ON a.container_id = p.partition_id"
                    + " WHERE UPPER(t.name) IN ('OUTBOX','OUTBOX_DELIVERY')";
            case ORACLE -> "SELECT NVL(SUM(bytes),0) FROM user_segments WHERE segment_name IN ('OUTBOX','OUTBOX_DELIVERY')"
                    + " OR segment_name IN (SELECT segment_name FROM user_lobs WHERE table_name IN ('OUTBOX','OUTBOX_DELIVERY'))"
                    + " OR segment_name IN (SELECT index_name FROM user_indexes WHERE table_name IN ('OUTBOX','OUTBOX_DELIVERY'))";
        };
        try {
            return Jdbc.one(c, sql, rs -> rs.getLong(1)).orElse(0L);
        } catch (RuntimeException e) {
            // catalog not readable (permissions): estimate from open payloads
            return Jdbc.one(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY", rs -> rs.getLong(1)).orElse(0L) * 2048L;
        }
    }
}
