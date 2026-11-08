// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import java.time.Duration;
import java.util.Map;

/**
 * Outcome of one {@link SangamConnector#invoke} call (VI.3 rule 4, A-2).
 */
public sealed interface ConnectorResult
        permits ConnectorResult.Success, ConnectorResult.BusinessFailure, ConnectorResult.TransientFailure,
        ConnectorResult.OutcomeUnknown {

    /** The effect happened (or had already happened: {@code duplicate=true}). */
    record Success(Map<String, Object> output, String providerRef, boolean duplicate) implements ConnectorResult {
        public Success {
            output = output == null ? Map.of() : Map.copyOf(output);
        }

        public static Success of(Map<String, Object> output, String providerRef) {
            return new Success(output, providerRef, false);
        }
    }

    /**
     * A definite, deterministic refusal: the effect did not happen and repeating the same request gives the same answer
     * (validation error, template not registered, account invalid). Routed by the model as a BPMN error.
     */
    record BusinessFailure(String code, String message, Map<String, Object> output) implements ConnectorResult {
        public BusinessFailure {
            output = output == null ? Map.of() : Map.copyOf(output);
        }

        public static BusinessFailure of(String code, String message) {
            return new BusinessFailure(code, message, Map.of());
        }
    }

    /**
     * A failure that is safe to retry. {@code noEffect=true} is a proof that nothing happened downstream (connection
     * refused, 429, 503 before processing); only then may a class (c) operation be retried.
     */
    record TransientFailure(String message, boolean noEffect, Duration retryAfter) implements ConnectorResult {
        public static TransientFailure noEffect(String message) {
            return new TransientFailure(message, true, null);
        }
    }

    /** The effect may or may not have happened. Leads to lookup (a/b) or UNKNOWN_OUTCOME + reconciliation (c). */
    record OutcomeUnknown(String reason, String providerRef) implements ConnectorResult {
        public static OutcomeUnknown of(String reason) {
            return new OutcomeUnknown(reason, null);
        }
    }
}
