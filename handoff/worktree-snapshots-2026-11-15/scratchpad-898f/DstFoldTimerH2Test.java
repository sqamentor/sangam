// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskQuery;

/**
 * E8-DST regression (HANDOFF 6b-4): an elapsed {@code R/PT1H} cycle in a Europe/Berlin JVM must fire exactly once per
 * elapsed hour across the autumn fold (2026-10-25, 03:00 CEST -> 02:00 CET), each fire in its own hour.
 */
class DstFoldTimerH2Test {

    private static final String CYCLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="cycle" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                <userTask id="hold"/>
                <boundaryEvent id="tm" attachedToRef="hold" cancelActivity="false">
                  <timerEventDefinition><timeCycle xsi:type="tFormalExpression">R/PT1H</timeCycle></timerEventDefinition>
                </boundaryEvent>
                <sequenceFlow id="f2" sourceRef="tm" targetRef="tick"/>
                <userTask id="tick"/>
                <sequenceFlow id="f3" sourceRef="tick" targetRef="te"/>
                <endEvent id="te"/>
                <sequenceFlow id="f4" sourceRef="hold" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private TimeZone saved;
    private OperatonEngineAdapter a;

    @BeforeEach
    void engine() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dst" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.setJobExecutorActivate(false);
        settings.setTimerZone(ZoneId.of("Europe/Berlin"));
        a = SangamEngineFactory.build(ds, settings, "h2");
    }

    @AfterEach
    void close() {
        try {
            a.setClock(null);
            a.close();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    private void window(Instant start, int hours, Duration step) {
        a.setClock(start);
        a.deploy(null, "cycle", Map.of("cycle.bpmn", CYCLE.getBytes(StandardCharsets.UTF_8)));
        String pid = a.startProcess(null, "cycle", "D-" + step.toMinutes(), Map.of());
        Instant end = start.plus(Duration.ofHours(hours));
        Instant now = start;
        long count = 0;
        List<String> misplaced = new ArrayList<>();
        while (now.isBefore(end)) {
            now = now.plus(step).isAfter(end) ? end : now.plus(step);
            a.setClock(now);
            a.executeDueJobs();
            long c = a.countTasks(new TaskQuery(null, null, null, pid, "tick", 0, 0));
            for (long i = count; i < c; i++) {
                Instant due = start.plus(Duration.ofHours(i + 1));
                if (now.isBefore(due) || !now.isBefore(due.plus(step).plusSeconds(1))) {
                    misplaced.add("fire " + (i + 1) + " at " + now + " (due " + due + ")");
                }
            }
            count = c;
        }
        assertEquals(hours, count, "one fire per elapsed hour");
        assertEquals(List.of(), misplaced, "every fire inside its own hour");
    }

    @Test
    void autumnFoldFiresOncePerElapsedHour() {
        window(Instant.parse("2026-10-24T22:00:00Z"), 6, Duration.ofMinutes(5));
    }

    @Test
    void springGapFiresOncePerElapsedHour() {
        window(Instant.parse("2026-03-28T23:00:00Z"), 6, Duration.ofMinutes(5));
    }
}
