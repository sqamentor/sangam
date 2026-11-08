// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.history.EngineEventBus;
import org.sangam.ports.support.EngineTx;

/** FC-52 task events and lifecycle fields, sub-tasks, identity links, instance involvement. */
class TaskServicesH2Test {

    private static final String MODEL = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="p" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="review"/>
                <userTask id="review" name="Review"/><sequenceFlow id="f2" sourceRef="review" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private TaskService ts;
    private final List<EngineEventBus.EngineEvent> events = new CopyOnWriteArrayList<>();
    private AutoCloseable sub;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        ts = h2.engine.getTaskService();
        h2.engine.getRepositoryService().createDeployment().addString("p.bpmn", MODEL).tenantId("t1").deploy();
        sub = h2.plugin.eventBus().register("task.", events::add, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        sub.close();
        h2.close();
    }

    private Task startTask() {
        String pid = h2.engine.getRuntimeService().createProcessInstanceByKey("p").processDefinitionTenantId("t1").execute().getId();
        return ts.createTaskQuery().processInstanceId(pid).singleResult();
    }

    private List<String> types(String taskId) {
        return events.stream().filter(e -> taskId.equals(e.taskId())).map(EngineEventBus.EngineEvent::type).toList();
    }

    private TaskLifecycleService lifecycle() {
        return new TaskLifecycleService(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine));
    }

    @Test
    void derivedTaskEventsAndClaimTimes() {
        Task t = startTask();
        assertTrue(types(t.getId()).contains("task.create"));
        TaskLifecycleService lc = lifecycle();

        ts.claim(t.getId(), "alice");
        assertTrue(types(t.getId()).contains("task.claim"), types(t.getId()).toString());
        TaskLifecycleService.TaskLifecycle l = lc.get(t.getId()).orElseThrow();
        assertEquals("alice", l.claimedBy());
        assertNotNull(l.claimTime());
        assertEquals("t1", l.tenantId());
        EngineEventBus.EngineEvent claim = events.stream().filter(e -> "task.claim".equals(e.type())).findFirst().orElseThrow();
        assertEquals("t1", claim.tenantId());
        assertEquals("alice", claim.data().get("new"));

        lc.startProgress(t.getId(), "t1", "alice");
        assertEquals("alice", lc.get(t.getId()).orElseThrow().inProgressBy());

        ts.setAssignee(t.getId(), null);
        assertTrue(types(t.getId()).contains("task.unclaim"));
        l = lc.get(t.getId()).orElseThrow();
        assertNull(l.claimedBy());
        assertNull(l.inProgressBy(), "unclaim clears progress");

        ts.setOwner(t.getId(), "bob");
        ts.setPriority(t.getId(), 80);
        ts.setAssignee(t.getId(), "carol");
        ts.delegateTask(t.getId(), "dave");
        ts.resolveTask(t.getId());
        Task cur = ts.createTaskQuery().taskId(t.getId()).singleResult();
        cur.setDueDate(new Date(4_000_000_000_000L));
        cur.setName("Review again");
        ts.saveTask(cur);
        List<String> seen = types(t.getId());
        for (String expected : List.of("task.owner", "task.priority", "task.delegate", "task.resolve", "task.due-date", "task.name")) {
            assertTrue(seen.contains(expected), expected + " in " + seen);
        }
        ts.complete(t.getId());
        assertTrue(types(t.getId()).contains("task.complete"));
    }

    @Test
    void businessStatusIsUpsertedPerInstance() {
        TaskLifecycleService lc = lifecycle();
        assertTrue(lc.businessStatus("pi-1", "t1").isEmpty());
        lc.setBusinessStatus("pi-1", "t1", "awaiting-documents", "u1");
        lc.setBusinessStatus("pi-1", "t1", "approved", "u2");
        assertEquals("approved", lc.businessStatus("pi-1", "t1").orElseThrow());
        assertTrue(lc.businessStatus("pi-1", "t2").isEmpty(), "another tenant does not read it");
        assertThrows(IllegalArgumentException.class, () -> lc.setBusinessStatus("pi-1", "t2", "hijacked", "x"),
                "another tenant cannot overwrite it");
        assertEquals("approved", lc.businessStatus("pi-1", "t1").orElseThrow());
        assertTrue(lc.businessStatus("pi-2", "t1").isEmpty());
    }

    @Test
    void subTasksInheritTheParentAndBlockCompletionWhenRequired() {
        Task parent = startTask();
        ts.setPriority(parent.getId(), 70);
        TaskEnhancementService strict = new TaskEnhancementService(h2.engine, h2.plugin.links(), true);
        Task st = strict.createSubTask(parent.getId(), "Collect", "erin", null, Map.of("note", "n1"));
        Task stored = ts.createTaskQuery().taskId(st.getId()).singleResult();
        assertEquals(parent.getId(), stored.getParentTaskId());
        assertEquals("t1", stored.getTenantId());
        assertEquals(70, stored.getPriority());
        assertEquals("n1", ts.getVariableLocal(st.getId(), "note"));
        assertEquals(1, strict.subTasks(parent.getId()).size());

        assertThrows(ProcessEngineException.class, () -> strict.complete(parent.getId(), Map.of()));
        assertEquals(1, ts.createTaskQuery().taskId(parent.getId()).count(), "parent still open");
        strict.complete(st.getId(), null);
        strict.complete(parent.getId(), Map.of("ok", true));
        assertEquals(0, ts.createTaskQuery().taskId(parent.getId()).count());

        Task p2 = startTask();
        TaskEnhancementService lax = new TaskEnhancementService(h2.engine, h2.plugin.links(), false);
        lax.createSubTask(p2.getId(), "Optional", null, null, null);
        lax.complete(p2.getId(), null);
        assertThrows(ProcessEngineException.class, () -> lax.createSubTask("missing", "x", null, null, null));
    }

    @Test
    void identityLinkTypesAreValidated() {
        Task t = startTask();
        TaskEnhancementService svc = new TaskEnhancementService(h2.engine, h2.plugin.links(), false);
        svc.addUserLink(t.getId(), "u1", "watcher");
        svc.addGroupLink(t.getId(), "g1", "x-escalation");
        assertThrows(IllegalArgumentException.class, () -> svc.addUserLink(t.getId(), "u2", "owner"));
        assertThrows(IllegalArgumentException.class, () -> svc.addUserLink(t.getId(), "u2", null));
        assertTrue(svc.links(t.getId()).stream().anyMatch(l -> "u1".equals(l.getUserId()) && "watcher".equals(l.getType())));
        assertTrue(svc.links(t.getId()).stream().anyMatch(l -> "g1".equals(l.getGroupId()) && "x-escalation".equals(l.getType())));
        svc.removeUserLink(t.getId(), "u1", "watcher");
        assertTrue(svc.links(t.getId()).stream().noneMatch(l -> "u1".equals(l.getUserId())));
    }

    @Test
    void instanceParticipantsAreTenantScoped() {
        TaskEnhancementService svc = new TaskEnhancementService(h2.engine, h2.plugin.links(), false);
        svc.addInstanceParticipant("t1", "process", "pi-9", "u1", "participant", "admin");
        svc.addInstanceParticipant("t1", "process", "pi-9", "u2", "watcher", "admin");
        svc.addInstanceParticipant("t2", "process", "pi-9", "intruder", "participant", "admin");
        assertThrows(IllegalArgumentException.class, () -> svc.addInstanceParticipant("t1", "process", "pi-9", "u3", "boss", "admin"));
        assertEquals(List.of("u1", "u2"), svc.involvedUsers("t1", "process", "pi-9").stream().sorted().toList());
        assertEquals(List.of("intruder"), svc.involvedUsers("t2", "process", "pi-9"));
        assertTrue(svc.involvedUsers("t3", "process", "pi-9").isEmpty());
    }
}
