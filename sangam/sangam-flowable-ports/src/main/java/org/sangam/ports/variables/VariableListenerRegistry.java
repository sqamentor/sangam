// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.variables;

import org.sangam.ports.history.EngineEventBus;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Variable listeners (FC-05 runtime part). Flowable's {@code variableListener} boundary/intermediate events map to
 * Operaton conditional events with {@code variableName}/{@code variableEvents}, which Operaton evaluates automatically
 * (B-2; the import mapping is {@code FlowableModelImporter}). This registry adds the code-level counterpart: a Java
 * callback when a named variable of a process definition is created, updated or deleted, fed from the engine event
 * bus (history events at level FULL or the per-definition level, FC-26). Callbacks run in the engine transaction.
 */
public class VariableListenerRegistry {

    private final EngineEventBus bus;

    public VariableListenerRegistry(EngineEventBus bus) {
        this.bus = bus;
    }

    /**
     * @param processDefinitionKey null for every definition
     * @param variableName         variable to watch
     * @param events               subset of {@code create, update, delete}; empty means all
     */
    public AutoCloseable register(String processDefinitionKey, String variableName, Set<String> events,
                                  Consumer<EngineEventBus.EngineEvent> callback) {
        return bus.register("variable.", ev -> {
            Object name = ev.data().get("variableName");
            Object key = ev.data().get("processDefinitionKey");
            String type = ev.type().substring("variable.".length());
            if (variableName.equals(name) && (processDefinitionKey == null || processDefinitionKey.equals(key))
                    && (events.isEmpty() || events.contains(type))) {
                callback.accept(ev);
            }
        }, true);
    }
}
