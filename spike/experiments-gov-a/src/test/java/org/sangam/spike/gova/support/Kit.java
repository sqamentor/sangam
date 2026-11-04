package org.sangam.spike.gova.support;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.sangam.spike.api.EngineAdapter;

/**
 * Everything engine-specific that E16-a / E17-a need beyond {@link EngineAdapter}:
 * <ol>
 *   <li>model variants: the BPMN fragments that standard BPMN 2.0.2 cannot express (each use is recorded as a
 *       variant in the scenario JSON);</li>
 *   <li>native engine API (each use is recorded under {@code nativeApi}, with the API name returned by
 *       {@link #api(String)}).</li>
 * </ol>
 * Implementations: {@code kit.operaton.OperatonKit}, {@code kit.flowable.FlowableKit}, loaded by class name.
 */
public interface Kit {

    /** "operaton" or "flowable". */
    String engine();

    EngineAdapter create(Db.Target target);

    String historyLevel();

    List<String> configurationNotes();

    /** The engine API that implements a {@link Kit} operation (for the JSON), e.g. api("migrate"). */
    String api(String operation);

    // ------------------------------------------------------------------ model variants

    /** Vendor namespace declaration for {@code <definitions>}. */
    String ns();

    /** External (pull-worker) service task. {@code extraAttrs} is put on the element as is (may be empty). */
    String externalTask(String id, String topic, String extraAttrs);

    /**
     * Attribute text needed on a PARALLEL multi-instance external task so that its items can be fetched together;
     * empty when the engine needs none. Flowable: external-worker jobs are exclusive by default, and acquiring several
     * exclusive jobs of one process instance returns none of them (seen in E17-a D-TENDER).
     */
    String parallelExternalTaskAttrs();

    /** DMN decision call that writes the single output column {@code resultVar} as a process variable. */
    String dmnTask(String id, String decisionKey, String resultVar, boolean sameDeployment);

    /**
     * Call activity with variable mapping. Each in is {sourceExpression, target} (an expression like "${x}" or
     * "${'AA'}"); each out is {source variable, target variable}.
     */
    String callActivity(String id, String calledElement, List<String[]> ins, List<String[]> outs);

    /** Attribute text for a user-task due date expression, e.g. {@code operaton:dueDate="${d}"}. */
    String dueDateAttr(String expression);

    /** Attribute text for a timer that uses a named business calendar; empty when the engine has none. */
    String timerCalendarAttr(String calendarName);

    // ------------------------------------------------------------------ native API

    long historicActivityCount(EngineAdapter a, String processInstanceId, String activityId);

    List<String> taskIds(EngineAdapter a, String processInstanceId, String processKey, String taskKey, int max);

    long countTasksWithProcessVariable(EngineAdapter a, String processKey, String variable, Object value);

    void setAssignee(EngineAdapter a, String taskId, String userId);

    String assignee(EngineAdapter a, String taskId);

    void setDueDate(EngineAdapter a, String taskId, Date due);

    Date dueDate(EngineAdapter a, String taskId);

    void delegate(EngineAdapter a, String taskId, String userId);

    void resolve(EngineAdapter a, String taskId, Map<String, Object> variables);

    String owner(EngineAdapter a, String taskId);

    /** What the engine's history keeps about who held a task (assignee changes, participants). */
    List<String> assignmentHistory(EngineAdapter a, String taskId);

    void broadcastSignal(EngineAdapter a, String signalName);

    /** Timer jobs of an instance (and its child executions): {jobId, due date ISO}. */
    List<String[]> timers(EngineAdapter a, String processInstanceId);

    /** Changes the due date of a waiting timer; returns the (possibly new) job id. */
    String rescheduleTimer(EngineAdapter a, String jobId, Date newDue);

    void suspend(EngineAdapter a, String processInstanceId);

    void activate(EngineAdapter a, String processInstanceId);

    boolean isSuspended(EngineAdapter a, String processInstanceId);

    String latestDefinitionId(EngineAdapter a, String processKey);

    String definitionIdOf(EngineAdapter a, String processInstanceId);

    /** Migrates running instances to {@code targetDefinitionId}; {@code mapping} maps changed activity ids. */
    void migrate(EngineAdapter a, List<String> processInstanceIds, String targetDefinitionId, Map<String, String> mapping);

    /** Instance modification: cancels {@code fromActivity} and starts {@code toActivity}. */
    void moveActivity(EngineAdapter a, String processInstanceId, String fromActivity, String toActivity);

    /** Bulk deletion of process instances in one engine call. */
    void bulkDelete(EngineAdapter a, List<String> processInstanceIds, String reason);

    /**
     * Makes "PnD" timers count working days of {@code cal}. Flowable: a named calendar, selected per timer with
     * {@link #timerCalendarAttr}. Operaton: no per-timer calendar exists, so the engine-wide "duration" calendar is
     * replaced (affects every duration timer of the engine).
     */
    void registerWorkingDayCalendar(EngineAdapter a, String name, BizCal cal);

    boolean hasMessageSubscription(EngineAdapter a, String messageName, String businessKey);

    long runningInstances(EngineAdapter a, String processKey);

    long finishedInstances(EngineAdapter a, String processKey);

    List<String> subProcessInstances(EngineAdapter a, String processInstanceId);

    /** Exception messages of failed jobs (timers, async, dead letter) of an instance, for diagnostics. */
    List<String> jobFailures(EngineAdapter a, String processInstanceId);

    /** External work items of an instance as "topic|lockOwner|lockExpiry|retries" (diagnostics). */
    List<String> externalWorkInfo(EngineAdapter a, String processInstanceId);

    /** Process instance ids started from a definition key (running and ended). */
    List<String> allInstanceIds(EngineAdapter a, String processKey);
}
