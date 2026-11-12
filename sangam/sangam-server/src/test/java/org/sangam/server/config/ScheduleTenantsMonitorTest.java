// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-117 Q3: one empty-schedule check for every per-tenant schedule (compliance, channels, AI worker, field, ops): one
 * meter family tagged by scheduler, {@code sangam.schedule.tenants{scheduler}} and {@code sangam.schedule.empty{scheduler}},
 * and a WARN that is never silent but does not flood schedules that cycle every few seconds.
 */
class ScheduleTenantsMonitorTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(ScheduleTenantsMonitor.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void anEmptyCycleWarnsAndIsCountedUnderTheSchedulerTag() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScheduleTenantsMonitor m = new ScheduleTenantsMonitor("compliance", "retention does not run.", meters);
        assertTrue(Double.isNaN(gauge(meters, "compliance")), "NaN before the first cycle");

        assertEquals(List.of(), m.observing(List::of).get());

        List<ILoggingEvent> warns = warns();
        assertEquals(1, warns.size(), logs.list::toString);
        assertTrue(warns.get(0).getFormattedMessage().contains("compliance schedule has no tenant"), warns.get(0).getFormattedMessage());
        assertTrue(warns.get(0).getFormattedMessage().contains("retention does not run."), warns.get(0).getFormattedMessage());
        assertEquals(1.0, empty(meters, "compliance"));
        assertEquals(0.0, gauge(meters, "compliance"));
    }

    @Test
    void aNonEmptyCycleDoesNotWarnAndReportsItsSize() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ScheduleTenantsMonitor m = new ScheduleTenantsMonitor("field", "x", meters);

        assertEquals(List.of("RJ", "MP"), m.observe(List.of("RJ", "MP")));
        assertTrue(warns().isEmpty(), logs.list::toString);
        assertEquals(0.0, empty(meters, "field"));
        assertEquals(2.0, gauge(meters, "field"));
    }

    @Test
    void oneMeterFamilyTaggedBySchedulerNotSeparateMeters() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        for (String s : List.of("compliance", "channels", "ai-worker", "field", "ops")) {
            new ScheduleTenantsMonitor(s, "x", meters).observe(s.equals("ops") ? List.of("RJ") : List.of());
        }
        assertEquals(5, meters.find(ScheduleTenantsMonitor.EMPTY_METRIC).counters().size());
        assertEquals(5, meters.find(ScheduleTenantsMonitor.TENANTS_METRIC).gauges().size());
        assertEquals(0.0, empty(meters, "ops"));
        assertEquals(1.0, empty(meters, "ai-worker"));
        assertTrue(meters.getMeters().stream().allMatch(x -> x.getId().getName().startsWith("sangam.schedule.")),
                "no per-scheduler meter names (the earlier sangam.compliance.schedule.* are gone)");
    }

    @Test
    void whileEmptyTheWarnRepeatsAtMostOncePerIntervalButEveryCycleIsCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        ScheduleTenantsMonitor m = new ScheduleTenantsMonitor("ai-worker", "x", meters, Duration.ofMinutes(5), clock);

        m.observe(List.of());                          // turns empty: WARN
        clock.now = clock.now.plusSeconds(5);
        m.observe(List.of());                          // 5 s later: counted, no WARN
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        m.observe(List.of());                          // interval passed: WARN
        assertEquals(2, warns().size(), logs.list::toString);
        assertEquals(3.0, empty(meters, "ai-worker"));

        m.observe(List.of("RJ"));                      // recovers
        clock.now = clock.now.plusSeconds(5);
        m.observe(List.of());                          // empty again: WARN at once
        assertEquals(3, warns().size(), logs.list::toString);
    }

    @Test
    void aScheduleThatCyclesNoFasterThanTheIntervalWarnsEveryEmptyCycle() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        ScheduleTenantsMonitor m = new ScheduleTenantsMonitor("compliance", "x", meters, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 3; i++) {
            m.observe(List.of());
            clock.now = clock.now.plus(Duration.ofMinutes(5));
        }
        assertEquals(3, warns().size(), "the compliance schedule (5-minute cycle) warns every empty cycle");
    }

    @Test
    void theWiringFactoryWorksWithoutAnApplicationRegistry() {
        ScheduleTenantsMonitor m = ScheduleTenantsMonitor.of("ops", "x",
                new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        assertEquals(List.of(), m.observe(null));
        assertEquals(1, warns().size(), "the WARN applies even when metrics are off");
    }

    private List<ILoggingEvent> warns() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static double gauge(SimpleMeterRegistry meters, String scheduler) {
        return meters.get(ScheduleTenantsMonitor.TENANTS_METRIC).tag(ScheduleTenantsMonitor.TAG, scheduler).gauge().value();
    }

    private static double empty(SimpleMeterRegistry meters, String scheduler) {
        return meters.get(ScheduleTenantsMonitor.EMPTY_METRIC).tag(ScheduleTenantsMonitor.TAG, scheduler).counter().count();
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
