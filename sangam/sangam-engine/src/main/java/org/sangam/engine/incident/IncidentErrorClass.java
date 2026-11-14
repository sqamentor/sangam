// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.incident;

import org.operaton.bpm.engine.delegate.BpmnError;
import org.sangam.engine.db.Conflicts;

/**
 * Owner decision 302: the {@code errorClass} of {@code INCIDENT_OPENED}, derived, never guessed:
 * <ul>
 *   <li>{@value #CONFIG}: a strict-gateway routing incident ({@code NO_MATCHING_ROUTE}, {@code AMBIGUOUS_ROUTE});</li>
 *   <li>{@value #RETRYABLE}: the failure is a retryable database conflict ({@link Conflicts#isRetryable}, as the
 *       failed-job factory uses it);</li>
 *   <li>{@value #BUSINESS}: only where a BPMN error code proves it: a {@link BpmnError} with a non-blank error code in
 *       the failure's cause chain;</li>
 *   <li>{@value #UNKNOWN}: everything else, including incidents without a recorded failure (external-task failures
 *       reported by a worker, the {@code LEASE_EXPIRED_HELD} hold, retries set to zero by an operator).</li>
 * </ul>
 * Precedence is the order above.
 */
public final class IncidentErrorClass {

    public static final String RETRYABLE = "retryable";
    public static final String BUSINESS = "business";
    public static final String CONFIG = "config";
    public static final String UNKNOWN = "unknown";

    private IncidentErrorClass() {
    }

    /**
     * @param routing a routing incident whose stable code was checked
     * @param failure the original failure that opened the incident, or null when none is known
     */
    public static String of(boolean routing, Throwable failure) {
        if (routing) {
            return CONFIG;
        }
        if (failure == null) {
            return UNKNOWN;
        }
        if (Conflicts.isRetryable(failure, false)) {
            return RETRYABLE;
        }
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BpmnError e && e.getErrorCode() != null && !e.getErrorCode().isBlank()) {
                return BUSINESS;
            }
        }
        return UNKNOWN;
    }
}
