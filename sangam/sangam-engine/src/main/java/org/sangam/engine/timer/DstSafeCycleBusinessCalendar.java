// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import org.operaton.bpm.engine.impl.ProcessEngineLogger;
import org.operaton.bpm.engine.impl.calendar.CycleBusinessCalendar;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.core.time.EngineTimeZone;
import org.sangam.engine.tx.CommandState;

/**
 * Replacement of Operaton's {@code cycle} business calendar (A-12, R31, FLOWABLE-INTO-OPERATON D-01, FLOWABLE-INTO-OPERATON D-02, FLOWABLE-INTO-OPERATON D-29, FLOWABLE-INTO-OPERATON D-30, FLOWABLE-INTO-OPERATON D-31), registered by
 * the plugin in the {@code MapBusinessCalendarManager}. It must extend {@link CycleBusinessCalendar}:
 * {@code TimerEntity.calculateNewDueDate} calls it through that class (2.2.0 bytecode).
 *
 * <p>ISO repeating intervals are computed by {@link CycleExpression} (UTC elapsed cadence, anchored calendar cadence),
 * never by {@code DurationHelper}. The next due date is always strictly after {@code max(now, current due)}, so a
 * timer can never be rescheduled into the past and loop (the E8 runaway); with {@code FIRE_ALL} it is the slot right
 * after the current one until the backlog exceeds {@code maxCatchUpFires} periods. {@code Rn} ends after n firings.
 * Cron expressions are parsed as Operaton parses them and evaluated on the wall clock of the timer zone
 * ({@link ZonedCron}).
 *
 * <p><b>Zones (SL-53).</b> Calendar cadence and cron use the timer zone ({@code sangam.engine.timer-zone}); elapsed
 * cadence runs on the UTC timeline; a start or end without an offset is engine-zone UTC, the zone Operaton writes the
 * start it inserts in (the JVM runs in UTC, {@link EngineTimeZone}). The JVM default zone is never consulted.
 */
public class DstSafeCycleBusinessCalendar extends CycleBusinessCalendar {

    private final SangamClock clock;
    private final TimerGuard guard;
    private final TimerPolicies policies;

    public DstSafeCycleBusinessCalendar(SangamClock clock, TimerGuard guard, TimerPolicies policies) {
        this.clock = clock;
        this.guard = guard;
        this.policies = policies;
    }

    @Override
    public Date resolveDuedate(String expression, Date startDate, long repeatOffset) {
        Instant now = clock.now();
        TimerEntity current = currentTimer();
        if (!CycleExpression.isRepeating(expression)) {
            return cron(expression, startDate != null ? startDate.toInstant() : now);
        }
        // offset-less start/end: engine-zone (UTC) wall time, the zone Operaton writes the inserted start in (SL-53)
        CycleExpression cycle = CycleExpression.parse(expression, EngineTimeZone.ZONE);
        Instant anchor = startDate != null ? startDate.toInstant() : now;
        Instant after = now;
        if (current != null && current.getDuedate() != null && current.getRepeat() != null) {
            Instant due = current.getDuedate().toInstant();
            if (cycle.times() != null) {
                long fired = guard.fireCount(CommandState.currentConnection(), TimerGuard.seriesKey(current));
                if (fired + 1 >= cycle.times()) {
                    return null; // the firing being executed is the n-th: the series ends (FLOWABLE-INTO-OPERATON D-29)
                }
            }
            TimerCatchUpPolicy policy = policies.policyFor(current.getProcessDefinitionKey(), current.getActivityId());
            long period = Math.max(1, cycle.nominalPeriodMillis());
            boolean replay = policy == TimerCatchUpPolicy.FIRE_ALL
                    && now.toEpochMilli() - due.toEpochMilli() <= (long) policies.maxCatchUpFires() * period;
            after = replay ? due : (due.isAfter(now) ? due : now);
        } else if (cycle.times() != null && cycle.times() <= 0) {
            return null;
        }
        Instant next = cycle.slotAfter(after, anchor, guard.cadenceZone());
        return next == null ? null : Date.from(next.plusMillis(repeatOffset));
    }

    /**
     * Cron (SL-53): the next execution on the wall clock of the timer zone, after the engine clock (or after
     * {@code startDate} when the engine passes one, as Operaton does). The engine clock is at or after the due date of
     * the firing being executed, so the next due date cannot fall back; a local wall-clock time that repeats at an
     * autumn fold maps to the same {@link TimerGuard} interval and fires once. Parse errors are reported as Operaton
     * reports them.
     */
    private Date cron(String expression, Instant after) {
        ZonedCron cron;
        try {
            cron = ZonedCron.parse(expression);
        } catch (ParseException e) {
            throw ProcessEngineLogger.UTIL_LOGGER.exceptionWhileParsingCycleExpresison(expression, e);
        }
        Instant next = cron.nextAfter(after, guard.cadenceZone());
        return next == null ? null : Date.from(next);
    }

    private static TimerEntity currentTimer() {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null) {
            return null;
        }
        JobEntity job = cc.getCurrentJob();
        return job instanceof TimerEntity t ? t : null;
    }
}
