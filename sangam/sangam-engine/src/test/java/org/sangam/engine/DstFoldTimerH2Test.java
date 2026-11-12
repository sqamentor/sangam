// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.engine.db.EngineDialect;

/**
 * SL-53 regression (E8-DST, HANDOFF 6b-4; owner decision 2026-11-09 "b: engine JVM in UTC").
 *
 * <p>Root cause: Operaton stores {@code java.util.Date} in zone-less {@code TIMESTAMP} columns as JVM-local wall time.
 * In a Europe/Berlin JVM the repeated 02:00 of the autumn fold (2026-10-25, 03:00 CEST -> 02:00 CET) reads back one
 * hour early, the timer guard sees an interval that already fired and suppresses the firing: one fire lost, the rest
 * misplaced. The fix: the engine runs only in a UTC JVM ({@code EngineTimeZone}; the build passes
 * {@code -Duser.timezone=UTC} to every test JVM) and the business zone of timers comes only from
 * {@code sangam.engine.timer-zone} (default UTC, never the JVM zone).
 *
 * <ul>
 *   <li>an elapsed {@code R/PT1H} cycle with timer zone Europe/Berlin fires exactly once per elapsed hour across the
 *       autumn fold and the spring gap, each fire in its own hour (red in a Berlin JVM before the fix: 5 of 6);</li>
 *   <li>a JVM outside UTC (Asia/Kolkata included) is refused at start-up, before the database is touched;</li>
 *   <li>cron, the date part of durations and calendar-cadence cycles follow the timer zone, never the JVM zone;
 *       with timer zone Asia/Kolkata (IFMS) a 17:00 IST cut-off fires exactly at 11:30Z.</li>
 * </ul>
 */
class DstFoldTimerH2Test {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static final String HOURLY = model("<timeCycle xsi:type=\"tFormalExpression\">R/PT1H</timeCycle>", false);

    private OperatonEngineAdapter a;

    /** A user task {@code hold} with a non-interrupting timer boundary that creates one {@code tick} task per firing. */
    private static String model(String timer, boolean interrupting) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
                  <process id="cycle" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                    <userTask id="hold"/>
                    <boundaryEvent id="tm" attachedToRef="hold" cancelActivity="%s">
                      <timerEventDefinition>%s</timerEventDefinition>
                    </boundaryEvent>
                    <sequenceFlow id="f2" sourceRef="tm" targetRef="tick"/>
                    <userTask id="tick"/>
                    <sequenceFlow id="f3" sourceRef="tick" targetRef="te"/>
                    <endEvent id="te"/>
                    <sequenceFlow id="f4" sourceRef="hold" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(interrupting, timer);
    }

    private static SangamEngineSettings settings(ZoneId timerZone) {
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.setJobExecutorActivate(false);
        settings.setTimerZone(timerZone);
        return settings;
    }

    private OperatonEngineAdapter engine(ZoneId timerZone) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dst" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        a = SangamEngineFactory.build(ds, settings(timerZone), "h2");
        return a;
    }

    @AfterEach
    void close() {
        if (a != null) {
            a.setClock(null);
            a.close();
            a = null;
        }
    }

    /** Starts the model at {@code start}, moves the engine clock in {@code step}s to {@code end}; one entry per tick. */
    private List<Instant> fires(String model, Instant start, Instant end, Duration step) {
        a.setClock(start);
        a.deploy(null, "cycle", Map.of("cycle.bpmn", model.getBytes(StandardCharsets.UTF_8)));
        String pid = a.startProcess(null, "cycle", "D-" + step.toMinutes(), Map.of());
        List<Instant> fires = new ArrayList<>();
        Instant now = start;
        while (now.isBefore(end)) {
            now = now.plus(step).isAfter(end) ? end : now.plus(step);
            a.setClock(now);
            a.executeDueJobs();
            long c = a.countTasks(new TaskQuery(null, null, null, pid, "tick", 0, 0));
            while (fires.size() < c) {
                fires.add(now);
            }
        }
        return fires;
    }

    /** Exactly one fire per expected due instant, each seen in the first clock step at or after it. */
    private static void assertFiredAt(List<Instant> due, List<Instant> fires, Duration step) {
        assertEquals(due.size(), fires.size(), "fires " + fires + ", expected one at each of " + due);
        List<String> misplaced = new ArrayList<>();
        for (int i = 0; i < due.size(); i++) {
            Instant d = due.get(i);
            Instant f = fires.get(i);
            if (f.isBefore(d) || f.isAfter(d.plus(step))) {
                misplaced.add("fire " + (i + 1) + " at " + f + " (due " + d + ")");
            }
        }
        assertEquals(List.of(), misplaced, "every fire inside its own interval");
    }

    private void hourly(Instant start, int hours, Duration step) {
        engine(BERLIN);
        List<Instant> due = new ArrayList<>();
        for (int i = 1; i <= hours; i++) {
            due.add(start.plus(Duration.ofHours(i)));
        }
        assertFiredAt(due, fires(HOURLY, start, start.plus(Duration.ofHours(hours)), step), step);
    }

    @Test
    void autumnFoldFiresOncePerElapsedHour() {
        hourly(Instant.parse("2026-10-24T22:00:00Z"), 6, Duration.ofMinutes(5));
    }

    @Test
    void autumnFoldFiresOncePerElapsedHourMinuteSteps() {
        hourly(Instant.parse("2026-10-24T22:00:00Z"), 6, Duration.ofMinutes(1));
    }

    @Test
    void springGapFiresOncePerElapsedHour() {
        hourly(Instant.parse("2026-03-28T23:00:00Z"), 6, Duration.ofMinutes(5));
    }

    @Test
    void aJvmOutsideUtcIsRefusedAtStartUpBeforeTheDatabaseIsTouched() {
        AtomicInteger touched = new AtomicInteger();
        DataSource untouchable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    touched.incrementAndGet();
                    throw new UnsupportedOperationException("database touched: " + method.getName());
                });
        TimeZone saved = TimeZone.getDefault();
        try {
            // Asia/Kolkata too: IST has no DST, but the engine JVM is strictly UTC (an IST host sets the timer zone)
            for (String zone : List.of("Europe/Berlin", "Europe/London", "America/Sao_Paulo", "Asia/Kolkata")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                int before = touched.get();
                // embedded engine (SangamEngineFactory / SangamEngineConfigurer: also the server's path)
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> SangamEngineFactory.build(untouchable, settings(BERLIN), "h2").close(), zone);
                assertTrue(e.getMessage().contains("-Duser.timezone=UTC"), e.getMessage());
                assertTrue(e.getMessage().contains(zone), e.getMessage());
                // hand-wired engine (components + plugin on an application-owned configuration)
                assertThrows(IllegalStateException.class,
                        () -> new SangamEngineComponents(settings(BERLIN), EngineDialect.H2), zone);
                assertEquals(zone, TimeZone.getDefault().getID(), "refused, never overridden");
                assertEquals(before, touched.get(), zone + ": refused before the first database call");
            }
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void cronFollowsTheTimerZoneWallClockAndFiresOnceAtTheRepeatedHour() {
        engine(BERLIN);
        Duration step = Duration.ofMinutes(5);
        Instant start = Instant.parse("2026-10-23T22:00:00Z"); // 2026-10-24 00:00 CEST
        List<Instant> fires = fires(model("<timeCycle xsi:type=\"tFormalExpression\">0 30 2 * * *</timeCycle>", false),
                start, start.plus(Duration.ofDays(3)), step);
        assertFiredAt(List.of(
                Instant.parse("2026-10-24T00:30:00Z"),   // 02:30 CEST
                Instant.parse("2026-10-25T00:30:00Z"),   // 02:30 CEST; 02:30 CET (01:30Z) is the same wall-clock time
                Instant.parse("2026-10-26T01:30:00Z")),  // 02:30 CET
                fires, step);
    }

    @Test
    void dayDurationIsACalendarDayOfTheTimerZone() {
        engine(BERLIN);
        Instant start = Instant.parse("2026-10-24T10:00:00Z"); // 12:00 CEST
        a.setClock(start);
        a.deploy(null, "cycle", Map.of("cycle.bpmn", model(
                "<timeDuration xsi:type=\"tFormalExpression\">P1DT1H</timeDuration>", true).getBytes(StandardCharsets.UTF_8)));
        String pid = a.startProcess(null, "cycle", "D-1", Map.of());
        Instant due = a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).timers()
                .singleResult().getDuedate().toInstant();
        // one Berlin calendar day (12:00 CEST -> 12:00 CET, 25 h) plus one elapsed hour
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), due);
    }

    private Instant timerDue(String pid) {
        return a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).timers().singleResult()
                .getDuedate().toInstant();
    }

    private long ticks(String pid) {
        return a.countTasks(new TaskQuery(null, null, null, pid, "tick", 0, 0));
    }

    /** Starts {@code timer} at {@code t0}; returns the instance id. */
    private String start(String timer, Instant t0) {
        a.setClock(t0);
        a.deploy(null, "cycle", Map.of("cycle.bpmn", model(timer, false).getBytes(StandardCharsets.UTF_8)));
        return a.startProcess(null, "cycle", "IST-1", Map.of());
    }

    /** Fires the instance's timer exactly at its due instant and checks the next due instant. */
    private void fireExactlyAt(String pid, Instant due, Instant nextDue, long ticksAfter) {
        assertEquals(due, timerDue(pid));
        a.setClock(due.minusMillis(1));
        a.executeDueJobs();
        assertEquals(ticksAfter - 1, ticks(pid), "not before " + due);
        a.setClock(due);
        a.executeDueJobs();
        assertEquals(ticksAfter, ticks(pid), "at " + due);
        assertEquals(nextDue, timerDue(pid));
    }

    @Test
    void istCutOffCronFiresAtTheExactIstWallClockInstant() {
        engine(KOLKATA); // JVM in UTC, timer zone Asia/Kolkata (IFMS)
        // a daily 17:00 IST cut-off: IST has no DST, so every firing is exactly 11:30Z
        String pid = start("<timeCycle xsi:type=\"tFormalExpression\">0 0 17 * * *</timeCycle>",
                Instant.parse("2026-11-10T00:00:00Z")); // 05:30 IST
        fireExactlyAt(pid, Instant.parse("2026-11-10T11:30:00Z"), Instant.parse("2026-11-11T11:30:00Z"), 1);
        fireExactlyAt(pid, Instant.parse("2026-11-11T11:30:00Z"), Instant.parse("2026-11-12T11:30:00Z"), 2);
    }

    @Test
    void istDailyCycleWithAnIstStartFiresAtTheExactInstant() {
        engine(KOLKATA);
        // a business start written with its offset (an offset-less literal is engine-zone UTC)
        String pid = start("<timeCycle xsi:type=\"tFormalExpression\">R/2026-11-10T17:00:00+05:30/P1D</timeCycle>",
                Instant.parse("2026-11-10T00:00:00Z"));
        fireExactlyAt(pid, Instant.parse("2026-11-10T11:30:00Z"), Instant.parse("2026-11-11T11:30:00Z"), 1);
        fireExactlyAt(pid, Instant.parse("2026-11-11T11:30:00Z"), Instant.parse("2026-11-12T11:30:00Z"), 2);
    }

    @Test
    void withoutATimerZoneTheTimerZoneIsUtcNeverTheJvmZone() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
            assertEquals(ZoneOffset.UTC, new SangamEngineSettings().getTimerZone(), "default timer zone");
        } finally {
            TimeZone.setDefault(saved);
        }
        // an engine built without sangam.engine.timer-zone runs the 17:00 cron at 17:00 UTC
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dst" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        a = SangamEngineFactory.build(ds, s, "h2");
        String pid = start("<timeCycle xsi:type=\"tFormalExpression\">0 0 17 * * *</timeCycle>",
                Instant.parse("2026-11-10T00:00:00Z"));
        fireExactlyAt(pid, Instant.parse("2026-11-10T17:00:00Z"), Instant.parse("2026-11-11T17:00:00Z"), 1);
    }

    @Test
    void dailyCycleKeepsTheTimerZoneWallClockAcrossTheFold() {
        engine(BERLIN);
        Duration step = Duration.ofMinutes(5);
        Instant start = Instant.parse("2026-10-24T10:00:00Z"); // 12:00 CEST; Operaton writes this start as UTC
        List<Instant> fires = fires(model("<timeCycle xsi:type=\"tFormalExpression\">R/P1D</timeCycle>", false),
                start, start.plus(Duration.ofDays(3)).plus(Duration.ofHours(2)), step);
        assertFiredAt(List.of(
                Instant.parse("2026-10-25T11:00:00Z"),   // 12:00 CET
                Instant.parse("2026-10-26T11:00:00Z"),
                Instant.parse("2026-10-27T11:00:00Z")),
                fires, step);
    }
}
