// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.operaton.bpm.engine.impl.context.BpmnExecutionContext;
import org.operaton.bpm.engine.impl.context.CaseExecutionContext;
import org.operaton.bpm.engine.impl.context.Context;
import org.sangam.feel.FeelClock;

/**
 * Decision 151 (integrator ruling 2, option A): the FEEL clock of an engine with a {@link CaptureListener}. FEEL
 * {@code now()} and {@code today()} read the engine clock (the clock the engine's timers use, steerable in test mode
 * and by a replay driver) instead of the JVM clock, and every read is reported as {@code CLOCK_READ} with the
 * execution that evaluated the expression (BPMN or CMMN execution context; null ids for a standalone decision).
 * Installed only when a listener is registered; without one FEEL keeps feel-scala's default clock.
 */
public final class CapturingFeelClock implements FeelClock {

    private final Supplier<Instant> engineClock;
    private final ZoneId zone;
    private final CaptureListener listener;

    /**
     * @param engineClock the engine clock
     * @param zone        the zone FEEL's date-time carries; feel-scala's default clock uses the JVM default zone
     */
    public CapturingFeelClock(Supplier<Instant> engineClock, ZoneId zone, CaptureListener listener) {
        this.engineClock = Objects.requireNonNull(engineClock, "engineClock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public ZonedDateTime now() {
        Instant at = Objects.requireNonNull(engineClock.get(), "engine clock");
        String executionId = null;
        String activityId = null;
        String activityInstanceId = null;
        // getCoreExecutionContext and instanceof: Context.getBpmnExecutionContext / getCaseExecutionContext cast the top of
        // the stack unchecked (2.2.0 bytecode), so the wrong one would throw inside a CMMN or BPMN evaluation
        Object top = Context.getCoreExecutionContext();
        if (top instanceof BpmnExecutionContext bpmn && bpmn.getExecution() != null) {
            executionId = bpmn.getExecution().getId();
            activityId = bpmn.getExecution().getActivityId();
            activityInstanceId = bpmn.getExecution().getActivityInstanceId();
        } else if (top instanceof CaseExecutionContext cmmn && cmmn.getExecution() != null) {
            executionId = cmmn.getExecution().getId();
            activityId = cmmn.getExecution().getActivityId();
        }
        listener.onInput(new CapturedInput(CapturedInput.CLOCK_READ, executionId, activityId, activityInstanceId, 1, at,
                null, null, Map.of()));
        return ZonedDateTime.ofInstant(at, zone);
    }
}
