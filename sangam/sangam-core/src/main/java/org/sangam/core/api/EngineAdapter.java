package org.sangam.core.api;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Engine-neutral contract that the spike's product layer uses (SANGAM.md XI.2).
 * One implementation per candidate engine: Flowable 8.0.0 and Operaton 2.2.0.
 * Every method is one engine command; no remote work may run inside it (VI.1 rule 5).
 */
public interface EngineAdapter extends AutoCloseable {

    /** Short identifier such as "flowable-8.0.0" or "operaton-2.2.0". */
    String engineId();

    /** Deploys BPMN/DMN/CMMN resources; keys are resource names (e.g. "wf1-bill.bpmn"). */
    String deploy(String tenantId, String deploymentName, Map<String, byte[]> resources);

    String startProcess(String tenantId, String processKey, String businessKey, Map<String, Object> variables);

    /**
     * Starts a CMMN case by case definition key and returns the case instance id.
     *
     * <p>Case human tasks are reached through the ordinary task calls; there is no separate case-task query:
     * {@link #queryTasks}/{@link #countTasks} accept a case instance id in {@link TaskQuery#processInstanceId()},
     * {@link TaskView#processInstanceId()} holds the case instance id for a case task, and {@link #claimTask} /
     * {@link #completeTask} work on case tasks. {@link #getInstance} and {@link #cancelInstance} also accept case
     * instance ids.
     */
    String startCase(String tenantId, String caseKey, String businessKey, Map<String, Object> variables);

    /** Correlates a message to exactly one waiting instance; returns that instance id. */
    String correlateMessage(String tenantId, String messageName, String businessKey, Map<String, Object> variables);

    /** Lists user tasks. Covers BPMN user tasks and CMMN human tasks (see {@link #startCase}). */
    List<TaskView> queryTasks(TaskQuery query);

    long countTasks(TaskQuery query);

    void claimTask(String taskId, String userId);

    void completeTask(String taskId, Map<String, Object> variables);

    // ---------------------------------------------------------------- worker lease (VI.3 + A-1/A-2)
    //
    // Fencing is done by the adapter, not the engine: neither engine has a lock version. Each lease has a row in the
    // adapter-owned table SPIKE_LEASE(work_id PK, lock_version, attempt_id, worker_id, expires_at), written in the
    // same engine command (and therefore the same transaction, Q1) as the engine call. lock_version increases on
    // every fetch (also a re-acquire), on expiry (swept by executeDueJobs/fetchAndLock), on fail and on unlock.
    // Every call below checks (owner, lockVersion, expires_at > engine clock) before it touches the engine and
    // throws StaleLeaseException otherwise; on that path nothing changes. Lease times use the engine clock.

    /**
     * Fetches and locks external work for a worker (Operaton external task / Flowable external worker job).
     * Each returned item carries a new {@link ExternalWork#lockVersion()} (strictly greater than any earlier
     * version of the same work) and a new {@link ExternalWork#attemptId()}.
     */
    List<ExternalWork> fetchAndLock(String workerId, String topic, int maxItems, Duration lockDuration);

    /** Heartbeat: sets the lease expiry to engine-now + {@code newDuration}. Does not change the lockVersion. */
    void extendLock(String workId, String workerId, long lockVersion, Duration newDuration);

    void completeExternal(String workId, String workerId, long lockVersion, Map<String, Object> variables);

    /** Reports a failure; the lease ends and the lockVersion is incremented (VI.3.1: AVAILABLE after back-off). */
    void failExternal(String workId, String workerId, long lockVersion, String errorMessage, int retriesLeft,
                      Duration retryBackoff);

    void bpmnErrorExternal(String workId, String workerId, long lockVersion, String errorCode,
                           Map<String, Object> variables);

    /**
     * Explicit release ({@code release(lock_version)} in VI.3.1): owner-checked and fenced like the other calls; the
     * work becomes AVAILABLE at once and the lockVersion is incremented.
     */
    void unlockExternal(String workId, String workerId, long lockVersion);

    /**
     * Sets the lease-expiry hook (A-10), called in the same transaction before expired work can be fetched again.
     * The default is {@link ExpiryPolicy#ALLOW_REOFFER}. Adapters that do not support the hook throw
     * {@link UnsupportedOperationException}.
     */
    default void setExpiryPolicy(ExpiryPolicy policy) {
        throw new UnsupportedOperationException(engineId() + " does not support an ExpiryPolicy");
    }

    /**
     * Deletes a process instance (or terminates a case instance). The lease rows of its external work are removed in
     * the same transaction, so a late worker call gets {@link StaleLeaseException} with
     * {@link StaleLeaseException.Reason#NO_LEASE}.
     */
    void cancelInstance(String instanceId, String reason);

    /**
     * Returns the state of a process or case instance: from the runtime tables while it runs, from history after it
     * ended. Returns {@code null} when the id is unknown (never existed, or history no longer has it); it does not
     * throw for an unknown id.
     */
    InstanceView getInstance(String instanceId);

    /** Fires due timers and runs due async jobs synchronously (test helper; the clock is the engine clock). */
    int executeDueJobs();

    /** Moves the engine clock (for timer and DST experiments). */
    void setClock(java.time.Instant now);

    /**
     * Runs {@code work} inside the engine's own database transaction, exposing its JDBC connection,
     * so product tables commit or roll back with engine state (feasibility question Q1).
     * Implementations must not commit the connection themselves.
     */
    <T> T inEngineTransaction(Function<Connection, T> work);

    /** JDBC URL the engine was configured with (for experiment reporting). */
    String jdbcUrl();

    @Override
    void close();

    /** {@code processInstanceId} may also be a case instance id (case tasks, see {@link #startCase}). */
    record TaskQuery(String tenantId, String candidateUser, List<String> candidateGroups,
                     String processInstanceId, String taskDefinitionKey, int firstResult, int maxResults) {
    }

    /** {@code processInstanceId} is the case instance id for a CMMN human task. */
    record TaskView(String id, String name, String taskDefinitionKey, String processInstanceId,
                    String assignee, String tenantId, Map<String, Object> variables) {
    }

    /**
     * One leased unit of external work.
     *
     * @param activityInstanceId engine identity of the activity execution the work belongs to, the input to
     *                           {@code operationId = UUIDv5(tenant, rootId, activityInstanceId, ...)} (VI.3.2 rule 4).
     *                           Operaton: {@code LockedExternalTask.getActivityInstanceId()}. Flowable: the id of the
     *                           unfinished runtime {@code ActivityInstance} (ACT_RU_ACTINST) of the job's execution and
     *                           element, or the execution id if there is none. Stable across fail/retry and lease
     *                           expiry; it is not stable across migration or a model-level retry loop on either engine.
     * @param lockVersion        fencing token; every worker call must pass it back
     * @param attemptId          new for every lease (A-2); not used for fencing, which is by lockVersion
     * @param operationId        the operation identity of this work (T35), or null where the adapter derives none
     */
    record ExternalWork(String id, String topic, String processInstanceId, String activityId,
                        String activityInstanceId, int retries, java.time.Instant lockExpiresAt,
                        long lockVersion, String attemptId, Map<String, Object> variables, String operationId) {

        public ExternalWork(String id, String topic, String processInstanceId, String activityId,
                            String activityInstanceId, int retries, java.time.Instant lockExpiresAt,
                            long lockVersion, String attemptId, Map<String, Object> variables) {
            this(id, topic, processInstanceId, activityId, activityInstanceId, retries, lockExpiresAt, lockVersion,
                    attemptId, variables, null);
        }
    }

    record InstanceView(String id, String processKey, String businessKey, boolean ended,
                        List<String> activeActivityIds, Map<String, Object> variables) {
    }
}
