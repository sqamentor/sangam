// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.engine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.core.api.EngineAdapter.TaskQuery;
import org.sangam.engine.SangamEngineSettings;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * E8 timers across DST (spike E8DstTimerTest, T22) on the real server: a non-interrupting boundary {@code R/PT1H} on a
 * waiting user task; every fire creates one {@code e8_tick} task. Pass: no runaway, exactly one fire per elapsed hour,
 * each inside its own interval. Stage B measured a runaway at the autumn fold on plain Operaton; this tests Sangam's
 * replacement (A-12 / R31: {@code DstSafeCycleBusinessCalendar} + {@code TimerGuard}, bounded {@code executeDueJobs}),
 * so the spike's test-side job cap is replaced by the product's own bounded runner.
 *
 * <p>Windows run for the engine's timer zone ({@code sangam.engine.timer-zone}, default the JVM zone); the Berlin
 * DST windows need a JVM started with {@code -Duser.timezone=Europe/Berlin} (e.g. {@code JAVA_TOOL_OPTIONS}), as the
 * spike ran one surefire execution per zone. Otherwise they are skipped (reported as skipped, not passed).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E8DstTimerIT extends EngineScenarioSupport {

    static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         id="e8-cycle-definitions" targetNamespace="http://sangam.org/acceptance">
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

    static final int RUNAWAY_FACTOR = 3;

    @Autowired
    SangamEngineSettings settings;

    @BeforeAll
    void deploy() {
        adapter.deploy(null, "e8-" + RUN, Map.of("e8-cycle.bpmn", MODEL.getBytes(StandardCharsets.UTF_8)));
    }

    private void window(String id, String zone, Instant start, int hours, Duration step) {
        Assumptions.assumeTrue(ZoneId.of(zone).equals(settings.getTimerZone()),
                "E8 window " + id + " needs timer zone " + zone + " (engine: " + settings.getTimerZone() + ")");
        scenario("E8-" + id + "-step" + step.toMinutes() + "m", ctx -> {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX").withZone(ZoneId.of(zone));
            clock(start);
            String pid = adapter.startProcess(null, "e8-cycle", key("e8-" + id), Map.of());
            Instant end = start.plus(Duration.ofHours(hours));
            List<String> fires = new ArrayList<>();
            int count = 0;
            int misplaced = 0;
            boolean runaway = false;
            while (now.isBefore(end)) {
                Instant next = now.plus(step);
                clock(next.isAfter(end) ? end : next);
                adapter.executeDueJobs();
                int c = (int) adapter.countTasks(new TaskQuery(null, null, null, pid, "e8_tick", 0, 0));
                if (c > RUNAWAY_FACTOR * hours) {
                    runaway = true;
                    ctx.detail("runawayStoppedAt", fmt.format(now) + " with " + c + " fires; timers " + timerJobs(pid));
                    count = c;
                    break;
                }
                for (int i = count; i < c && fires.size() < 60; i++) {
                    Instant due = start.plus(Duration.ofHours(i + 1));
                    boolean inPlace = !now.isBefore(due) && now.isBefore(due.plus(step).plusSeconds(1));
                    if (!inPlace) {
                        misplaced++;
                    }
                    fires.add(fmt.format(now) + (inPlace ? "" : " (expected ~" + fmt.format(due) + ")"));
                }
                count = c;
            }
            ctx.detail("fireTimesLocal", fires);
            ctx.check("no runaway (more than " + RUNAWAY_FACTOR + "x the expected fires)", !runaway);
            ctx.check("exactly " + hours + " fires (got " + count + ")", count == hours);
            ctx.check("every fire inside its own interval (misplaced " + misplaced + ")", misplaced == 0);
            adapter.cancelInstance(pid, "E8 window done");
        });
    }

    @Test
    void kolkata24h() {
        window("KOLKATA-24H", "Asia/Kolkata", Instant.parse("2026-10-24T18:30:00Z"), 24, Duration.ofMinutes(5));
    }

    @Test
    void berlinAutumnFold5m() {
        window("BERLIN-AUTUMN-FOLD", "Europe/Berlin", Instant.parse("2026-10-24T22:00:00Z"), 6, Duration.ofMinutes(5));
    }

    @Test
    void berlinAutumnFold1m() {
        window("BERLIN-AUTUMN-FOLD", "Europe/Berlin", Instant.parse("2026-10-24T22:00:00Z"), 6, Duration.ofMinutes(1));
    }

    @Test
    void berlinSpringGap5m() {
        window("BERLIN-SPRING-GAP", "Europe/Berlin", Instant.parse("2026-03-28T23:00:00Z"), 6, Duration.ofMinutes(5));
    }

    @Test
    void berlinSpringGap1m() {
        window("BERLIN-SPRING-GAP", "Europe/Berlin", Instant.parse("2026-03-28T23:00:00Z"), 6, Duration.ofMinutes(1));
    }
}
