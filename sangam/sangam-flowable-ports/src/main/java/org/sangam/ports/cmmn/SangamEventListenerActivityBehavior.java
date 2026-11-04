// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.calendar.BusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.CycleBusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.DueDateBusinessCalendar;
import org.operaton.bpm.engine.impl.calendar.DurationBusinessCalendar;
import org.operaton.bpm.engine.impl.cmmn.behavior.EventListenerActivityBehavior;
import org.operaton.bpm.engine.impl.cmmn.execution.CmmnActivityExecution;
import org.operaton.bpm.engine.impl.cmmn.model.CmmnActivity;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.persistence.entity.TimerEntity;

import java.util.Date;

/**
 * Event listener behaviour with the {@code created} step Operaton leaves as a TODO (FC-55).
 * <ul>
 *   <li><b>Timer event listener</b> ({@code timerExpression} = ISO date, duration, or cycle; calendar tags of
 *       FC-23 work): a timer job of type {@link CmmnTimerJobHandler#TYPE} is scheduled for the case execution. When it
 *       fires, the listener occurs, which satisfies sentries that wait on its {@code occur} transition. CMMN event
 *       listeners are not repeatable, so a cycle expression fires once, at its first due date.</li>
 *   <li><b>User / generic event listener</b>: nothing to schedule; it occurs through the product API.</li>
 * </ul>
 * The timer expression is stored on the activity by {@link SangamCmmnTransformListener} (property
 * {@link #PROPERTY_TIMER_EXPRESSION}).
 */
public class SangamEventListenerActivityBehavior extends EventListenerActivityBehavior {

    public static final String PROPERTY_TIMER_EXPRESSION = "sangam.timerExpression";
    public static final String PROPERTY_LISTENER_KIND = "sangam.eventListenerKind";

    @Override
    public void created(CmmnActivityExecution execution) {
        CmmnActivity activity = execution.getActivity();
        Object expr = activity.getProperty(PROPERTY_TIMER_EXPRESSION);
        if (!"timer".equals(activity.getProperty(PROPERTY_LISTENER_KIND)) || expr == null) {
            return;
        }
        String text = expr.toString().trim();
        Object evaluated = Context.getProcessEngineConfiguration().getExpressionManager().createExpression(text).getValue(execution);
        String value = evaluated instanceof Date d ? null : String.valueOf(evaluated);
        Date due;
        if (evaluated instanceof Date d) {
            due = d;
        } else {
            String bare = value.startsWith("cal:") ? value.substring(value.indexOf(':', 4) + 1) : value;
            String type = bare.startsWith("R") ? CycleBusinessCalendar.NAME : bare.startsWith("P") ? DurationBusinessCalendar.NAME
                    : DueDateBusinessCalendar.NAME;
            BusinessCalendar cal = Context.getProcessEngineConfiguration().getBusinessCalendarManager().getBusinessCalendar(type);
            due = cal.resolveDuedate(value);
        }
        if (due == null) {
            throw new ProcessEngineException("timer event listener " + activity.getId() + ": expression '" + text + "' has no due date");
        }
        TimerEntity timer = new TimerEntity();
        timer.setJobHandlerType(CmmnTimerJobHandler.TYPE);
        timer.setJobHandlerConfigurationRaw(execution.getId());
        timer.setDuedate(due);
        timer.setTenantId(execution.getTenantId());
        timer.setExclusive(true);
        Context.getCommandContext().getJobManager().schedule(timer);
    }
}
