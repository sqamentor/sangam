// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy.external;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the external deny-only policy service is asked about one command at command entry (owner decision 166, M-083).
 * Built only from the authenticated identity and the command catalogue entry, never from the request body: the body is
 * not sent (data minimisation), and the actor is the server-side identity, never a payload value.
 *
 * @param tenantId     tenant of the authenticated identity
 * @param actorId      person or service principal id
 * @param authMethod   authentication method (OIDC, SAML, API_KEY, ...), may be null
 * @param groups       group or role ids of the principal
 * @param commandType  the fixed R41 command type, e.g. {@code task.complete} (the action)
 * @param method       HTTP method of the command
 * @param pathTemplate contract path template of the command, e.g. {@code /tasks/{taskId}/complete}
 * @param pathParams   the path variables, the resource reference (e.g. {@code taskId})
 */
public record ExternalPolicyRequest(String tenantId, String actorId, String authMethod, Set<String> groups,
                                    String commandType, String method, String pathTemplate,
                                    Map<String, String> pathParams) {

    /** Version of the request shape sent to the policy service ({@code input.contract}). */
    public static final String CONTRACT = "sangam.external-policy-request/1";

    public ExternalPolicyRequest {
        requireText(tenantId, "tenantId");
        requireText(actorId, "actorId");
        requireText(commandType, "commandType");
        groups = groups == null ? Set.of() : Set.copyOf(new TreeSet<>(groups));
        pathParams = pathParams == null ? Map.of() : Map.copyOf(new TreeMap<>(pathParams));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for an external policy check");
        }
    }
}
