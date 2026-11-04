// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.Map;
import java.util.Objects;

/**
 * One call to an external system.
 *
 * @param tenantId          tenant (department) of the case; never taken from the payload (K1)
 * @param connector         connector id, e.g. {@code pfms.payment}, {@code ifms.budget}, {@code esign.sign}
 * @param operation         operation of that connector, e.g. {@code submitPaymentFile}
 * @param operationId       UUIDv5(business key, step, attempt scope) for effects (T35); may be null for queries
 * @param businessKey       business key of the case
 * @param processInstanceId engine process instance id (for the inbox correlation)
 * @param payload           canonical payload (decimals as strings, R33)
 * @param payloadHash       SHA-256 of the canonical payload; bound to the approval for effects (VI.4 rule 5)
 */
public record ConnectorRequest(String tenantId, String connector, String operation, String operationId,
                               String businessKey, String processInstanceId, Map<String, Object> payload,
                               String payloadHash) {

    public ConnectorRequest {
        Objects.requireNonNull(connector, "connector");
        payload = payload == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
}
