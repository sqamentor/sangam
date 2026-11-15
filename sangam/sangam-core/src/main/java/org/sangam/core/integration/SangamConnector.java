// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import org.sangam.core.layer.CapabilityClass;

/**
 * Connector SPI (SANGAM.md A-2, A-8, R36, R37, R71).
 *
 * <p>A connector performs one external effect per {@link #invoke} call. The caller (the integration layer's
 * {@code ConnectorInvoker}) has already recorded the call intent in {@code OPERATION_LEDGER} before {@link #invoke}
 * runs, and records the outcome afterwards. A connector therefore never touches the database and never runs inside
 * a database transaction (VI.1 rule 5).
 *
 * <p>Rules every implementation follows:
 * <ul>
 *   <li>{@link ConnectorRequest#operationId()} is forwarded to the downstream system as its idempotency key or
 *       transaction id wherever the protocol allows it (VI.3 rule 4). It is stable across retries; the
 *       {@link ConnectorRequest#attemptId()} changes per lease.</li>
 *   <li>The declared {@link #capabilityClass()} must be true of the downstream system: (a) idempotent by
 *       operationId <i>and</i> {@link #lookup} works, (b) {@link #lookup} works but a repeat would duplicate the effect,
 *       (c) neither. Undeclared means (c).</li>
 *   <li>An answer that does not prove whether the effect happened (timeout after send, dropped connection, 5xx
 *       after the request body was sent to a non-idempotent endpoint) is {@link ConnectorResult.OutcomeUnknown},
 *       never a retryable failure. {@link ConnectorResult.TransientFailure} with {@code noEffect=true} is only for
 *       failures that prove no effect happened (connection refused, DNS failure, 503 before processing, 429).</li>
 *   <li>Outbound destinations go through the egress policy (R27): allow-list, private/metadata ranges blocked,
 *       bounded redirects and response sizes.</li>
 * </ul>
 */
public interface SangamConnector {

    /** Static description: id, type, capability class, operations. */
    ConnectorDescriptor descriptor();

    /** Shortcut for {@code descriptor().capabilityClass()}. */
    default CapabilityClass capabilityClass() {
        return descriptor().capabilityClass();
    }

    /** Performs the effect once. Must not throw for downstream outcomes; it maps them to a {@link ConnectorResult}. */
    ConnectorResult invoke(ConnectorRequest request);

    /**
     * Outcome lookup by operationId (classes a and b). The default answers {@link LookupResult.NotSupported}, which is
     * correct only for class (c).
     */
    default LookupResult lookup(ConnectorRequest request) {
        return new LookupResult.NotSupported();
    }

    /**
     * Problem code of an API read that was refused because the connector did not answer {@link #readOnly} for the
     * request (owner decision 320): a permanent refusal (HTTP 422), never a retryable one.
     */
    String OPERATION_NOT_READ_ONLY = "CONNECTOR_OPERATION_NOT_READ_ONLY";

    /**
     * Whether {@link #invoke} of exactly this request is read-only (owner decisions 320 and 321): it changes no state,
     * here or downstream, and sends nothing outside (no message, order, file or inquiry transaction; fetching with an
     * HTTP {@code GET} or {@code HEAD}, or running a declared select, is a read). The answer is per request and comes
     * from what the request will really do (the resolved HTTP method, the resolved statement, the operation the
     * connector will run), never from a list of names and never from the caller's intent. It must not cause an effect
     * and must not throw.
     *
     * <p>A path that calls a connector from an API read (outside {@code ConnectorInvoker}, so without the operation
     * ledger, idempotency and maker-checker) invokes only a request for which this is {@code true}, and otherwise
     * refuses with {@link #OPERATION_NOT_READ_ONLY}. The default is {@code false} (fail closed): a connector that does
     * not override it can never be called from an API read. A connector that wraps another answers for the request it
     * will pass on.
     */
    default boolean readOnly(ConnectorRequest request) {
        return false;
    }

    /** Health probe used by the operator view; must not cause an effect. */
    default boolean healthy() {
        return true;
    }
}
