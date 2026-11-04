package org.sangam.spike.experiments.support;

import java.util.List;
import java.util.Map;

import org.sangam.spike.api.EngineAdapter;

/**
 * Engine-specific calls the experiments need beyond {@link EngineAdapter} (history reads, the GAP-set triggers that
 * have no engine-neutral API). Every method used by a scenario is recorded in that scenario's JSON as
 * engine-specific handling, so nothing here is hidden.
 *
 * <p>Implementations: {@code hooks.flowable.FlowableHooks}, {@code hooks.operaton.OperatonHooks}; loaded by name so
 * that the other engine's classes are never touched (they are not on the class path of that execution).
 */
public interface EngineHooks {

    /** "flowable" or "operaton". */
    String engine();

    EngineAdapter create(Db.Target db);

    /** Activity ids from history (finished and running), in start order. */
    List<String> historicActivityIds(EngineAdapter a, String processInstanceId);

    /** Running process instance ids of a definition key. */
    List<String> runningInstances(EngineAdapter a, String processKey);

    /** All (running and ended) process instance ids of a definition key, from history. */
    List<String> allInstances(EngineAdapter a, String processKey);

    /** Broadcasts a signal by name (diagnostic for the signal end event). */
    void broadcastSignal(EngineAdapter a, String signalName);

    /**
     * Evaluates process-level conditional start events with the given variables and returns the ids of the
     * instances started. Throws {@link UnsupportedOperationException} (with what exists instead) if the engine
     * has no such API.
     */
    List<String> evaluateConditionalStart(EngineAdapter a, Map<String, Object> variables);

    /** Enabled activities of the ad-hoc sub-process in this instance; UnsupportedOperationException if none exists. */
    List<String> adhocEnabledActivities(EngineAdapter a, String processInstanceId);

    /** Starts an inner activity of the ad-hoc sub-process on demand. */
    void adhocExecute(EngineAdapter a, String processInstanceId, String activityId);

    /**
     * Deploys with the engine's BPMN validation switched off (diagnostic for models the validator rejects).
     * UnsupportedOperationException if the engine has no such switch.
     */
    String deployWithoutValidation(EngineAdapter a, String name, Map<String, byte[]> resources);

    /**
     * Historic plan items of a case instance (human tasks and milestones), ordered by end/occurrence time (open ones
     * last), as "elementId:type:state" (state "completed" for a completed task or an occurred milestone).
     */
    List<String> caseHistory(EngineAdapter a, String caseInstanceId);

    /**
     * Like {@link EngineAdapter#executeDueJobs()} for timers, but stops after {@code maxExecutions} job executions
     * (E8 runaway guard: a timer whose next due date does not advance would otherwise refire up to the adapter's own
     * bound, 100 rounds on Flowable and 10,000 executions on Operaton, per call). Returns the executions done.
     */
    int executeDueJobsBounded(EngineAdapter a, int maxExecutions);

    /** Due dates (UTC ISO) and repeat expressions of the timer jobs of an instance (E8 diagnostics). */
    List<String> timerJobs(EngineAdapter a, String processInstanceId);

    /** Puts the engine clock back to system time. */
    void resetClock(EngineAdapter a);

    /** Free-form description of the configuration the adapter applies (for the JSON). */
    List<String> configurationWorkarounds();
}
