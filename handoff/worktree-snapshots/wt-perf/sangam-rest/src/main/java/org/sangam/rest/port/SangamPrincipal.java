// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.Map;
import java.util.Set;

/**
 * The authenticated caller, produced by the server's authentication layer (OIDC JWT or the dev static token) and put on
 * the servlet request as attribute {@link #ATTRIBUTE}. The tenant always comes from here (token claim {@code tenant}),
 * never from a request body or parameter (VI.4 rule 6, K1).
 */
public record SangamPrincipal(String tenantId, String principalId, Set<String> groups, String authMethod,
                              Map<String, Object> claims) {

    public static final String ATTRIBUTE = SangamPrincipal.class.getName();

    public SangamPrincipal {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }
}
