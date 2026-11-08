package org.sangam.core.layer;

import java.util.Locale;

/**
 * Integration capability class of a connector or topic (SANGAM.md A-2, A-8).
 * The default for an undeclared topic is {@link #C_NEITHER}; {@code effect=irreversible} is an alias of (c).
 */
public enum CapabilityClass {
    /** (a) idempotent + outcome lookup: retry or query with the same operationId. */
    A_IDEMPOTENT_WITH_LOOKUP,
    /** (b) outcome lookup only: query before any controlled retry. */
    B_LOOKUP_ONLY,
    /** (c) neither: UNKNOWN_OUTCOME and reconciliation, no automatic repeat. */
    C_NEITHER;

    /** What happens when a lease on an operation of this class expires. */
    public enum LeaseExpiryAction {
        /** Task back to AVAILABLE; the next attempt reuses the same operationId. */
        RETRY_SAME_OPERATION,
        /** Task back to AVAILABLE, but the next attempt must look the outcome up before re-issuing. */
        LOOKUP_THEN_RETRY,
        /** No automatic repeat: UNKNOWN_OUTCOME and a reconciliation item. */
        UNKNOWN_OUTCOME
    }

    public LeaseExpiryAction onLeaseExpired() {
        return switch (this) {
            case A_IDEMPOTENT_WITH_LOOKUP -> LeaseExpiryAction.RETRY_SAME_OPERATION;
            case B_LOOKUP_ONLY -> LeaseExpiryAction.LOOKUP_THEN_RETRY;
            case C_NEITHER -> LeaseExpiryAction.UNKNOWN_OUTCOME;
        };
    }

    public boolean allowsAutomaticRetry() {
        return this != C_NEITHER;
    }

    /**
     * Parses a topic declaration: {@code a}/{@code b}/{@code c} (or the enum names), {@code irreversible} (alias of c).
     * Null or blank yields the A-8 default, (c).
     */
    public static CapabilityClass fromDeclaration(String declaration) {
        if (declaration == null || declaration.isBlank()) {
            return C_NEITHER;
        }
        String d = declaration.trim().toLowerCase(Locale.ROOT);
        return switch (d) {
            case "a", "a_idempotent_with_lookup", "idempotent" -> A_IDEMPOTENT_WITH_LOOKUP;
            case "b", "b_lookup_only", "lookup" -> B_LOOKUP_ONLY;
            case "c", "c_neither", "neither", "irreversible", "effect=irreversible" -> C_NEITHER;
            default -> throw new IllegalArgumentException("unknown capability class: " + declaration);
        };
    }
}
