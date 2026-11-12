// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SL-117: the ONE "is this per-tenant schedule running for anybody?" check, next to {@link ScheduleTenants}, used by
 * the compliance, channels, AI worker, field and ops schedules. Every observed tenant list (one per scheduler cycle)
 * sets {@value #TENANTS_METRIC}{@code {scheduler}}; an empty one counts {@value #EMPTY_METRIC}{@code {scheduler}} and
 * logs a WARN naming the scheduler, what does not run and where the tenants come from.
 *
 * <p>The WARN is logged on every empty cycle when the schedule turns empty, then at most once per {@code warnEvery}
 * (default {@link #DEFAULT_WARN_EVERY}) while it stays empty, because some schedules cycle every few seconds (the AI
 * worker every 5 s); the counter counts every empty cycle. A schedule whose cycle is at least {@code warnEvery} (the
 * compliance schedule: 5 minutes) therefore warns on every empty cycle.
 *
 * <p>Failed tenant-directory reads ({@link ScheduleTenants}) use the same throttle: WARN at once on the first failure,
 * then at most once per {@code warnEvery} while it persists, one INFO when a read succeeds again, and every failure
 * counted in {@value #DIRECTORY_FAILURES_METRIC}{@code {scheduler}}.
 */
public final class ScheduleTenantsMonitor {

    public static final String TENANTS_METRIC = "sangam.schedule.tenants";
    public static final String EMPTY_METRIC = "sangam.schedule.empty";
    public static final String DIRECTORY_FAILURES_METRIC = "sangam.schedule.directory_failures";
    public static final String TAG = "scheduler";
    public static final Duration DEFAULT_WARN_EVERY = Duration.ofMinutes(5);
    private static final Logger LOG = LoggerFactory.getLogger(ScheduleTenantsMonitor.class);

    private final String scheduler;
    private final String consequence;
    private final Duration warnEvery;
    private final Clock clock;
    private final Counter empty;
    private final Counter directoryFailures;
    private volatile double lastCount = Double.NaN;
    private Instant lastWarn;
    private boolean wasEmpty;
    private Instant lastDirectoryWarn;
    private boolean directoryFailing;

    /**
     * @param scheduler   tag value, e.g. {@code compliance}
     * @param consequence what runs for nobody and where the tenants come from (appended to the WARN)
     */
    public ScheduleTenantsMonitor(String scheduler, String consequence, MeterRegistry meters) {
        this(scheduler, consequence, meters, DEFAULT_WARN_EVERY, Clock.systemUTC());
    }

    /** For the wiring: the application's registry, or a private one when metrics are off (the WARN still applies). */
    public static ScheduleTenantsMonitor of(String scheduler, String consequence, ObjectProvider<MeterRegistry> meters) {
        return new ScheduleTenantsMonitor(scheduler, consequence, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    ScheduleTenantsMonitor(String scheduler, String consequence, MeterRegistry meters, Duration warnEvery, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.consequence = Objects.requireNonNull(consequence, "consequence");
        this.warnEvery = Objects.requireNonNull(warnEvery, "warnEvery");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(meters, "meters");
        Gauge.builder(TENANTS_METRIC, this, m -> m.lastCount)
                .description("Tenants in the last cycle of a per-tenant schedule (0: it runs for nobody)")
                .tag(TAG, scheduler)
                .register(meters);
        this.empty = Counter.builder(EMPTY_METRIC)
                .description("Cycles of a per-tenant schedule that ran for no tenant")
                .tag(TAG, scheduler)
                .register(meters);
        this.directoryFailures = Counter.builder(DIRECTORY_FAILURES_METRIC)
                .description("Failed tenant-directory reads of a per-tenant schedule")
                .tag(TAG, scheduler)
                .register(meters);
    }

    public String scheduler() {
        return scheduler;
    }

    /**
     * Records one failed directory read (always counted). Returns true when {@link ScheduleTenants} must log the WARN:
     * on the first failure, then at most once per {@code warnEvery} while the failure persists.
     */
    public synchronized boolean directoryFailed() {
        directoryFailures.increment();
        Instant now = clock.instant();
        boolean warn = !directoryFailing || lastDirectoryWarn == null || !now.isBefore(lastDirectoryWarn.plus(warnEvery));
        if (warn) {
            lastDirectoryWarn = now;
        }
        directoryFailing = true;
        return warn;
    }

    /** Records a successful directory read; true exactly once after a failure (one INFO line on recovery). */
    public synchronized boolean directoryRecovered() {
        boolean recovered = directoryFailing;
        directoryFailing = false;
        lastDirectoryWarn = null;
        return recovered;
    }

    /** The scheduler's tenant source, observed on every read (one read per cycle). */
    public Supplier<List<String>> observing(Supplier<List<String>> source) {
        Objects.requireNonNull(source, "source");
        return () -> observe(source.get());
    }

    /** Records one cycle's tenant list and returns it unchanged. */
    public synchronized List<String> observe(List<String> tenants) {
        List<String> ts = tenants == null ? List.of() : tenants;
        lastCount = ts.size();
        if (!ts.isEmpty()) {
            wasEmpty = false;
            return ts;
        }
        empty.increment();
        Instant now = clock.instant();
        if (!wasEmpty || lastWarn == null || !now.isBefore(lastWarn.plus(warnEvery))) {
            lastWarn = now;
            LOG.warn("SL-117: the {} schedule has no tenant this cycle, so it runs for nobody: {}", scheduler, consequence);
        }
        wasEmpty = true;
        return ts;
    }
}
