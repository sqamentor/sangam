// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SG-03(c): task suspension and activation as events, engine-neutral. Operaton never suspends a task through the task
 * entity: suspending or activating a process instance, a process definition (with its instances), a batch of instances,
 * a delayed (timer) definition change or a case execution ends in one bulk {@code UPDATE} of
 * {@code TaskManager.updateTaskSuspensionStateBy*}, which fires no task listener. sangam-engine's TaskManager
 * ({@code TenantScopedCreation.Tasks}) captures the tasks whose state that update changes and reports them here, in the
 * same command and transaction; the listeners of the engine (the ports' {@code TaskEventDispatcher}) turn each into one
 * {@code task.suspend} / {@code task.activate} event.
 *
 * <p>Listeners are registered per process engine name, so two engines in one JVM never see each other's tasks.
 */
public final class TaskSuspensionEvents {

    /** One task whose suspension state the update changed (its values before the update). */
    public record ChangedTask(String taskId, String tenantId, String processInstanceId, String caseInstanceId,
                              String taskDefinitionKey, String assignee, String owner) {
    }

    /** Receives the tasks of one update; {@code suspended} is the new state. Runs in the engine command. */
    @FunctionalInterface
    public interface Listener {
        void changed(List<ChangedTask> tasks, boolean suspended);
    }

    private static final Map<String, List<Listener>> BY_ENGINE = new ConcurrentHashMap<>();

    private TaskSuspensionEvents() {
    }

    /**
     * Sets THE listener of the engine, replacing any earlier one: an engine rebuilt under the same name (tests, a
     * restart in one JVM) must not keep the listener of its closed predecessor, which would emit every event twice.
     */
    public static void register(String engineName, Listener listener) {
        List<Listener> l = new CopyOnWriteArrayList<>();
        l.add(listener);
        BY_ENGINE.put(key(engineName), l);
    }

    public static void unregister(String engineName, Listener listener) {
        List<Listener> l = BY_ENGINE.get(key(engineName));
        if (l != null) {
            l.remove(listener);
        }
    }

    /** True when the engine has a listener, so the TaskManager can skip the extra read otherwise. */
    public static boolean hasListeners(String engineName) {
        List<Listener> l = BY_ENGINE.get(key(engineName));
        return l != null && !l.isEmpty();
    }

    /** Reports the tasks of one update to every listener of the engine; nothing when the list is empty. */
    public static void fire(String engineName, List<ChangedTask> tasks, boolean suspended) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<Listener> l = BY_ENGINE.get(key(engineName));
        if (l == null) {
            return;
        }
        List<ChangedTask> copy = List.copyOf(tasks);
        for (Listener listener : l) {
            listener.changed(copy, suspended);
        }
    }

    private static String key(String engineName) {
        return engineName == null ? "" : engineName;
    }
}
