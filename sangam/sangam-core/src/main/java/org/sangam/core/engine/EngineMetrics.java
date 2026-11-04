// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.engine;

/**
 * Engine metrics port (D-39, R13). sangam-server binds it to Micrometer. Label cardinality is bounded: process
 * definition keys and topics only, never instance ids (VI.1 rule 8).
 */
public interface EngineMetrics {

    /** {@code root_lock_wait_seconds} per process definition key (VI.1 rule 8). */
    default void rootLockWait(String processDefinitionKey, long nanos) {
    }

    /** A command attempt rolled back for a retryable conflict. */
    default void commandRetry(String command) {
    }

    /** A command gave up after its retries ({@code 409 CONFLICT_RETRY_EXHAUSTED}). */
    default void commandRetryExhausted(String command) {
    }

    default void fetchRetry(String topic) {
    }

    default void leaseExpired(String topic, String decision) {
    }

    /** A timer firing suppressed by the DST guard or the catch-up policy (A-12). */
    default void timerSuppressed(String processDefinitionKey, String reason) {
    }

    /** {@code sangam_jobs_acquired_total}: jobs acquired by the job executor (or by executeDueJobs). */
    default void jobsAcquired(int count) {
    }

    /**
     * {@code sangam_jobs_failed_total}: a job execution failed (the engine's failed-job handling ran).
     *
     * @param cause simple class name of the failure (bounded cardinality)
     */
    default void jobFailed(String cause) {
    }

    /** {@code executeDueJobs} stopped at a bound (runaway guard). */
    default void jobExecutionBounded(String reason) {
    }

    EngineMetrics NONE = new EngineMetrics() {
    };
}
