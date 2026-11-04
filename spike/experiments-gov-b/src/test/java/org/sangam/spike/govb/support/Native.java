package org.sangam.spike.govb.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Engine capabilities that {@code EngineAdapter} does not expose, called on the engine's own API. Every call records
 * its native API name on the current {@link Rec} (E16/E17 rule: record each native use per engine).
 */
public interface Native {

    long runningInstances(String processKey);

    long finishedInstances(String processKey);

    void suspend(String processInstanceId);

    void activate(String processInstanceId);

    boolean isSuspended(String processInstanceId);

    void setVariable(String processInstanceId, String name, Object value);

    /** Executions/plan item instances of a case that are ENABLED (manual activation pending) for one plan item id. */
    List<String> enabledCaseItems(String caseInstanceId, String planItemId);

    /** Manually starts an enabled case item (ad-hoc action). */
    void startCaseItem(String itemId);

    List<String> historicByBusinessKey(String processKey, String businessKey);

    long runtimeInstancesByVar(String processKey, String name, Object value);

    long runtimeInstancesByVarLike(String processKey, String name, String like);

    long historicInstancesByVar(String processKey, String name, Object value);

    long tasksByProcessVar(String name, Object value);

    /** Operaton: open incidents; Flowable: dead-letter jobs plus external-worker jobs with retries 0. */
    long failedWork(String processInstanceId);

    List<Map<String, Object>> evaluateDecision(String decisionKey, String tenantId, Map<String, Object> variables);

    /** Called (child) process instances of a process instance, running or ended. */
    List<String> subProcessInstances(String superProcessInstanceId);

    /** Process instances started by process tasks of a case, running or ended. */
    List<String> processInstancesOfCase(String caseInstanceId);

    long timerJobs(String processInstanceId);

    Instant earliestTimerDue(String processInstanceId);

    void rescheduleTimers(String processInstanceId, Instant due);

    String processDefinitionId(String processKey, String tenantId);

    long historicActivityCount(String processInstanceId, String activityId);

    Map<String, Long> finishedTasksByAssignee(String processKey);

    long finishedByEndActivity(String processKey, String endActivityId);

    double avgDurationMs(String processKey);

    String instanceTenant(String processInstanceId);

    /** Event subscriptions of a given name (signal/message) across the engine. */
    long eventSubscriptions(String eventName);

    /** Number of deployed case definitions with this key. */
    long caseDefinitions(String caseKey);

    /** BPMN adHocSubProcess: the activities that may be started now in the instance's ad-hoc sub-process. */
    List<String> adhocEnabled(String processInstanceId);

    void adhocStart(String processInstanceId, String activityId);

    /**
     * Message correlation by business key that also matches a waiting CHILD execution (parallel branch, event
     * gateway). Only used where the adapter's correlateMessage finds no execution.
     */
    String correlateAnyExecution(String tenantId, String messageName, String businessKey, Map<String, Object> vars);

    /**
     * Makes a called process instance run in its DEFINITION's tenant (for cross-tenant call activities). Returns the
     * number of workaround lines it needs (0 when the engine does this natively).
     */
    int calledProcessesRunInCalleeTenant();
}
