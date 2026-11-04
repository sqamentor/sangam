// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

import java.util.Map;

/**
 * A reconciliation item for an operation whose outcome is unknown, or whose downstream record does not match
 * (OJ-02, O04/O05). The three operator actions are: confirm success (with evidence), confirm failure, and retry with
 * the same operationId (only for capability classes a and b).
 */
public record ReconciliationRequest(String tenantId, String connector, String operationId, String businessKey,
                                    String processInstanceId, String kind, Map<String, Object> evidence) {

    public ReconciliationRequest {
        evidence = evidence == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(evidence));
    }
}
