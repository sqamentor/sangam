// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.power;

import java.util.List;
import java.util.Map;

/**
 * Result of one power-limit evaluation (owner decision 142).
 *
 * @param permitted       true only when every applicable delegated limit and every mandatory cap passed
 * @param effective       the binding decision: {@link PowerDecision.Deny} when not permitted, otherwise the most
 *                        restrictive applicable decision ({@link PowerDecision.Limited} with the lowest ceiling, or
 *                        {@link PowerDecision.Unlimited} only when every delegated limit said so explicitly)
 * @param reason          why
 * @param approval        the evidence of a permit (null when denied)
 * @param changedFields   at a recheck, the fields that changed since the earlier approval (empty otherwise)
 * @param auditPayload    the audit payload: actor, action, scope, case type, amount, currency, contract, metadata and
 *                        rule versions, applicable limits and caps, decision and reason
 */
public record PowerOutcome(boolean permitted, PowerDecision effective, String reason, PowerApproval approval,
                           List<String> changedFields, Map<String, Object> auditPayload) {

    /** Audit event type of every evaluation (catalogue entry requested from the integrator). */
    public static final String AUDIT_EVENT = "POWER_LIMIT_DECISION";

    public PowerOutcome {
        changedFields = List.copyOf(changedFields);
        auditPayload = auditPayload == null ? Map.of() : java.util.Collections.unmodifiableMap(auditPayload);
    }

    /** Throws {@link PowerLimitExceededException} unless permitted. */
    public PowerOutcome requirePermitted() {
        if (!permitted) {
            throw new PowerLimitExceededException(reason, this);
        }
        return this;
    }

    /** The refusal of a denied outcome; the caller records the audit payload before rolling back. */
    public static final class PowerLimitExceededException extends RuntimeException {
        private final transient PowerOutcome outcome;

        public PowerLimitExceededException(String reason, PowerOutcome outcome) {
            super("POWER_LIMIT_EXCEEDED: " + reason);
            this.outcome = outcome;
        }

        public PowerOutcome outcome() {
            return outcome;
        }
    }
}
