package org.sangam.spike.govc.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.sangam.spike.api.EngineAdapter;

/**
 * Engine-native calls that {@link EngineAdapter} does not expose. One implementation per engine
 * ({@code hooks.operaton.OperatonNative}, {@code hooks.flowable.FlowableNative}), loaded by name so the other
 * engine's classes are never touched. Every implementation method records the engine API it used through
 * {@link NativeUse#record}, so each scenario's JSON lists the native APIs it needed.
 */
public interface Native {

    /** "flowable" or "operaton". */
    String engine();

    EngineAdapter create(Db.Target db);

    /** Template expansion of the vendor tokens (see {@link Models}). */
    Models.Dialect dialect();

    // ------------------------------------------------------------------ timers

    record Timer(String jobId, String activityId, Instant due, boolean suspended, String executionId) {
    }

    /** Timer jobs of an instance (including suspended ones). */
    List<Timer> timers(EngineAdapter a, String processInstanceId);

    /** Timer start-event jobs of a process definition key (no instance). */
    List<Timer> startTimers(EngineAdapter a, String processKey);

    /** Moves a running timer to an absolute due date. */
    void setTimerDue(EngineAdapter a, String jobId, Instant due);

    /**
     * Re-arms a timer from its model expression with the instance's current variables (no product-side date).
     * Returns null when one engine operation does it, otherwise a description of the composition used (recorded as
     * a workaround by the scenario).
     */
    String recalculateTimerFromModel(EngineAdapter a, String jobId);

    // ------------------------------------------------------------------ suspension

    void suspendInstance(EngineAdapter a, String processInstanceId);

    void activateInstance(EngineAdapter a, String processInstanceId);

    boolean isSuspended(EngineAdapter a, String processInstanceId);

    // ------------------------------------------------------------------ variables / executions

    void setVariable(EngineAdapter a, String executionOrInstanceId, String name, Object value);

    Object getVariable(EngineAdapter a, String executionOrInstanceId, String name);

    void setVariablesLocal(EngineAdapter a, String executionId, Map<String, Object> vars);

    /** Execution ids that wait on {@code messageName} in the instance (and its sub-scopes). */
    List<String> messageSubscriptions(EngineAdapter a, String processInstanceId, String messageName);

    /**
     * Sets {@code localVars} as local variables of {@code localScopeExecutionId} (for example the multi-instance branch)
     * and delivers the message to the waiting execution, in one engine transaction.
     */
    void deliverMessage(EngineAdapter a, String executionId, String messageName, String localScopeExecutionId,
                        Map<String, Object> localVars);

    /** The execution of the instance that holds {@code name = value} as a LOCAL variable (for example loopCounter). */
    String executionWithLocalVariable(EngineAdapter a, String processInstanceId, String name, Object value);

    String taskExecutionId(EngineAdapter a, String taskId);

    /**
     * Starts an additional activity in a running instance next to what already runs (instance modification).
     * Returns null when the running activity is untouched, otherwise what happened to it.
     */
    String startAdditionalActivity(EngineAdapter a, String processInstanceId, String activityId, String runningActivityId);

    /**
     * Makes the engine evaluate conditional events of a running instance. Returns false when the engine evaluates
     * them by itself on every variable change (nothing to do).
     */
    boolean evaluateConditionalEvents(EngineAdapter a, String processInstanceId);

    // ------------------------------------------------------------------ hierarchy and links

    String superInstance(EngineAdapter a, String childInstanceId);

    List<String> subInstances(EngineAdapter a, String processInstanceId);

    /** Starts a linked (sibling) instance; Flowable can store a reference natively, Operaton through variables. */
    String startLinked(EngineAdapter a, String processKey, String businessKey, Map<String, Object> vars,
                       String referenceId, String referenceType);

    List<String> linkedInstances(EngineAdapter a, String referenceId, String referenceType);

    // ------------------------------------------------------------------ history

    List<String> historicActivityIds(EngineAdapter a, String processInstanceId);

    /** Every value the variable had in the instance, in update order (history detail). */
    List<Object> variableHistory(EngineAdapter a, String processInstanceId, String name);

    List<String> historicInstancesByBusinessKey(EngineAdapter a, String processKey, String businessKey);

    List<String> runningInstances(EngineAdapter a, String processKey);

    void deleteHistoricInstance(EngineAdapter a, String processInstanceId);

    void resetClock(EngineAdapter a);

    /** Configuration notes about the adapter (for the JSON). */
    List<String> configurationNotes();
}
