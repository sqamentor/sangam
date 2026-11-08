// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import org.operaton.bpm.engine.HistoryService;
import org.sangam.cell.registration.CellHeartbeat;
import org.sangam.integration.relay.OutboxCapacityMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load figures of this cell for the control-plane heartbeat (IR-2):
 * <ul>
 *   <li>{@code startsPerSec}: process starts since the previous sample (engine history, every tenant of the cell);</li>
 *   <li>{@code p99CommandMs}: 99th percentile of {@code http.server.requests} (published with
 *       {@code management.metrics.distribution.percentiles.http.server.requests=0.99}; the timer maximum otherwise);</li>
 *   <li>{@code dbPoolUsagePct}: Hikari {@code hikaricp.connections.active} / {@code hikaricp.connections.max};</li>
 *   <li>{@code outboxFillPct}: integration {@link OutboxCapacityMonitor} (R40 gauge);</li>
 *   <li>{@code activeNodes} and {@code tenants}: supplied by the caller (node presence documents, tenant directory).</li>
 * </ul>
 * Every figure degrades to 0 when its source is missing; a heartbeat is never blocked by a metric.
 */
public final class CellLoadSampler {

    private static final Logger LOG = LoggerFactory.getLogger(CellLoadSampler.class);

    private final String cellId;
    private final String version;
    private final MeterRegistry meters;
    private final HistoryService history;
    private final OutboxCapacityMonitor capacity;
    private final Supplier<Integer> activeNodes;
    private final Supplier<Long> tenants;
    private volatile Instant lastSample = Instant.now();
    private volatile String status = "UP";

    public CellLoadSampler(String cellId, String version, MeterRegistry meters, HistoryService history,
                           OutboxCapacityMonitor capacityOrNull, Supplier<Integer> activeNodes, Supplier<Long> tenants) {
        this.cellId = cellId;
        this.version = version;
        this.meters = meters;
        this.history = history;
        this.capacity = capacityOrNull;
        this.activeNodes = activeNodes;
        this.tenants = tenants;
    }

    /** UP, DEGRADED or DRAINING (set while the node shuts down). */
    public void status(String status) {
        this.status = status;
    }

    public CellHeartbeat sample() {
        Instant now = Instant.now();
        Instant since = lastSample;
        lastSample = now;
        double seconds = Math.max(1.0, (now.toEpochMilli() - since.toEpochMilli()) / 1000.0);
        double starts = safe(() -> (double) history.createHistoricProcessInstanceQuery()
                .startedAfter(Date.from(since)).count()) / seconds;
        double outbox = safe(() -> {
            if (capacity == null) {
                return 0.0;
            }
            OutboxCapacityMonitor.Sample s = capacity.last() != null ? capacity.last() : capacity.sample();
            return s == null ? 0.0 : s.usedRatio() * 100.0;
        });
        String current = status;
        if ("UP".equals(current) && outbox >= 80.0) {
            current = "DEGRADED";
        }
        return new CellHeartbeat(cellId, current, round(starts), round(p99Ms()), Math.max(1, safeInt(activeNodes)),
                round(poolUsagePct()), round(outbox), safeLong(tenants), version);
    }

    private double p99Ms() {
        return safe(() -> {
            double worst = 0;
            for (Timer t : meters.find("http.server.requests").timers()) {
                double v = -1;
                for (ValueAtPercentile p : t.takeSnapshot().percentileValues()) {
                    if (Math.abs(p.percentile() - 0.99) < 1e-6) {
                        v = p.value(java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                }
                if (v < 0) {
                    v = t.max(java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                worst = Math.max(worst, v);
            }
            return worst;
        });
    }

    private double poolUsagePct() {
        return safe(() -> {
            double active = 0;
            double max = 0;
            for (Gauge g : meters.find("hikaricp.connections.active").gauges()) {
                active += g.value();
            }
            for (Gauge g : meters.find("hikaricp.connections.max").gauges()) {
                max += g.value();
            }
            return max <= 0 ? 0.0 : active / max * 100.0;
        });
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 100.0) / 100.0 : 0.0;
    }

    private static double safe(Supplier<Double> s) {
        try {
            Double v = s.get();
            return v == null || !Double.isFinite(v) ? 0.0 : v;
        } catch (RuntimeException e) {
            LOG.debug("cell load figure unavailable: {}", e.toString());
            return 0.0;
        }
    }

    private static int safeInt(Supplier<Integer> s) {
        try {
            Integer v = s.get();
            return v == null ? 1 : v;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static long safeLong(Supplier<Long> s) {
        try {
            Long v = s.get();
            return v == null ? 0 : v;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
