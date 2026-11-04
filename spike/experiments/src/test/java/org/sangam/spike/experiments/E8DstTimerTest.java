package org.sangam.spike.experiments;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.spike.experiments.support.ExperimentBase;

/**
 * E8 timers across DST (SANGAM.md XI.4, T22): a non-interrupting boundary timer with {@code timeCycle R/PT1H} on a
 * waiting user task; every fire creates one {@code e8_tick} user task. The engine clock is stepped with setClock and
 * due jobs are executed after every step (EngineHooks.executeDueJobsBounded: the adapter steps, capped at
 * MAX_JOBS_PER_STEP executions so a timer that does not advance cannot spin for minutes). Pass: exactly one fire per elapsed hour, each inside its own interval.
 *
 * <p>One zone per JVM: the pom runs this class in separate surefire executions with {@code -Duser.timezone=<zone>}
 * (Asia/Kolkata, Europe/Berlin) per engine, and only that zone's windows run. The zone is never switched inside the
 * JVM: Operaton caches the JVM default zone at class initialisation ({@code DateTimeUtil.JVM_DEFAULT_DATE_TIME_ZONE},
 * static final), so a run-time switch misaligns cycles (seen in the first pre-run; README).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E8DstTimerTest extends ExperimentBase {

    static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         id="e8-cycle-definitions" targetNamespace="http://sangam.org/spike/experiments">
              <process id="e8-cycle" name="E8 R/PT1H cycle" isExecutable="true">
                <startEvent id="e8_start"/>
                <sequenceFlow id="e8_f1" sourceRef="e8_start" targetRef="e8_hold"/>
                <userTask id="e8_hold" name="Hold"/>
                <boundaryEvent id="e8_timer" attachedToRef="e8_hold" cancelActivity="false">
                  <timerEventDefinition id="e8_timerDef">
                    <timeCycle xsi:type="tFormalExpression">R/PT1H</timeCycle>
                  </timerEventDefinition>
                </boundaryEvent>
                <sequenceFlow id="e8_f2" sourceRef="e8_timer" targetRef="e8_tick"/>
                <userTask id="e8_tick" name="Tick"/>
                <sequenceFlow id="e8_f3" sourceRef="e8_tick" targetRef="e8_tickEnd"/>
                <endEvent id="e8_tickEnd"/>
                <sequenceFlow id="e8_f4" sourceRef="e8_hold" targetRef="e8_end"/>
                <endEvent id="e8_end"/>
              </process>
            </definitions>
            """;

    /** A window is stopped once it has more than this many times the expected fires. */
    static final int RUNAWAY_FACTOR = 3;

    /** Job executions allowed per clock step; at most one fire is due per 5-minute step. */
    static final int MAX_JOBS_PER_STEP = 20;

    record Window(String id, String zone, Instant start, int hours, String note) {
    }

    /** JVM default zone, fixed per surefire execution with -Duser.timezone (never switched at run time). */
    private final String zone = TimeZone.getDefault().getID();

    @BeforeAll
    void setUp() {
        open("E8", "Timers across DST: R/PT1H cycle, exactly one fire per elapsed interval (T22)");
        adapter.deploy(null, "e8", Map.of("e8-cycle.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
        results.put("model", "inline e8-cycle.bpmn: userTask e8_hold + non-interrupting boundary timeCycle R/PT1H -> userTask e8_tick");
        results.put("jvmDefaultZone", zone);
        results.put("jobRunner", "EngineHooks.executeDueJobsBounded (adapter executeDueJobs steps, max " + MAX_JOBS_PER_STEP
                + " job executions per clock step) - runaway guard; the adapters' own bounds are 100 rounds (Flowable)"
                + " and 10,000 executions (Operaton) per call");
    }

    private void runWindow(Window w, Duration step) {
        scenario("E8-" + w.id() + "-step" + step.toMinutes() + "m", "E8", w.note() + " (" + w.zone() + ", " + w.hours()
                + " h from " + w.start() + ", clock step " + step.toMinutes() + " min)", List.of("inline e8-cycle.bpmn"), ctx -> {
            ZoneId z = ZoneId.of(w.zone());
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX").withZone(z);
            clock(w.start());
            String pid = adapter.startProcess(null, "e8-cycle", "e8-" + w.id() + "-" + step.toMinutes(), Map.of());
            Instant end = w.start().plus(Duration.ofHours(w.hours()));
            List<String> fires = new ArrayList<>();
            List<Long> gaps = new ArrayList<>();
            int count = 0;
            int misplaced = 0;
            boolean runaway = false;
            List<String> bursts = new ArrayList<>();
            List<String> timerAfterFire = new ArrayList<>();
            ctx.detail("timerAtStart", hooks.timerJobs(adapter, pid));
            Instant lastFire = null;
            while (now.isBefore(end)) {
                Instant next = now.plus(step);
                clock(next.isAfter(end) ? end : next);
                hooks.executeDueJobsBounded(adapter, MAX_JOBS_PER_STEP);
                int c = (int) adapter.countTasks(new org.sangam.spike.api.EngineAdapter.TaskQuery(null, null, null, pid,
                        "e8_tick", 0, 0));
                if (c - count > 1) {
                    bursts.add(fmt.format(now) + ": +" + (c - count) + " fires in one step");
                }
                if (c > count && timerAfterFire.size() < 10) {
                    timerAfterFire.add(fmt.format(now) + " -> " + hooks.timerJobs(adapter, pid));
                }
                if (c > RUNAWAY_FACTOR * w.hours()) {
                    runaway = true;
                    ctx.detail("runawayStoppedAt", fmt.format(now) + " (" + now + ") with " + c + " fires");
                    ctx.detail("timerAtRunaway", hooks.timerJobs(adapter, pid));
                    count = c;
                    break;
                }
                for (int i = count; i < c && fires.size() < 60; i++) {
                    int interval = i + 1; // fire #interval belongs to (start + (interval-1)h, start + interval h + step]
                    Instant due = w.start().plus(Duration.ofHours(interval));
                    boolean inPlace = !now.isBefore(due) && now.isBefore(due.plus(step).plusSeconds(1));
                    if (!inPlace) {
                        misplaced++;
                    }
                    fires.add(fmt.format(now) + (inPlace ? "" : " (expected ~" + fmt.format(due) + ")"));
                    if (lastFire != null) {
                        gaps.add(Duration.between(lastFire, now).toMinutes());
                    }
                    lastFire = now;
                }
                count = c;
            }
            ctx.detail("expectedFires", w.hours());
            ctx.detail("actualFires", count);
            ctx.detail("misplacedFires", misplaced);
            ctx.detail("fireTimesLocal", fires);
            ctx.detail("minutesBetweenFires", gaps);
            ctx.detail("bursts", bursts.size() > 20 ? bursts.subList(0, 20) : bursts);
            ctx.detail("runaway", runaway);
            ctx.detail("timerAfterFire", timerAfterFire);
            ctx.check("no runaway (more than " + RUNAWAY_FACTOR + "x the expected fires)", !runaway);
            ctx.check("exactly " + w.hours() + " fires (got " + count + ")", count == w.hours());
            ctx.check("every fire inside its own interval (misplaced " + misplaced + ")", misplaced == 0);
            adapter.cancelInstance(pid, "E8 window done");
        });
    }

    private void catchUp(Window w) {
        scenario("E8-" + w.id() + "-catchup", "E8", "informational: single jump of " + w.hours() + " h + 5 min, then one"
                + " executeDueJobs (" + w.zone() + ")", List.of("inline e8-cycle.bpmn"), ctx -> {
            ctx.info();
            clock(w.start());
            String pid = adapter.startProcess(null, "e8-cycle", "e8-" + w.id() + "-catchup", Map.of());
            clock(w.start().plus(Duration.ofHours(w.hours())).plusSeconds(300));
            int jobs = hooks.executeDueJobsBounded(adapter, MAX_JOBS_PER_STEP);
            ctx.detail("jobsExecuted", jobs);
            ctx.detail("firesAfterOneCall", tasks(pid, "e8_tick").size());
            hooks.executeDueJobsBounded(adapter, MAX_JOBS_PER_STEP);
            ctx.detail("firesAfterSecondCall", tasks(pid, "e8_tick").size());
            adapter.cancelInstance(pid, "E8 catch-up done");
        });
    }

    @Test
    void dst() {
        List<Window> windows = List.of(
                new Window("KOLKATA-24H", "Asia/Kolkata", Instant.parse("2026-10-24T18:30:00Z"), 24,
                        "no-DST control: 24 h from local midnight IST"),
                new Window("BERLIN-AUTUMN-FOLD", "Europe/Berlin", Instant.parse("2026-10-24T22:00:00Z"), 6,
                        "autumn fold 2026-10-25 03:00 CEST -> 02:00 CET (01:00Z): local 02:xx occurs twice"),
                new Window("BERLIN-SPRING-GAP", "Europe/Berlin", Instant.parse("2026-03-28T23:00:00Z"), 6,
                        "spring gap 2026-03-29 02:00 CET -> 03:00 CEST (01:00Z): local 02:xx does not exist"));
        List<Window> mine = windows.stream().filter(w -> w.zone().equals(zone)).toList();
        if (mine.isEmpty()) {
            results.put("note", "no E8 window for JVM zone " + zone + "; run with -Duser.timezone=Asia/Kolkata or Europe/Berlin");
        }
        for (Window w : mine) {
            runWindow(w, Duration.ofMinutes(5));
        }
        for (Window w : mine) {
            if (!w.zone().equals("Asia/Kolkata")) {
                runWindow(w, Duration.ofMinutes(1));
            }
        }
        for (Window w : mine) {
            catchUp(w);
        }
    }

    @AfterAll
    void tearDown() {
        try {
            Map<String, Object> summary = summary(results.scenarios());
            Map<String, Object> counts = new java.util.LinkedHashMap<>();
            for (Map<String, Object> s : results.scenarios()) {
                Map<?, ?> d = (Map<?, ?>) s.get("details");
                counts.put(String.valueOf(s.get("id")), d.containsKey("actualFires")
                        ? d.get("actualFires") + "/" + d.get("expectedFires")
                        : "after one call: " + d.get("firesAfterOneCall") + ", after two: " + d.get("firesAfterSecondCall"));
            }
            summary.put("fireCounts", counts);
            results.write(hooks.engine() + "-" + zone.replace('/', '_'), adapter.engineId(), summary);
        } finally {
            closeAdapter();
        }
    }
}
