// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.metrics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.operaton.bpm.engine.RuntimeService;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.ReconciliationQueue;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.sangam.integration.relay.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Operational gauges for the deploy dashboard (deploy README section 10). Meter names use dots; Prometheus renders
 * them as {@code sangam_outbox_pending}, {@code sangam_outbox_oldest_age_seconds}, {@code sangam_reconciliation_items{state}}
 * and {@code sangam_incidents_open}. Values are read at most every 15 s (one cached snapshot); a missing table or a
 * failing query gives {@code NaN} instead of an error.
 * <p>
 * "Pending" outbox deliveries are those not yet {@code DONE}: states {@code PENDING}, {@code CLAIMED}, {@code PARKED}
 * (sangam-core {@code Relay}).
 * <p>
 * M-091 adds three gauges for the alert rules:
 * <ul>
 *   <li>{@code sangam.outbox.parked} ({@code sangam_outbox_parked}): deliveries in state {@code PARKED} only (ordered
 *       poison or exhausted deliveries that block their root's line until an operator repairs them). Database read
 *       in the cached snapshot, like the gauges above: every pod reports the same value, so rules aggregate with
 *       {@code max()}.</li>
 *   <li>{@code sangam.outbox.capacity.used.ratio} ({@code sangam_outbox_capacity_used_ratio}): the last
 *       {@code OutboxCapacityMonitor} sample's {@code usedRatio} (used bytes / {@code outboxMaxBytes}, R40; 0.5 is the
 *       ALERT level). Every pod samples the same database catalog on its own schedule, so it is a database value
 *       repeated per pod: aggregate with {@code max()}. NaN without the integration module.</li>
 *   <li>{@code sangam.relay.last.cycle.age} ({@code sangam_relay_last_cycle_age_seconds}): seconds since this pod's
 *       outbox relay last completed a cycle, or since it started when no cycle has completed yet. Per pod (every pod
 *       runs its own relay loop): alert per instance. NaN when the relay is absent or not started
 *       ({@code sangam.integration.background.enabled=false}).</li>
 * </ul>
 */
@Component
public class SangamMetrics implements MeterBinder {

    private static final Logger LOG = LoggerFactory.getLogger(SangamMetrics.class);
    static final Duration CACHE = Duration.ofSeconds(15);
    static final String NOT_DELIVERED = "('PENDING','CLAIMED','PARKED')";

    private final DataSource dataSource;
    private final ObjectProvider<RuntimeService> runtimeService;
    private final ObjectProvider<OutboxCapacityMonitor> capacity;
    private final ObjectProvider<OutboxRelayService> relay;
    private volatile Snapshot snapshot;

    record Snapshot(Instant takenAt, double outboxPending, double outboxOldestAgeSeconds,
                    Map<String, Double> reconciliationByState, double incidentsOpen, double outboxParked) {
    }

    public SangamMetrics(DataSource dataSource, ObjectProvider<RuntimeService> runtimeService,
                         ObjectProvider<OutboxCapacityMonitor> capacity, ObjectProvider<OutboxRelayService> relay) {
        this.dataSource = dataSource;
        this.runtimeService = runtimeService;
        this.capacity = capacity;
        this.relay = relay;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sangam.outbox.pending", this, m -> m.current().outboxPending())
                .description("Outbox deliveries not yet delivered (PENDING, CLAIMED, PARKED)")
                .register(registry);
        Gauge.builder("sangam.outbox.oldest.age", this, m -> m.current().outboxOldestAgeSeconds())
                .description("Age of the oldest undelivered outbox event")
                .baseUnit("seconds")
                .register(registry);
        for (ReconciliationQueue.State state : ReconciliationQueue.State.values()) {
            Gauge.builder("sangam.reconciliation.items", this,
                            m -> m.current().reconciliationByState().getOrDefault(state.name(), Double.NaN))
                    .description("Reconciliation items by state")
                    .tag("state", state.name())
                    .register(registry);
        }
        Gauge.builder("sangam.incidents.open", this, m -> m.current().incidentsOpen())
                .description("Open Operaton incidents")
                .register(registry);
        Gauge.builder("sangam.outbox.parked", this, m -> m.current().outboxParked())
                .description("Outbox deliveries PARKED (blocking their line until repaired); database value, same on every pod")
                .register(registry);
        Gauge.builder("sangam.outbox.capacity.used.ratio", this, SangamMetrics::capacityUsedRatio)
                .description("Outbox used bytes / outboxMaxBytes from the last capacity sample (R40; 0.5 = ALERT); database value")
                .register(registry);
        Gauge.builder("sangam.relay.last.cycle.age", this, SangamMetrics::relayLastCycleAgeSeconds)
                .description("Seconds since this pod's outbox relay last completed a cycle (since start if none yet); per pod")
                .baseUnit("seconds")
                .register(registry);
    }

    /** M-091: the last capacity sample's used ratio; NaN without a capacity monitor. */
    double capacityUsedRatio() {
        OutboxCapacityMonitor m = capacity.getIfAvailable();
        if (m == null) {
            return Double.NaN;
        }
        OutboxCapacityMonitor.Sample s = m.last();
        return s == null ? Double.NaN : s.usedRatio();
    }

    /** M-091 heartbeat: seconds since the relay's last completed cycle (or its start); NaN when absent or not started. */
    double relayLastCycleAgeSeconds() {
        OutboxRelayService r = relay.getIfAvailable();
        if (r == null) {
            return Double.NaN;
        }
        Instant since = r.lastCycleCompletedAt().or(r::startedAt).orElse(null);
        return since == null ? Double.NaN : Math.max(0d, Duration.between(since, Instant.now()).toMillis() / 1000d);
    }

    Snapshot current() {
        Snapshot s = snapshot;
        if (s == null || s.takenAt().plus(CACHE).isBefore(Instant.now())) {
            synchronized (this) {
                s = snapshot;
                if (s == null || s.takenAt().plus(CACHE).isBefore(Instant.now())) {
                    s = take();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    private Snapshot take() {
        double pending = Double.NaN;
        double oldest = Double.NaN;
        double parked = Double.NaN;
        Map<String, Double> recon = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            pending = scalar(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE state IN " + NOT_DELIVERED);
            parked = scalar(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE state = 'PARKED'");
            oldest = oldestAge(c);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT state, COUNT(*) FROM RECONCILIATION_ITEM GROUP BY state");
                 ResultSet rs = ps.executeQuery()) {
                for (ReconciliationQueue.State st : ReconciliationQueue.State.values()) {
                    recon.put(st.name(), 0d);
                }
                while (rs.next()) {
                    recon.put(rs.getString(1), (double) rs.getLong(2));
                }
            } catch (SQLException e) {
                recon.clear();
            }
        } catch (SQLException e) {
            LOG.debug("sangam metrics: database not available: {}", e.getMessage());
        }
        double incidents = Double.NaN;
        RuntimeService rs = runtimeService.getIfAvailable();
        if (rs != null) {
            try {
                incidents = rs.createIncidentQuery().count();
            } catch (RuntimeException e) {
                LOG.debug("sangam metrics: incident count failed: {}", e.getMessage());
            }
        }
        return new Snapshot(Instant.now(), pending, oldest, recon, incidents, parked);
    }

    private static double scalar(Connection c, String sql) {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : Double.NaN;
        } catch (SQLException e) {
            return Double.NaN; // table missing (schema not migrated yet) or query failed
        }
    }

    private static double oldestAge(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT MIN(o.created_at) AS oldest FROM OUTBOX o"
                + " JOIN OUTBOX_DELIVERY d ON d.outbox_id = o.id WHERE d.state IN " + NOT_DELIVERED);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Double.NaN;
            }
            Instant oldest = Jdbc.instant(rs, "oldest");
            return oldest == null ? 0d : Math.max(0d, Duration.between(oldest, Instant.now()).toMillis() / 1000d);
        } catch (SQLException | RuntimeException e) {
            return Double.NaN;
        }
    }
}
