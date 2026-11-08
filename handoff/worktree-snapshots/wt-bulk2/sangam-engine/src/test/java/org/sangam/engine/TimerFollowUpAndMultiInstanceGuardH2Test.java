// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.TaskQuery;

/**
 * Regression tests for two engine defects found by the gov-a acceptance ports (E16-a GP-06/GP-09/GP-10, E17-a
 * D-WORKS/D-PENSION):
 * <ol>
 *   <li>{@link org.sangam.engine.job.GuardedJobHandler}: a repeating timer must schedule its next job. Operaton does
 *       that only when the job handler is a {@code TimerEventJobHandler}; the wrapper hid it, so every cycle fired
 *       once.</li>
 *   <li>{@link org.sangam.engine.deploy.SangamBpmnParseListener}: the GP-09 runtime guard must see the evaluated
 *       cardinality of an expression-based multi-instance; on the body it ran before {@code nrOfInstances} was set.</li>
 * </ol>
 */
class TimerFollowUpAndMultiInstanceGuardH2Test {

    private static final String CYCLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="cycle" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="hold"/>
                <userTask id="hold"/>
                <boundaryEvent id="tm" attachedToRef="hold" cancelActivity="false">
                  <timerEventDefinition><timeCycle xsi:type="tFormalExpression">R3/PT1H</timeCycle></timerEventDefinition>
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

    private static final String MI = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="t">
              <process id="mi" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="item"/>
                <userTask id="item">
                  <multiInstanceLoopCharacteristics isSequential="false">
                    <loopCardinality xsi:type="tFormalExpression">${n}</loopCardinality>
                  </multiInstanceLoopCharacteristics>
                </userTask>
                <sequenceFlow id="f2" sourceRef="item" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter a;

    @BeforeEach
    void engine() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:regr" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.setJobExecutorActivate(false);
        a = SangamEngineFactory.build(ds, settings, "h2");
    }

    @AfterEach
    void close() {
        a.setClock(null);
        a.close();
    }

    private long count(String pid, String key) {
        return a.countTasks(new TaskQuery(null, null, null, pid, key, 0, 0));
    }

    @Test
    void repeatingTimerSchedulesEveryCycleUntilRn() {
        Instant t0 = Instant.parse("2026-11-02T04:30:00Z");
        a.setClock(t0);
        a.deploy(null, "cycle", Map.of("cycle.bpmn", CYCLE.getBytes(StandardCharsets.UTF_8)));
        String pid = a.startProcess(null, "cycle", "C-1", Map.of());
        for (int h = 1; h <= 5; h++) {
            a.setClock(t0.plus(Duration.ofHours(h)).plusSeconds(60));
            a.executeDueJobs();
            assertEquals(Math.min(h, 3), count(pid, "tick"), "fires after hour " + h + " (R3: 3 in total)");
        }
        assertEquals(0, a.processEngine().getManagementService().createJobQuery().processInstanceId(pid).timers().count(),
                "no timer left after the 3rd firing");
    }

    @Test
    void multiInstanceGuardSeesExpressionCardinality() {
        a.deploy(null, "mi", Map.of("mi.bpmn", MI.getBytes(StandardCharsets.UTF_8)));
        RuntimeException e = assertThrows(RuntimeException.class, () -> a.startProcess(null, "mi", "M-1", Map.of("n", 51)));
        assertTrue(String.valueOf(e.getMessage()).contains("GP-09") || String.valueOf(e.getCause()).contains("GP-09"),
                "GP-09 guard message: " + e);
        assertEquals(0, a.processEngine().getRuntimeService().createProcessInstanceQuery().processDefinitionKey("mi").count());
        String pid = a.startProcess(null, "mi", "M-2", Map.of("n", 50));
        assertEquals(50, count(pid, "item"));
    }
}
