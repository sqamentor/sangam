// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.core.engine.TaskSuspensionEvents;
import org.sangam.ports.history.EngineEventBus;

/**
 * SG-03(c), ports side: the tasks reported by sangam-engine's TaskManager become exactly one {@code task.suspend} or
 * {@code task.activate} per task on the {@link EngineEventBus}, with the data shape of the other derived task events.
 */
class TaskSuspensionDispatchTest {

    static TaskSuspensionEvents.ChangedTask task(String id) {
        return new TaskSuspensionEvents.ChangedTask(id, "t1", "pi-" + id, null, "review", "alice", null);
    }

    @Test
    void oneEventPerReportedTaskWithTheSuspensionChange() throws Exception {
        EngineEventBus bus = new EngineEventBus();
        List<EngineEventBus.EngineEvent> seen = new ArrayList<>();
        try (AutoCloseable reg = bus.register("task.", seen::add, true)) {
            TaskEventDispatcher d = new TaskEventDispatcher(bus, null);
            d.suspensionChanged(List.of(task("a"), task("b")), true);
            d.suspensionChanged(List.of(task("a")), false);
        }
        assertEquals(List.of("task.suspend", "task.suspend", "task.activate"), seen.stream().map(EngineEventBus.EngineEvent::type).toList());
        EngineEventBus.EngineEvent first = seen.get(0);
        assertEquals("a", first.taskId());
        assertEquals("t1", first.tenantId());
        assertEquals("pi-a", first.processInstanceId());
        assertEquals("2", first.data().get("new"));
        assertEquals("1", seen.get(2).data().get("new"));
        assertEquals("alice", first.data().get("assignee"));
    }

    @Test
    void theListenerRegisteredForAnEngineReceivesOnlyItsEnginesTasks() {
        List<String> got = new ArrayList<>();
        TaskSuspensionEvents.Listener l = (tasks, suspended) -> tasks.forEach(t -> got.add(t.taskId()));
        TaskSuspensionEvents.register("engine-x", l);
        TaskSuspensionEvents.Listener replaced = (tasks, suspended) -> got.add("stale");
        try {
            TaskSuspensionEvents.fire("engine-x", List.of(task("a")), true);
            TaskSuspensionEvents.fire("engine-y", List.of(task("b")), true);
            TaskSuspensionEvents.register("engine-x", replaced);
            TaskSuspensionEvents.register("engine-x", l); // a rebuilt engine replaces its predecessor's listener
            TaskSuspensionEvents.fire("engine-x", List.of(task("c")), false);
            TaskSuspensionEvents.fire("engine-x", List.of(), false);
        } finally {
            TaskSuspensionEvents.unregister("engine-x", l);
        }
        assertEquals(List.of("a", "c"), got);
    }
}
