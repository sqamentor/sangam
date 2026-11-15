// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.core.engine.TaskSuspensionEvents;

/**
 * SG-03(c): every way Operaton suspends or activates tasks ends in a bulk {@code TaskManager} UPDATE that fires no task
 * listener, so {@code task.suspend} / {@code task.activate} never fired. {@code TenantScopedCreation.Tasks} (the one
 * Sangam TaskManager) now reports exactly the tasks each UPDATE changes. Each path is covered: one instance, a
 * definition with its instances, a definition key, a batch, a delayed (timer) definition change, and a repeat that
 * changes nothing (no second event). Without the overrides every count here is zero.
 */
class TaskSuspensionEventsH2Test {

    static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="susp" isExecutable="true" operaton:historyTimeToLive="30">
                <startEvent id="start"/><sequenceFlow id="f0" sourceRef="start" targetRef="review"/>
                <userTask id="review"/><sequenceFlow id="f1" sourceRef="review" targetRef="end"/><endEvent id="end"/>
              </process>
            </definitions>
            """;

    record Event(String taskId, boolean suspended) {
    }

    final List<Event> events = new ArrayList<>();

    @AfterEach
    void resetClock() {
        ClockUtil.reset();
    }

    record Engine(ProcessEngine engine, List<String> instances) {
        String definitionId() {
            return engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("susp").singleResult()
                    .getId();
        }

        String taskOf(String pi) {
            return engine.getTaskService().createTaskQuery().processInstanceId(pi).singleResult().getId();
        }

        void runJobs() {
            for (int round = 0; round < 10; round++) {
                List<Job> due = engine.getManagementService().createJobQuery().executable().list();
                if (due.isEmpty()) {
                    return;
                }
                due.forEach(j -> engine.getManagementService().executeJob(j.getId()));
            }
        }
    }

    /** A Sangam engine with two instances waiting in a user task, and a recording suspension listener. */
    private void withEngine(Consumer<Engine> test) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:s" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setJobExecutorActivate(false);
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            TaskSuspensionEvents.Listener l = (tasks, suspended) -> tasks.forEach(t -> events.add(new Event(t.taskId(),
                    suspended)));
            TaskSuspensionEvents.register(engine.getName(), l);
            try {
                a.deploy("t", "susp", Map.of("susp.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
                List<String> pis = List.of(a.startProcess("t", "susp", "BK-1", Map.of()),
                        a.startProcess("t", "susp", "BK-2", Map.of()));
                test.accept(new Engine(engine, pis));
            } finally {
                TaskSuspensionEvents.unregister(engine.getName(), l);
            }
        }
    }

    @Test
    void oneInstanceSuspendedAndActivatedGivesOneEventEachForItsTask() {
        withEngine(e -> {
            String pi = e.instances().get(0);
            String task = e.taskOf(pi);
            e.engine().getRuntimeService().suspendProcessInstanceById(pi);
            assertEquals(List.of(new Event(task, true)), events);
            e.engine().getRuntimeService().activateProcessInstanceById(pi);
            assertEquals(List.of(new Event(task, true), new Event(task, false)), events);
        });
    }

    @Test
    void aDefinitionWithItsInstancesGivesOneEventPerTask() {
        withEngine(e -> {
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, null);
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().allMatch(Event::suspended));
            events.clear();
            e.engine().getRepositoryService().activateProcessDefinitionByKey("susp", true, null);
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().noneMatch(Event::suspended));
        });
    }

    @Test
    void aBatchGivesOneEventPerTask() {
        withEngine(e -> {
            // SL-45 / decision 144: a batch over a tenant's instances is created by an identity of that tenant, so its
            // jobs carry the tenant (a batch of no tenant over tenant-owned instances is refused when its jobs run)
            e.engine().getIdentityService().setAuthentication("operator", List.of(), List.of("t"));
            Batch b;
            try {
                b = e.engine().getRuntimeService().updateProcessInstanceSuspensionState()
                        .byProcessInstanceIds(e.instances()).suspendAsync();
            } finally {
                e.engine().getIdentityService().clearAuthentication();
            }
            assertEquals(0, events.size(), "nothing before the batch jobs run");
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
            assertTrue(events.stream().allMatch(Event::suspended));
            assertTrue(b.getId() != null);
        });
    }

    @Test
    void aDelayedDefinitionChangeEmitsWhenItsTimerRuns() {
        withEngine(e -> {
            Date at = new Date(ClockUtil.getCurrentTime().getTime() + 3_600_000L);
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, at);
            e.runJobs();
            assertEquals(0, events.size(), "not before the date");
            ClockUtil.setCurrentTime(new Date(at.getTime() + 1000));
            e.runJobs();
            assertEquals(2, events.size(), events.toString());
        });
    }

    @Test
    void aRepeatThatChangesNothingEmitsNothing() {
        withEngine(e -> {
            String pi = e.instances().get(0);
            e.engine().getRuntimeService().suspendProcessInstanceById(pi);
            e.engine().getRepositoryService().suspendProcessDefinitionById(e.definitionId(), true, null);
            // the definition suspension changes only the second instance's task: the first was already suspended
            assertEquals(2, events.size(), events.toString());
            assertEquals(2, events.stream().map(Event::taskId).distinct().count(), "each task exactly once");
            events.clear();
            e.engine().getRuntimeService().suspendProcessInstanceById(e.instances().get(1));
            assertEquals(0, events.size(), "already suspended: no event");
        });
    }
}
