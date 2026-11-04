// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.timer;

import java.time.Instant;
import java.util.Date;

import org.operaton.bpm.engine.impl.calendar.CycleBusinessCalendar;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.JobEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;
import org.sangam.core.engine.SangamClock;
import org.sangam.core.engine.TimerCatchUpPolicy;
import org.sangam.engine.tx.CommandState;

/**
 * Replacement of Operaton's {@code cycle} business calendar (A-12, R31, D-01, D-02, D-29, D-30, D-31), registered by
 * the plugin in the {@code MapBusinessCalendarManager}. It must extend {@link CycleBusinessCalendar}:
 * {@code TimerEntity.calculateNewDueDate} calls it through that class (2.2.0 bytecode).
 *
 * <p>ISO repeating intervals are computed by {@link CycleExpression} (UTC elapsed cadence, anchored calendar cadence),
 * never by {@code DurationHelper}. The next due date is always strictly after {@code max(now, current due)}, so a
 * timer can never be rescheduled into the past and loop (the E8 runaway); with {@code FIRE_ALL} it is the slot right
 * after the current one until the backlog exceeds {@code maxCatchUpFires} periods. {@code Rn} ends after n firings.
 * Cron expressions keep Operaton's evaluation, with the same monotonic guard.
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
            // Cron: Operaton computes the next fire time after the engine clock, which is at or after the due date of
            // the firing being executed, so it cannot fall back; a repeated local wall-clock time at a DST fold is
            // suppressed by the TimerGuard (same wall-clock interval).
            return super.resolveDuedate(expression, startDate, repeatOffset);
        }
        CycleExpression cycle = CycleExpression.parse(expression, guard.localZone());
        Instant anchor = startDate != null ? startDate.toInstant() : now;
        Instant after = now;
        if (current != null && current.getDuedate() != null && current.getRepeat() != null) {
            Instant due = current.getDuedate().toInstant();
            if (cycle.times() != null) {
                long fired = guard.fireCount(CommandState.currentConnection(), TimerGuard.seriesKey(current));
                if (fired + 1 >= cycle.times()) {
                    return null; // the firing being executed is the n-th: the series ends (D-29)
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

    private static TimerEntity currentTimer() {
        CommandContext cc = Context.hasActiveCommandContext() ? Context.getCommandContext() : null;
        if (cc == null) {
            return null;
        }
        JobEntity job = cc.getCurrentJob();
        return job instanceof TimerEntity t ? t : null;
    }
}
