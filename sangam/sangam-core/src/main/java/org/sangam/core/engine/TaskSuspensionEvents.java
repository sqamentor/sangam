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
        purgeReleased();
        List<Listener> l = new CopyOnWriteArrayList<>();
        l.add(listener);
        BY_ENGINE.put(key(engineName), l);
    }

    /** A listener method of an owner object, called with the owner, so the lambda itself captures nothing. */
    @FunctionalInterface
    public interface OwnedListener<T> {
        void changed(T owner, List<ChangedTask> tasks, boolean suspended);
    }

    /**
     * As {@link #register}, but the registry holds {@code owner} only weakly. The owner (e.g. the ports' task event
     * dispatcher) is kept alive by its engine configuration; once the engine is closed and unreachable, the owner and
     * the whole engine (MyBatis configuration, caches, deployed models) can be collected, and the entry is purged on
     * the next registration. A strong registration of a per-engine object, with no engine-close hook to remove it,
     * kept every closed engine of the JVM reachable from this static map (R8-heap: ~40 MB per engine, test JVMs ran out
     * of heap). {@code listener} must not capture {@code owner}; pass an unbound method reference.
     */
    public static <T> void registerWeakly(String engineName, T owner, OwnedListener<? super T> listener) {
        register(engineName, new WeakListener<>(owner, listener));
    }

    private static final class WeakListener<T> implements Listener {
        private final java.lang.ref.WeakReference<T> owner;
        private final OwnedListener<? super T> target;

        WeakListener(T owner, OwnedListener<? super T> target) {
            this.owner = new java.lang.ref.WeakReference<>(java.util.Objects.requireNonNull(owner, "owner"));
            this.target = java.util.Objects.requireNonNull(target, "listener");
        }

        @Override
        public void changed(List<ChangedTask> tasks, boolean suspended) {
            T o = owner.get();
            if (o != null) {
                target.changed(o, tasks, suspended);
            }
        }

        boolean released() {
            return owner.get() == null;
        }
    }

    private static boolean released(Listener l) {
        return l instanceof WeakListener<?> w && w.released();
    }

    /** Drops the engines whose listeners are all weak and released (their engine was closed and collected). */
    private static void purgeReleased() {
        BY_ENGINE.values().removeIf(l -> !l.isEmpty() && l.stream().allMatch(TaskSuspensionEvents::released));
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
        return l != null && l.stream().anyMatch(x -> !released(x));
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
