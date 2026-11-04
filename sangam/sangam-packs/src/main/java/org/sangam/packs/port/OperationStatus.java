// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/** What the process knows about an external operation (VI.3). */
public enum OperationStatus {
    /** Confirmed success (acknowledged by the external system). */
    COMPLETED,
    /** Confirmed failure; a new attempt needs a new operationId (A-2). */
    FAILED,
    /** Accepted for processing; the outcome arrives later as {@link ConnectorGateway#MSG_OPERATION_RESULT}. */
    PENDING,
    /** Outcome not known: UNKNOWN_OUTCOME, no automatic repeat for class (c) (VI.3 rule 5). */
    UNKNOWN,
    /** Cancelled at the external system (VI.5 CANCEL_ACKED). */
    CANCELLED
}
