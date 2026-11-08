// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import java.util.Map;
import java.util.Objects;

/**
 * One state-changing API request as the idempotency layer sees it (X.2.2.1–X.2.2.2).
 *
 * @param tenantId            from the authenticated identity (VI.4 rule 6), never from the body
 * @param principalId         authenticated principal; a service account on several pods is one principal
 * @param commandType         fixed command name, e.g. {@code process.start}, {@code task.complete}
 * @param method              HTTP method
 * @param pathTemplate        e.g. {@code /api/v1/tasks/{id}/complete}
 * @param pathParams          e.g. {@code {id: "t-42"}}: the target belongs to the fingerprint, not the scope
 * @param semanticQueryParams query parameters that change the command's meaning
 * @param body                parsed JSON body (maps/lists/strings; decimals as strings, R33)
 * @param idempotencyKey      {@code Idempotency-Key} header, may be null
 * @param ifMatch             {@code If-Match} header (part of the fingerprint)
 * @param actingForSeat       {@code X-Sangam-Acting-For-Seat} header (part of the fingerprint, IX.5)
 * @param preferWaitSeconds   {@code Prefer: wait=N}, 0..10
 */
public record IdempotentRequest(String tenantId, String principalId, String commandType, String method, String pathTemplate,
                                Map<String, ?> pathParams, Map<String, ?> semanticQueryParams, Object body,
                                String idempotencyKey, String ifMatch, String actingForSeat, int preferWaitSeconds) {

    public IdempotentRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(commandType, "commandType");
        preferWaitSeconds = Math.max(0, Math.min(10, preferWaitSeconds));
    }

    /** Parses {@code Prefer: wait=N} (RFC 7240); 0 when absent. */
    public static int preferWait(String preferHeader) {
        if (preferHeader == null) {
            return 0;
        }
        for (String part : preferHeader.split(",")) {
            String p = part.trim();
            if (p.startsWith("wait=")) {
                try {
                    return Math.max(0, Math.min(10, Integer.parseInt(p.substring(5).trim())));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }
}
