// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.integration;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One call of a connector.
 *
 * @param tenantId      tenant from the authenticated identity or channel binding (VI.4 rule 6)
 * @param operationId   UUIDv5 business-operation identity (R36); the downstream idempotency key
 * @param attemptId     changes per lease (A-2); for logs and provider correlation only
 * @param attemptNumber 1 for the first attempt of this operation
 * @param rootId        root process/case instance id
 * @param businessKey   business key, may be null
 * @param operation     connector operation name, e.g. {@code send}, {@code pay}, {@code sign}
 * @param input         typed JSON input (decimals as strings, R33)
 * @param deadline      the call must give up by this instant (lease end minus a safety margin); may be null
 */
public record ConnectorRequest(String tenantId, UUID operationId, String attemptId, int attemptNumber, String rootId,
                               String businessKey, String operation, Map<String, Object> input, Instant deadline) {

    public ConnectorRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(operationId, "operationId");
        operation = operation == null ? "invoke" : operation;
        input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** String input value, or null. */
    public String str(String key) {
        Object v = input.get(key);
        return v == null ? null : v.toString();
    }

    /** String input value, or {@code fallback}. */
    public String str(String key, String fallback) {
        String v = str(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Required string input value. */
    public String required(String key) {
        String v = str(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("connector input '" + key + "' is required");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object v = input.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Remaining time until the deadline in milliseconds, or {@code fallbackMillis} when there is no deadline. */
    public long remainingMillis(long fallbackMillis) {
        if (deadline == null) {
            return fallbackMillis;
        }
        return Math.max(0, deadline.toEpochMilli() - System.currentTimeMillis());
    }

    public ConnectorRequest withInput(Map<String, Object> newInput) {
        return new ConnectorRequest(tenantId, operationId, attemptId, attemptNumber, rootId, businessKey, operation, newInput,
                deadline);
    }
}
