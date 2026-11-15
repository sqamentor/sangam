// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.operaton.bpm.engine.externaltask.ExternalTask;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.db.entitymanager.DbEntityManager;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.CachedDbEntity;
import org.operaton.bpm.engine.impl.db.entitymanager.cache.DbEntityState;
import org.operaton.bpm.engine.impl.event.EventHandlerImpl;
import org.operaton.bpm.engine.impl.event.EventType;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;
import org.operaton.bpm.engine.impl.interceptor.Session;
import org.operaton.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExternalTaskEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.sangam.engine.tx.CommandState;

/**
 * Decision 151: the engine's capture points besides FEEL clock reads ({@link CapturingFeelClock}), kept out of
 * {@code OperatonEngineAdapter} (class-size ceiling). Built once per engine by {@code SangamEngineComponents};
 * {@link #NONE} (no listener, every production engine) does nothing at all.
 *
 * <table>
 *   <caption>Capture points</caption>
 *   <tr><th>Input</th><th>Where</th><th>Payload (W4-G {@code ReplayFixture.EventType} docs)</th></tr>
 *   <tr><td>{@code TIMER_SCHEDULED}</td><td>{@link #interceptor()}: at command close, each new ({@code TRANSIENT})
 *       {@code TimerEntity} of the entity cache, follow-up cycle timers included</td><td>{@code timerId},
 *       {@code dueAt}</td></tr>
 *   <tr><td>{@code TIMER_FIRED}</td><td>{@link #timerFired}: {@code GuardedJobHandler}, after the DST guard said FIRE,
 *       before the timer's effect</td><td>{@code timerId}</td></tr>
 *   <tr><td>{@code MESSAGE_DELIVERED}</td><td>{@link #messageEventHandler()} (replaces Operaton's {@code message} event
 *       handler, same behaviour) for every delivery to a waiting execution; {@link #messageStart} in the adapter before
 *       a correlation that will start an instance</td><td>{@code messageName}, {@code correlationKey} (decision 332:
 *       the key the adapter that issued the correlation was called with, handed over through
 *       {@link CorrelationKeyHandover}; null on every other path), the message variables</td></tr>
 *   <tr><td>{@code EXTERNAL_TASK_RESULT}</td><td>{@link #externalResult}: the adapter's complete, fail and BPMN-error
 *       calls after the lease check; {@link #externalTimeout}: the lease-expiry sweep ({@code TIMEOUT})</td>
 *       <td>result variables; {@code errorMessage} or {@code errorCode}</td></tr>
 * </table>
 *
 * A value the fixture cannot carry exactly (a double, a float, an object, a file) or a variable whose name collides
 * with a payload key is not converted: the listener's {@link CaptureListener#unsupported} is called with the reason.
 */
public class CapturePoints {

    /** No listener: every method returns at once. */
    public static final CapturePoints NONE = new CapturePoints(null, () -> null);

    private final CaptureListener listener;
    private final Supplier<Instant> engineClock;
    /** Attempts per timer job (TIMER_FIRED); a retried firing counts up. */
    private final Map<String, Integer> timerAttempts = new ConcurrentHashMap<>();

    CapturePoints(CaptureListener listener, Supplier<Instant> engineClock) {
        this.listener = listener;
        this.engineClock = engineClock;
    }

    /** {@link #NONE} when {@code listener} is null. */
    public static CapturePoints of(CaptureListener listener, Supplier<Instant> engineClock) {
        return listener == null ? NONE : new CapturePoints(listener, Objects.requireNonNull(engineClock, "engineClock"));
    }

    public boolean active() {
        return listener != null;
    }

    // ------------------------------------------------------------------ timers

    /** TIMER_FIRED for a timer job about to take effect. */
    public void timerFired(TimerEntity timer) {
        if (listener == null || timer == null) {
            return;
        }
        int attempt = timerAttempts.merge(timer.getId(), 1, Integer::sum);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("timerId", timer.getId());
        emit(new CapturedInput(CapturedInput.TIMER_FIRED, timer.getExecutionId(), timer.getActivityId(),
                activityInstanceOf(timer.getExecution()), attempt, now(), null, null, p));
    }

    /** The post command interceptor that reports new timers (TIMER_SCHEDULED) at command close; null when inactive. */
    public CommandInterceptor interceptor() {
        if (listener == null) {
            return null;
        }
        CommandContextListener onClose = new CommandContextListener() {
            @Override
            public void onCommandContextClose(CommandContext cc) {
                timersScheduled(cc);
            }

            @Override
            public void onCommandFailed(CommandContext cc, Throwable t) {
                // nothing scheduled
            }
        };
        return new CommandInterceptor() {
            @Override
            public <T> T execute(Command<T> command) {
                if (!Context.hasActiveCommandContext()) {
                    return next.execute(command);
                }
                CommandContext cc = Context.getCommandContext();
                // registerCommandContextListener ignores a listener that is already registered (javap 2.2.0)
                cc.registerCommandContextListener(onClose);
                // decision 332: the command depth tells the adapter's own correlation from one issued inside it
                CommandState state = CommandState.of(cc);
                state.enterCommand();
                try {
                    return next.execute(command);
                } finally {
                    state.exitCommand();
                }
            }
        };
    }

    void timersScheduled(CommandContext cc) {
        DbEntityManager em = null;
        for (Session s : cc.getSessions().values()) {
            if (s instanceof DbEntityManager m) {
                em = m;
            }
        }
        if (em == null) {
            return;
        }
        List<TimerEntity> created = new ArrayList<>();
        for (CachedDbEntity cached : em.getDbEntityCache().getCachedEntities()) {
            if (cached.getEntity() instanceof TimerEntity t && cached.getEntityState() == DbEntityState.TRANSIENT) {
                created.add(t);
            }
        }
        Instant at = created.isEmpty() ? null : now();
        for (TimerEntity t : created) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("timerId", t.getId());
            p.put("dueAt", t.getDuedate() == null ? null : t.getDuedate().toInstant());
            emit(new CapturedInput(CapturedInput.TIMER_SCHEDULED, t.getExecutionId(), t.getActivityId(), null, 1, at,
                    null, null, p));
        }
    }

    // ------------------------------------------------------------------ messages

    /**
     * Operaton's {@code message} event handler with a capture first: every message delivered to a waiting execution
     * (intermediate catch, receive task, boundary, event-subprocess start), on every correlation path.
     */
    public EventHandlerImpl messageEventHandler() {
        return new EventHandlerImpl(EventType.MESSAGE) {
            @Override
            public void handleEvent(EventSubscriptionEntity sub, Object payload, Object localPayload,
                                    Object payloadToTriggeredScope, String businessKey, CommandContext cc) {
                if (listener != null) {
                    Map<String, Object> vars = new LinkedHashMap<>(asMap(payload));
                    for (Map.Entry<String, Object> e : asMap(localPayload).entrySet()) {
                        if (vars.containsKey(e.getKey())) {
                            listener.unsupported("message " + sub.getEventName() + ": variable " + e.getKey()
                                    + " is both a message and a local variable");
                        }
                        vars.put(e.getKey(), e.getValue());
                    }
                    // decision 332: Operaton passes no business key for a delivery to a waiting execution; the key is
                    // the one the adapter that issued this correlation was called with, null on every other path
                    String key = businessKey != null ? businessKey : CommandState.of(cc).deliveredCorrelationKey();
                    message(sub.getEventName(), key, vars, sub.getExecutionId(), sub.getActivityId(),
                            activityInstanceOf(sub.getExecution()));
                }
                super.handleEvent(sub, payload, localPayload, payloadToTriggeredScope, businessKey, cc);
            }
        };
    }

    /**
     * A message correlation that will start an instance (no execution waits for it: {@code noWaitingExecution}),
     * reported before the start so it precedes every input the new instance takes. Execution and activity are not
     * known yet (null).
     */
    public void messageStart(boolean noWaitingExecution, String messageName, String businessKey,
                             Map<String, Object> variables) {
        if (listener != null && noWaitingExecution) {
            message(messageName, businessKey, variables, null, null, null);
        }
    }

    /**
     * The adapter's message correlation with its two capture duties: {@link #messageStart} first (decision 151), then
     * the correlation with the key the adapter was called with handed over to the delivery's capture point
     * (decision 332, {@link CorrelationKeyHandover#within}).
     */
    public <T> T correlate(CommandContext cc, boolean noWaitingExecution, String messageName, String businessKey,
                           Map<String, Object> variables, Supplier<T> correlation) {
        messageStart(noWaitingExecution, messageName, businessKey, variables);
        return CorrelationKeyHandover.within(cc, businessKey, correlation);
    }

    private void message(String name, String correlationKey, Map<String, Object> variables, String executionId,
                         String activityId, String activityInstanceId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("messageName", name);
        p.put("correlationKey", correlationKey);
        putVariables(p, variables, "message " + name);
        emit(new CapturedInput(CapturedInput.MESSAGE_DELIVERED, executionId, activityId, activityInstanceId, 1, now(),
                null, null, p));
    }

    // ------------------------------------------------------------------ external tasks

    /**
     * EXTERNAL_TASK_RESULT for a worker's complete ({@code SUCCESS}), failure ({@code FAILURE}) or BPMN error
     * ({@code BPMN_ERROR}), inside the adapter's command after the lease check and before Operaton applies it.
     *
     * @param attemptNo the lease's attempt number ({@code SangamLeaseTable.Row.attemptNo})
     * @param errorKey  {@code errorMessage} or {@code errorCode}, null for a completion
     */
    public void externalResult(String workId, int attemptNo, String outcome, Integer retriesLeft,
                               Map<String, Object> variables, String errorKey, String errorValue) {
        if (listener == null) {
            return;
        }
        ExternalTaskEntity t = Context.getCommandContext().getExternalTaskManager().findExternalTaskById(workId);
        Map<String, Object> p = new LinkedHashMap<>();
        if (errorKey != null) {
            p.put(errorKey, errorValue);
        }
        putVariables(p, variables, "external task " + workId);
        emit(new CapturedInput(CapturedInput.EXTERNAL_TASK_RESULT, t == null ? null : t.getExecutionId(),
                t == null ? null : t.getActivityId(), t == null ? null : t.getActivityInstanceId(), Math.max(1, attemptNo),
                now(), outcome, retriesLeft, p));
    }

    /** EXTERNAL_TASK_RESULT {@code TIMEOUT}: the lease-expiry sweep ended an expired lease of {@code task}. */
    public void externalTimeout(ExternalTask task, int attemptNo) {
        if (listener == null || task == null) {
            return;
        }
        emit(new CapturedInput(CapturedInput.EXTERNAL_TASK_RESULT, task.getExecutionId(), task.getActivityId(),
                task.getActivityInstanceId(), Math.max(1, attemptNo), now(), "TIMEOUT", null, Map.of()));
    }

    // ------------------------------------------------------------------ plumbing

    private void emit(CapturedInput in) {
        listener.onInput(in);
    }

    private Instant now() {
        return Objects.requireNonNull(engineClock.get(), "engine clock");
    }

    private static String activityInstanceOf(ExecutionEntity e) {
        return e == null ? null : e.getActivityInstanceId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload) {
        return payload instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Adds the variables converted to replayable values; anything else is reported unsupported and left out. */
    void putVariables(Map<String, Object> p, Map<String, Object> variables, String where) {
        if (variables == null) {
            return;
        }
        for (Map.Entry<String, Object> e : variables.entrySet()) {
            if (p.containsKey(e.getKey())) {
                listener.unsupported(where + ": variable " + e.getKey() + " has the name of a payload key");
                continue;
            }
            Object v = e.getValue() instanceof TypedValue tv ? tv.getValue() : e.getValue();
            Object r = replayable(v);
            if (v != null && r == null) {
                listener.unsupported(where + ": variable " + e.getKey() + " is a " + v.getClass().getName()
                        + ", which a replay fixture cannot carry exactly");
                continue;
            }
            p.put(e.getKey(), r);
        }
    }

    /** The exact replayable form, or null when there is none (a null input gives null). */
    static Object replayable(Object v) {
        if (v == null || v instanceof BigDecimal || v instanceof Long || v instanceof Boolean || v instanceof String
                || v instanceof Instant || v instanceof ZonedDateTime) {
            return v;
        }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (v instanceof Date d) {
            return d.toInstant();
        }
        return null; // Double, Float, objects, files, JSON: not exact
    }
}
