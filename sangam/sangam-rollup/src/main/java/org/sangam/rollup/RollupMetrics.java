// SPDX-License-Identifier: Apache-2.0
package org.sangam.rollup;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Measurement hooks of the roll-up (M-063; SG-15 / T-SCALE-10 measure with them in the test phase, nothing here runs
 * a benchmark). Exposed on {@code /actuator/prometheus} of the management port:
 * <ul>
 *   <li>{@code sangam.rollup.ingest} (timer, tag {@code mode} = batch | event): time to project one ingest call;</li>
 *   <li>{@code sangam.rollup.events} (counter, tag {@code outcome} = projected | duplicate | rejected);</li>
 *   <li>{@code sangam.rollup.batch.events}, {@code sangam.rollup.batch.rows} and {@code sangam.rollup.batch.rows.naive}
 *       (summaries): events per batch, row writes made, and row writes the per-event path would have made; their
 *       ratio is the pre-aggregation saving 8.4 b claims;</li>
 *   <li>{@code sangam.rollup.batch.fallback} (counter): batches replayed event by event after a concurrent first
 *       insert;</li>
 *   <li>{@code sangam.rollup.upstream.events} (counter, tag {@code result} = queued | sent | parked | retry) and
 *       {@code sangam.rollup.upstream.send} (timer): the tier feed.</li>
 * </ul>
 */
@Component
public class RollupMetrics {

    private final MeterRegistry registry;
    private final Timer batchTimer;
    private final Timer eventTimer;
    private final DistributionSummary batchEvents;
    private final DistributionSummary batchRows;
    private final DistributionSummary batchNaiveRows;
    private final Counter fallback;
    private final Timer sendTimer;

    public RollupMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.batchTimer = Timer.builder("sangam.rollup.ingest").tag("mode", "batch").register(registry);
        this.eventTimer = Timer.builder("sangam.rollup.ingest").tag("mode", "event").register(registry);
        this.batchEvents = DistributionSummary.builder("sangam.rollup.batch.events").register(registry);
        this.batchRows = DistributionSummary.builder("sangam.rollup.batch.rows").register(registry);
        this.batchNaiveRows = DistributionSummary.builder("sangam.rollup.batch.rows.naive").register(registry);
        this.fallback = Counter.builder("sangam.rollup.batch.fallback").register(registry);
        this.sendTimer = Timer.builder("sangam.rollup.upstream.send").register(registry);
    }

    /** Meters kept in memory only (tests, and code built without Spring). */
    static RollupMetrics inMemory() {
        return new RollupMetrics(new SimpleMeterRegistry());
    }

    MeterRegistry registry() {
        return registry;
    }

    void outcome(RollupProjector.Outcome outcome, long n) {
        if (n > 0) {
            registry.counter("sangam.rollup.events", "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment(n);
        }
    }

    void event(long nanos) {
        eventTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    void batch(long nanos, int events, int rows, long naiveRows) {
        batchTimer.record(nanos, TimeUnit.NANOSECONDS);
        batchEvents.record(events);
        batchRows.record(rows);
        batchNaiveRows.record(naiveRows);
    }

    void fallback() {
        fallback.increment();
    }

    void upstream(String result, long n) {
        if (n > 0) {
            registry.counter("sangam.rollup.upstream.events", "result", result).increment(n);
        }
    }

    void send(long nanos) {
        sendTimer.record(nanos, TimeUnit.NANOSECONDS);
    }
}
