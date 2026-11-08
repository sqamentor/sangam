// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.core;

/**
 * The shared government approval lifecycle (IX.3):
 * DRAFT, SUBMITTED, SCRUTINY and CLARIFICATION_SOUGHT, RECOMMENDATION (1..n levels), SANCTION, EXECUTION,
 * ACKNOWLEDGED, RECONCILED, CLOSED; side exits REJECTED, WITHDRAWN, CANCELLED, UNKNOWN_OUTCOME.
 * The extra states are the ones the packs need between those steps (VI.5 CANCEL_REQUESTED, appeal, court stay).
 */
public enum CaseState {
    DRAFT("CASE_DRAFT_SAVED"),
    SUBMITTED("CASE_SUBMITTED"),
    SCRUTINY("CASE_IN_SCRUTINY"),
    CLARIFICATION_SOUGHT("CLARIFICATION_SOUGHT"),
    RECOMMENDATION("CASE_IN_RECOMMENDATION"),
    SANCTION("CASE_IN_SANCTION"),
    APPROVED("CASE_APPROVED"),
    EXECUTION("CASE_IN_EXECUTION"),
    ACKNOWLEDGED("CASE_ACKNOWLEDGED"),
    RECONCILED("CASE_RECONCILED"),
    CLOSED("CASE_CLOSED"),
    REJECTED("CASE_REJECTED"),
    WITHDRAWN("CASE_WITHDRAWN"),
    CANCEL_REQUESTED("CANCEL_REQUESTED"),
    CANCELLED("INSTANCE_CANCELLED"),
    UNKNOWN_OUTCOME("EXT_OP_UNKNOWN"),
    RETURNED("CASE_RETURNED"),
    APPEAL("APPEAL_FILED"),
    ON_HOLD("LEGAL_HOLD_SET");

    private final String auditEvent;

    CaseState(String auditEvent) {
        this.auditEvent = auditEvent;
    }

    /** Audit event name of entering this state (X.4.0.3). */
    public String auditEvent() {
        return auditEvent;
    }

    /** True for states after which no business step follows. */
    public boolean terminal() {
        return this == CLOSED || this == REJECTED || this == WITHDRAWN || this == CANCELLED;
    }
}
