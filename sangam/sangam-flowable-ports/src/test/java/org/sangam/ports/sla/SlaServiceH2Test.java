// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.sla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.WorkCalendar;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.sla.SlaService.SlaClock;
import org.sangam.ports.sla.SlaService.SlaDefinition;
import org.sangam.ports.sla.SlaService.State;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.Paging;

/** EC-08 SLA clocks: deadlines, exactly-once warning/breach events, pause/resume, stop, aggregates, tenant isolation. */
class SlaServiceH2Test {

    /** A calendar that pushes every deadline one extra day (stands in for a non-working day). */
    private static final WorkCalendar PLUS_ONE_DAY = (start, period, duration, zone) ->
            start.atZone(zone).plus(period).plusDays(1).toInstant().plus(duration);

    private PortsH2Engine h2;
    private Transactions tx;
    private SlaService sla;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        tx = EngineTx.transactions(h2.engine);
        sla = new SlaService(Dialect.POSTGRESQL, tx, new Outbox(Dialect.POSTGRESQL), name -> "skip-a-day".equals(name) ? PLUS_ONE_DAY : null);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private static SlaDefinition def(String key, String target, String warn) {
        return new SlaDefinition(key, target, warn, null, ZoneId.of("UTC"), "order");
    }

    private String start(String tenant, SlaDefinition d, String scopeId) {
        return tx.inTransaction(c -> sla.start(c, tenant, d, "process", scopeId));
    }

    private void stop(String tenant, String key, String scopeId) {
        tx.inTransaction(c -> {
            sla.stop(c, tenant, key, "process", scopeId);
            return null;
        });
    }

    private SlaClock clock(String id) {
        return sla.get(id).orElseThrow();
    }

    private List<String> events(String tenant) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = h2.connection(); var ps = c.prepareStatement("SELECT event_type FROM OUTBOX WHERE tenant_id = ? ORDER BY id")) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    @Test
    void deadlineIsStartPlusTargetAndStartIsIdempotentPerTenant() {
        String a = start("t1", def("resolve", "P1DT2H", "PT20H"), "pi-1");
        assertEquals(a, start("t1", def("resolve", "P1DT2H", "PT20H"), "pi-1"), "same clock while open");
        String b = start("t2", def("resolve", "P1DT2H", "PT20H"), "pi-1");
        assertNotEquals(a, b, "another tenant's scope with the same id is a separate clock");

        SlaClock c = clock(a);
        assertEquals(State.RUNNING, c.state());
        assertEquals(Duration.ofHours(26), Duration.between(c.startedAt(), c.dueAt()));
        assertEquals(Duration.ofHours(20), Duration.between(c.startedAt(), c.warnAt()));
        assertEquals(1, sla.forScope("t1", "process", "pi-1").size());
        assertEquals(b, sla.forScope("t2", "process", "pi-1").get(0).id());
        assertTrue(sla.forScope("t3", "process", "pi-1").isEmpty());
    }

    @Test
    void workingCalendarMovesTheDeadlineAndUnknownCalendarIsRefused() {
        String id = start("t1", new SlaDefinition("cal", "PT4H", null, "skip-a-day", ZoneId.of("UTC"), null), "pi-cal");
        SlaClock c = clock(id);
        assertEquals(Duration.ofHours(28), Duration.between(c.startedAt(), c.dueAt()));
        assertThrows(IllegalArgumentException.class,
                () -> start("t1", new SlaDefinition("cal2", "PT4H", null, "no-such-calendar", null, null), "pi-cal"));
        assertTrue(sla.forScope("t1", "process", "pi-cal").stream().noneMatch(k -> "cal2".equals(k.slaKey())));
    }

    @Test
    void warningAndBreachAreRaisedExactlyOnce() throws SQLException {
        String warned = start("t1", def("w", "P1D", "PT0S"), "pi-w");
        String breached = start("t1", def("b", "PT0S", null), "pi-b");
        assertEquals(2, sla.sweep(100));
        assertEquals(0, sla.sweep(100), "nothing raised twice");
        assertEquals(List.of("sangam.sla.warning", "sangam.sla.breached"), events("t1"));

        SlaClock w = clock(warned);
        assertEquals(State.RUNNING, w.state());
        assertTrue(w.warned());
        SlaClock b = clock(breached);
        assertEquals(State.BREACHED, b.state());
        assertNotNull(b.breachedAt());

        stop("t1", "w", "pi-w");
        stop("t1", "b", "pi-b");
        assertEquals(State.MET, clock(warned).state(), "stopped before its deadline");
        assertEquals(State.BREACHED, clock(breached).state(), "a breach is not undone by stopping");
        assertEquals(0, sla.sweep(100));
    }

    /** Regression: a clock stopped after its deadline but before the next sweep was recorded as MET. */
    @Test
    void stopAfterTheDeadlineBeforeAnySweepIsABreach() throws SQLException {
        String id = start("t1", def("late", "PT0S", null), "pi-late");
        stop("t1", "late", "pi-late");
        SlaClock c = clock(id);
        assertEquals(State.BREACHED, c.state(), "deadline passed at stop time");
        assertNotNull(c.breachedAt());
        assertEquals(List.of("sangam.sla.breached"), events("t1"), "the breach is still reported, once");
        assertEquals(0, sla.sweep(100));
        assertEquals(1, sla.breaches("t1", null, null, null, new Paging.PageRequest(0, 10)).total());
    }

    @Test
    void pauseMovesTheDeadlineByThePausedTime() throws InterruptedException {
        String id = start("t1", def("p", "P1D", "PT12H"), "pi-p");
        SlaClock before = clock(id);
        sla.pause(id);
        assertEquals(State.PAUSED, clock(id).state());
        assertTrue(sla.atRisk("t1", null, Duration.ofDays(30), 10).isEmpty(), "paused clocks are not at risk");
        Thread.sleep(300);
        sla.resume(id);
        SlaClock after = clock(id);
        assertEquals(State.RUNNING, after.state());
        long moved = Duration.between(before.dueAt(), after.dueAt()).toMillis();
        assertTrue(moved >= 300, "deadline moved by the paused time: " + moved);
        assertEquals(moved, after.pausedMillis());
        assertEquals(moved, Duration.between(before.warnAt(), after.warnAt()).toMillis());
        sla.resume(id); // not paused: no-op
        assertEquals(after.dueAt(), clock(id).dueAt());
        assertThrows(IllegalArgumentException.class, () -> sla.resume("missing"));
    }

    /** Regression: stopping a paused clock left the open pause out of paused time, inflating elapsed statistics. */
    @Test
    void stoppingAPausedClockCountsTheOpenPauseAsPaused() throws InterruptedException {
        String id = start("t1", def("ps", "P1D", null), "pi-ps");
        sla.pause(id);
        Thread.sleep(400);
        stop("t1", "ps", "pi-ps");
        SlaClock c = clock(id);
        assertEquals(State.MET, c.state());
        assertTrue(c.pausedMillis() >= 400, "open pause added: " + c.pausedMillis());
        SlaService.SlaStats s = sla.stats("t1", null, null).get(0);
        assertEquals(1, s.met());
        assertTrue(s.avgElapsedMillis() < 300, "elapsed excludes the paused time: " + s.avgElapsedMillis());
    }

    @Test
    void aggregatesAreTenantScoped() {
        start("t1", def("b", "PT0S", null), "pi-1");
        start("t1", def("b", "P1D", null), "pi-2");
        start("t1", def("soon", "PT1H", null), "pi-3");
        start("t2", def("b", "PT0S", null), "pi-9");
        sla.sweep(100);
        stop("t1", "b", "pi-2");

        Paging.PageResult<SlaClock> br = sla.breaches("t1", "order", null, null, new Paging.PageRequest(0, 10));
        assertEquals(1, br.total());
        assertEquals("pi-1", br.items().get(0).scopeId());
        assertEquals(0, sla.breaches("t1", "other-definition", null, null, new Paging.PageRequest(0, 10)).total());
        assertEquals(0, sla.breaches("t1", null, Instant.now().plus(Duration.ofDays(1)), null, new Paging.PageRequest(0, 10)).total());

        List<SlaService.AtRisk> risk = sla.atRisk("t1", null, Duration.ofHours(2), 10);
        assertEquals(1, risk.size());
        assertEquals("pi-3", risk.get(0).clock().scopeId());
        long rem = risk.get(0).remainingMillis();
        assertTrue(rem > 0 && rem <= 3_600_000, "remaining " + rem);
        assertTrue(sla.atRisk("t2", null, Duration.ofHours(2), 10).isEmpty(), "t2 has no running clock");

        Map<String, SlaService.SlaStats> byKey = new java.util.HashMap<>();
        sla.stats("t1", null, null).forEach(s -> byKey.put(s.slaKey(), s));
        SlaService.SlaStats b = byKey.get("b");
        assertEquals(2, b.total());
        assertEquals(1, b.breached());
        assertEquals(1, b.met());
        assertEquals(0.5, b.breachRate());
        assertNotNull(b.avgElapsedMillis());
        assertEquals(1, byKey.get("soon").running());
        assertEquals(1, sla.stats("t2", null, null).get(0).total());
        assertFalse(sla.stats("t3", null, null).iterator().hasNext());
        // database rows really are separated by tenant
        assertEquals(1L, (long) tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM SLA_INSTANCE WHERE tenant_id = 't2'",
                rs -> rs.getLong(1)).orElseThrow()));
    }
}
