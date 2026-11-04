// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import org.sangam.core.layer.CapabilityClass;

/**
 * The only way a pack delegate reaches an external system (ARCHITECTURE.md section 1.3, VI.1 rule 5, K13).
 *
 * <p>WS-INTEGRATION provides the production implementation: {@link #submit} writes an outbox row in the caller's
 * transaction (no remote call while the L2 root lock is held), the relay performs the call with the operationId as the
 * idempotency key, and the outcome comes back through the inbox as the BPMN message
 * {@value #MSG_OPERATION_RESULT}, correlated on the process variable {@code operationId}. Read-only
 * {@link #query} calls may be answered synchronously; pack models run them after an asynchronous continuation so that
 * no engine transaction waits on a remote system while holding row locks.
 *
 * <p>The default in this module is {@link org.sangam.packs.sim.SimulatorConnectorGateway}: every connector of every
 * pack answers deterministically, with failure injection through variables (ADR-D-13: simulator first).
 */
public interface ConnectorGateway {

    /** Message name used by the inbox to deliver an asynchronous operation outcome. */
    String MSG_OPERATION_RESULT = "sangam.operationResult";
    /** Message name used by the inbox to deliver a cancel acknowledgement (VI.5). */
    String MSG_CANCEL_ACK = "sangam.cancelAck";
    /** Message name used by the reconciliation console to resolve an UNKNOWN_OUTCOME item (OJ-02). */
    String MSG_RECONCILIATION_RESOLVED = "sangam.reconciliationResolved";
    /** Message name used to deliver an asynchronous signature result (G-SIGN). */
    String MSG_SIGNATURE_RESULT = "sangam.signatureResult";

    /** Read-only lookup (budget availability, registry, status, duplicate check). Must have no business effect. */
    ConnectorResult query(ConnectorRequest request);

    /**
     * Requests an external effect exactly once per {@link ConnectorRequest#operationId()} (VI.3). A repeated call with
     * the same operationId and payload hash returns the first result; a different payload hash under the same
     * operationId is rejected ({@code IDEMPOTENCY_PAYLOAD_MISMATCH}).
     */
    ConnectorResult submit(ConnectorRequest request);

    /** Looks up the outcome of an earlier {@link #submit} (capability class a or b). Class (c) returns UNKNOWN. */
    ConnectorResult lookup(String tenantId, String connector, String operationId);

    /** Asks the external system to cancel an in-flight operation (VI.5 CANCEL_REQUESTED). */
    ConnectorResult cancel(String tenantId, String connector, String operationId, String reason);

    /** Opens a reconciliation item for an UNKNOWN_OUTCOME (VI.3 rule 5); returns the item id. */
    String openReconciliation(ReconciliationRequest request);

    /** Capability class of a connector (A-2, A-8); undeclared connectors are class (c). */
    CapabilityClass capabilityOf(String connector);
}
