// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskQuery;

/**
 * executeDueJobs must not starve due jobs that sort after a job definition at its per-call limit.
 *
 * <p>Root cause (E46 WF-2 on MySQL, CI 44022815109): the loop read only the first page (100) of due jobs ordered by
 * id. When one definition's jobs filled that page and the definition reached {@code maxExecutionsPerJobDefinitionPerCall},
 * the page held only skipped jobs, the loop saw no progress and stopped, so a due timer sorted later never ran (the
 * deemed timer: "open tasks [DEEMED_REVIEW] (found [AUDIT])"). Red before the fix: of 20 target timers only those
 * sorted among the first page or so fired.
 */
class DueJobPagingH2Test {

    private static final int FLOOD = 3 * OperatonEngineAdapter.DUE_JOB_PAGE;
    private static final int TARGETS = 20;

    private OperatonEngineAdapter a;

    private static String model(String id) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                    <userTask id="hold"/>
                    <boundaryEvent id="tm" attachedToRef="hold" cancelActivity="true">
                      <timerEventDefinition><timeDuration xsi:type="tFormalExpression">PT1H</timeDuration></timerEventDefinition>
                    </boundaryEvent>
                    <sequenceFlow id="f2" sourceRef="tm" targetRef="%sFired"/>
                    <userTask id="%sFired"/>
                    <sequenceFlow id="f3" sourceRef="%sFired" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(id, id, id, id);
    }

    @AfterEach
    void close() {
        if (a != null) {
            a.setClock(null);
            a.close();
            a = null;
        }
    }

    @Test
    void aDefinitionAtItsLimitDoesNotStarveLaterDueJobs() {
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform");
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.setJobExecutorActivate(false);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:duepage" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        a = SangamEngineFactory.build(ds, settings, "h2");

        Instant t0 = Instant.parse("2026-11-17T00:00:00Z");
        a.setClock(t0);
        a.deploy("t1", "flood", Map.of("flood.bpmn", model("flood").getBytes(StandardCharsets.UTF_8)));
        a.deploy("t1", "target", Map.of("target.bpmn", model("target").getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < FLOOD; i++) {
            a.startProcess("t1", "flood", "F-" + i, Map.of());
        }
        for (int i = 0; i < TARGETS; i++) {
            a.startProcess("t1", "target", "T-" + i, Map.of());
        }
        a.setClock(t0.plus(Duration.ofHours(2)));
        a.executeDueJobs();

        long flooded = a.countTasks(new TaskQuery(null, null, null, null, "floodFired", 0, 0));
        long fired = a.countTasks(new TaskQuery(null, null, null, null, "targetFired", 0, 0));
        assertEquals(TARGETS, fired, "every due target timer ran (flood definition ran " + flooded + ")");
        assertEquals(settings.getMaxExecutionsPerJobDefinitionPerCall(), flooded, "the flood definition stays bounded");
    }
}
