// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-request identity and command metadata handed to every port call.
 *
 * @param tenantId        from the authenticated identity (VI.4 rule 6)
 * @param principalId     person or service principal id
 * @param groups          group/role ids of the principal
 * @param seatId          seat context selected by the caller ({@code X-Sangam-Seat}, IX.5 rule 1)
 * @param actingForSeatId acting authority ({@code X-Sangam-Acting-For-Seat}, IX.5 rule 4); part of the R41 fingerprint
 * @param idempotencyKey  {@code Idempotency-Key} header (R41), recorded in class-B audit
 * @param traceId         W3C trace id of the current span
 * @param clientIp        caller address (class-B audit only)
 * @param authMethod      OIDC, SAML, API_KEY, DEV_TOKEN, SYSTEM ...
 */
public record RequestContext(String tenantId, String principalId, Set<String> groups, String seatId,
                             String actingForSeatId, String idempotencyKey, String traceId, String clientIp,
                             String authMethod) {

    public RequestContext {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    public boolean inGroup(String group) {
        return groups.contains(group);
    }

    /** Principal ids used for candidate/authorization matching: the user plus their groups. */
    public List<String> principalIds() {
        List<String> ids = new ArrayList<>(groups.size() + 1);
        ids.add(principalId);
        ids.addAll(groups);
        return ids;
    }

    /** Actor fields for class-B audit payloads (X.2.3.2 {@code actor}). */
    public java.util.Map<String, Object> actor() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("principal_id", principalId);
        m.put("seat_id", seatId);
        m.put("acted_for_seat_id", actingForSeatId);
        m.put("auth_method", authMethod);
        m.put("client_ip", clientIp);
        m.put("idempotency_key", idempotencyKey);
        m.put("trace_id", traceId);
        m.values().removeIf(java.util.Objects::isNull);
        return m;
    }

    /** System principal for background work; records {@code onBehalfOf} (VI.4 rule 7). */
    public static RequestContext system(String tenantId, String onBehalfOf) {
        return new RequestContext(tenantId, "system", Set.of("sangam-system"), null, null, null, null, null,
                onBehalfOf == null ? "SYSTEM" : "SYSTEM:onBehalfOf=" + onBehalfOf);
    }
}
