// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.Map;

/**
 * Outcome of a connector call as seen by the process.
 *
 * @param status      what is known about the effect
 * @param reasonCode  machine reason for FAILED (for example {@code INVALID_IFSC}, {@code ACCOUNT_CLOSED})
 * @param message     human text (English); UIs translate by reason code
 * @param externalRef reference issued by the external system (UTR, file name, txn id, URI)
 * @param data        result data; for queries the answer, for effects any returned fields
 */
public record ConnectorResult(OperationStatus status, String reasonCode, String message, String externalRef,
                              Map<String, Object> data) {

    public ConnectorResult {
        data = data == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(data));
    }

    public static ConnectorResult completed(String externalRef, Map<String, Object> data) {
        return new ConnectorResult(OperationStatus.COMPLETED, null, null, externalRef, data);
    }

    public static ConnectorResult failed(String reasonCode, String message) {
        return new ConnectorResult(OperationStatus.FAILED, reasonCode, message, null, Map.of());
    }

    public static ConnectorResult unknown(String message) {
        return new ConnectorResult(OperationStatus.UNKNOWN, "OUTCOME_UNKNOWN", message, null, Map.of());
    }

    public static ConnectorResult pending(String externalRef) {
        return new ConnectorResult(OperationStatus.PENDING, null, null, externalRef, Map.of());
    }
}
