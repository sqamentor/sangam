// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Global typed event dispatcher (FC-43, Flowable {@code FlowableEventDispatcher}). Events come from the history
 * handler (process, activity, task, variable, case), from {@code TaskEventDispatcher} (fine-grained task events) and
 * from the event registry. Listeners run synchronously inside the engine transaction; a listener registered as
 * {@code failOnException=false} cannot break the command (its exception is logged).
 *
 * <p>Event types: {@code process.start|end|update|migrate}, {@code activity.start|end}, {@code task.create|update|
 * complete|delete|claim|unclaim|delegate|resolve|owner|due-date|priority|in-progress}, {@code variable.create|update|
 * delete}, {@code case.*}, {@code caseActivity.*}.
 */
public class EngineEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(EngineEventBus.class);

    public record EngineEvent(String type, String tenantId, String processInstanceId, String caseInstanceId,
                              String activityId, String taskId, Map<String, Object> data) {
    }

    private record Registration(String typePrefix, Consumer<EngineEvent> listener, boolean failOnException) {
    }

    private final List<Registration> listeners = new CopyOnWriteArrayList<>();

    /** {@code typePrefix} "" receives everything; "task." receives every task event. */
    public AutoCloseable register(String typePrefix, Consumer<EngineEvent> listener, boolean failOnException) {
        Registration r = new Registration(typePrefix, listener, failOnException);
        listeners.add(r);
        return () -> listeners.remove(r);
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public void dispatch(EngineEvent event) {
        for (Registration r : listeners) {
            if (!event.type().startsWith(r.typePrefix())) {
                continue;
            }
            try {
                r.listener().accept(event);
            } catch (RuntimeException e) {
                if (r.failOnException()) {
                    throw e;
                }
                LOG.warn("engine event listener for '{}' failed on {}: {}", r.typePrefix(), event.type(), e.toString());
            }
        }
    }
}
