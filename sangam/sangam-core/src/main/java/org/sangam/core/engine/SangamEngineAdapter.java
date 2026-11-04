// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.sangam.core.api.EngineAdapter;

/**
 * Stage C extension of {@link EngineAdapter} with the fields and calls the worker API needs (ARCHITECTURE §4,
 * VI.3): operationId, attempt number, business key and tenant on every leased item; heartbeat with
 * {@code cancelRequested}; cancel request; capped counts (A-16); branch-addressed correlation (D-35); pause/resume
 * (D-32). Engine-neutral: no Operaton type appears here.
 *
 * <p>Integration note: {@code EngineAdapter.ExternalWork} in sangam-core has no {@code operationId}; this interface
 * carries it in {@link LeasedWork} until the integrator adds it to the core record.
 */
public interface SangamEngineAdapter extends EngineAdapter {

    /**
     * One leased item with its business identity.
     *
     * @param operationId       T35 identity (UUIDv5 of tenant, business key, activity, scope); stable across
     *                          retries, lease expiry, redelivery, migration and restore
     * @param attemptNo         1 for the first lease of this work, +1 per later lease (VI.3.1)
     * @param capabilityClass   declared class ({@code a}, {@code b}, {@code c}); null = undeclared (A-8: class c)
     * @param resourceFence     VI.6 {@code resource:} mode: the exclusive lease's version for downstream fencing; else null
     */
    record LeasedWork(EngineAdapter.ExternalWork work, String tenantId, String businessKey, String rootId,
                      UUID operationId, int attemptNo, String capabilityClass, Long resourceFence,
                      boolean cancelRequested) {
    }

    /** Result of a heartbeat (VI.3 rule 3). */
    record Heartbeat(Instant lockExpiresAt, boolean cancelRequested) {
    }

    /** {@link #fetchAndLock} with the business identity of every item. */
    List<LeasedWork> fetchAndLockWork(String workerId, String topic, int maxItems, Duration lockDuration);

    /** {@link #extendLock} returning the new expiry and whether a cancel was requested. */
    Heartbeat heartbeat(String workId, String workerId, long lockVersion, Duration newDuration);

    /** VI.3.1 {@code LOCKED --cancel requested--> CANCEL_REQUESTED}; the next heartbeat reports it. */
    void requestCancel(String workId);

    /** A-16: counts at most {@code cap + 1} rows; a result above {@code cap} means "more than cap". */
    long countTasksCapped(TaskQuery query, int cap);

    /**
     * D-35: correlates a message to one waiting branch of an instance, addressed by a local variable of the waiting
     * execution (for example the multi-instance item id). Returns the execution id that received it.
     */
    String correlateMessageToBranch(String tenantId, String messageName, String processInstanceId,
                                    String localVariableName, Object localVariableValue, Map<String, Object> variables);

    /** D-32: suspends the instance and records the pause; deadlines are moved on {@link #resumeInstance}. */
    void pauseInstance(String processInstanceId, String actor, String reason);

    /** D-32: re-dates every timer of the instance by the paused time (tx 1), then activates it (tx 2). */
    void resumeInstance(String processInstanceId, String actor);

    /** The engine clock (D-10). */
    SangamClock clock();
}
