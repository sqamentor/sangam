// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports;

import org.sangam.core.layer.Dialect;
import org.sangam.ports.events.OutboundEventService;
import org.sangam.ports.history.EngineEventBus;
import org.sangam.ports.task.TaskLifecycleService;
import org.sangam.ports.variables.MultiInstanceAggregation;

import java.util.Objects;

/**
 * Process-wide access point for services used from engine callbacks that the engine instantiates by class name
 * (delegates, listeners, behaviours). Sangam runs one engine per JVM (FLOWABLE-INTO-OPERATON D-10 containment), so one context is enough;
 * {@link SangamPortsPlugin} fills it when the engine is built.
 */
public final class PortsContext {

    private static volatile PortsContext current;

    private final Dialect dialect;
    private final OutboundEventService outboundEvents;
    private final EngineEventBus eventBus;
    private final TaskLifecycleService taskLifecycle;
    private final MultiInstanceAggregation aggregation;

    public PortsContext(Dialect dialect, OutboundEventService outboundEvents, EngineEventBus eventBus,
                        TaskLifecycleService taskLifecycle, MultiInstanceAggregation aggregation) {
        this.dialect = Objects.requireNonNull(dialect);
        this.outboundEvents = outboundEvents;
        this.eventBus = eventBus;
        this.taskLifecycle = taskLifecycle;
        this.aggregation = aggregation;
    }

    public static void install(PortsContext ctx) {
        current = ctx;
    }

    public static PortsContext get() {
        PortsContext c = current;
        if (c == null) {
            throw new IllegalStateException("Sangam ports are not initialised (SangamPortsPlugin not registered)");
        }
        return c;
    }

    public static boolean installed() {
        return current != null;
    }

    public Dialect dialect() {
        return dialect;
    }

    public OutboundEventService outboundEvents() {
        if (outboundEvents == null) {
            throw new IllegalStateException("event registry is not enabled");
        }
        return outboundEvents;
    }

    public EngineEventBus eventBus() {
        return eventBus;
    }

    public TaskLifecycleService taskLifecycle() {
        return taskLifecycle;
    }

    public MultiInstanceAggregation aggregation() {
        return aggregation;
    }
}
