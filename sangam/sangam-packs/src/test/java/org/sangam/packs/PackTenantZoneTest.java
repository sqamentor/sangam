// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.sangam.core.time.BusinessClock;
import org.sangam.packs.core.Vars;
import org.sangam.packs.gov.HearingScheduleDelegate;
import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.sim.WorkingDayCalendar;

/**
 * SL-104 (ADR-D-20): pack time is the tenant's business zone from {@link BusinessClock#system()} (the server installs
 * the tenant-profile clock), never a fixed country zone: {@code Vars.today}, the default working-day calendar and the
 * hearing time. A tenant with a profile zone keeps it; a tenant without one is in UTC (the documented fallback).
 * Friday 2026-11-06 20:00Z is already Saturday in a +05:30 zone and still Friday in UTC.
 */
class PackTenantZoneTest {

    private static final Instant FRI_2000Z = Instant.parse("2026-11-06T20:00:00Z");
    private static final ZoneId PROFILE_ZONE = ZoneId.of("Asia/Kolkata");   // test data: one tenant's profile zone

    private BusinessClock previousClock;
    private PackRuntime.Ports previousPorts;

    @BeforeEach
    void tenantZones() {
        previousClock = BusinessClock.install(BusinessClock.of(Clock.fixed(FRI_2000Z, ZoneOffset.UTC),
                t -> "with-profile".equals(t) ? PROFILE_ZONE : null));   // null: no profile, UTC
        previousPorts = PackRuntime.ports();
        PackRuntime.install(previousPorts.withClock(Clock.fixed(FRI_2000Z, ZoneOffset.UTC)));
    }

    @AfterEach
    void restore() {
        BusinessClock.install(previousClock);
        PackRuntime.install(previousPorts);
    }

    @Test
    void todayIsTheTenantsBusinessDate() {
        assertEquals(LocalDate.of(2026, 11, 7), Vars.today("with-profile"));
        assertEquals(LocalDate.of(2026, 11, 6), Vars.today("no-profile"), "a tenant without a profile is in UTC");
        assertEquals(PROFILE_ZONE, Vars.zone("with-profile"));
        assertEquals(ZoneOffset.UTC, Vars.zone("no-profile"));
    }

    @Test
    void theDefaultCalendarUsesEachTenantsBusinessZone() {
        WorkingDayCalendar cal = new WorkingDayCalendar();
        assertFalse(cal.isWorkingDay("with-profile", FRI_2000Z), "already Saturday in the tenant's profile zone");
        assertTrue(cal.isWorkingDay("no-profile", FRI_2000Z), "still Friday in UTC");
    }

    @Test
    void aFixedZoneCalendarIsUnchanged() {
        WorkingDayCalendar cal = new WorkingDayCalendar(PROFILE_ZONE);
        assertFalse(cal.isWorkingDay("no-profile", FRI_2000Z), "an explicit zone wins over the business clock");
    }

    @Test
    void theHearingIsAt1030InTheTenantsZone() throws Exception {
        Map<String, Object> vars = new HashMap<>(Map.of("nextHearingDate", "2026-12-01"));
        new HearingScheduleDelegate().execute(execution("with-profile", vars));
        assertEquals(Date.from(Instant.parse("2026-12-01T05:00:00Z")), vars.get("hearingAt"), "10:30 at +05:30");

        Map<String, Object> other = new HashMap<>(Map.of("nextHearingDate", "2026-12-01"));
        new HearingScheduleDelegate().execute(execution("no-profile", other));
        assertEquals(Date.from(Instant.parse("2026-12-01T10:30:00Z")), other.get("hearingAt"), "10:30 UTC");
    }

    /** A delegate execution of {@code tenant} over {@code vars}; every other method answers a neutral default. */
    private static DelegateExecution execution(String tenant, Map<String, Object> vars) {
        return (DelegateExecution) Proxy.newProxyInstance(PackTenantZoneTest.class.getClassLoader(),
                new Class<?>[]{DelegateExecution.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getTenantId":
                            return tenant;
                        case "getVariable":
                            return vars.get((String) a[0]);
                        case "hasVariable":
                            return vars.containsKey((String) a[0]);
                        case "getVariables":
                            return vars;
                        case "setVariable":
                            vars.put((String) a[0], a[1]);
                            return null;
                        default:
                            Class<?> r = m.getReturnType();
                            if (r == boolean.class) {
                                return false;
                            }
                            if (r == int.class || r == long.class) {
                                return r == int.class ? 0 : 0L;
                            }
                            return null;
                    }
                });
    }
}