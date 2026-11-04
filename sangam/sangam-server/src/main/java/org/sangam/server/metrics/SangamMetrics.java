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
 */
@Component
public class SangamMetrics implements MeterBinder {

    private static final Logger LOG = LoggerFactory.getLogger(SangamMetrics.class);
    static final Duration CACHE = Duration.ofSeconds(15);
    static final String NOT_DELIVERED = "('PENDING','CLAIMED','PARKED')";

    private final DataSource dataSource;
    private final ObjectProvider<RuntimeService> runtimeService;
    private volatile Snapshot snapshot;

    record Snapshot(Instant takenAt, double outboxPending, double outboxOldestAgeSeconds,
                    Map<String, Double> reconciliationByState, double incidentsOpen) {
    }

    public SangamMetrics(DataSource dataSource, ObjectProvider<RuntimeService> runtimeService) {
        this.dataSource = dataSource;
        this.runtimeService = runtimeService;
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
        Map<String, Double> recon = new HashMap<>();
        try (Connection c = dataSource.getConnection()) {
            pending = scalar(c, "SELECT COUNT(*) FROM OUTBOX_DELIVERY WHERE state IN " + NOT_DELIVERED);
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
        return new Snapshot(Instant.now(), pending, oldest, recon, incidents);
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
