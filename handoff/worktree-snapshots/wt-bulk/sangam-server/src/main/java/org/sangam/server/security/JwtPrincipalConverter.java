// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.sangam.rest.port.SangamPrincipal;
import org.sangam.server.config.SangamSecurityProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps an OIDC JWT to a {@link SangamPrincipal}. The tenant comes only from the token claim (VI.4 rule 6, K1); a token
 * without it is rejected with 401 and there is <b>no</b> default-tenant fallback. Roles/groups: the configured roles
 * claim (default {@code roles}), else {@code groups}, else Keycloak {@code realm_access.roles}. The seats the token
 * names (claim default {@code seats}) are put in {@code claims["seats"]} as a list of strings; effective dating and
 * additional charge are resolved server-side (sangam-security), the token list only limits {@code X-Sangam-Seat}.
 */
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String SEATS = "seats";

    private final SangamSecurityProperties.Claims claims;

    public JwtPrincipalConverter(SangamSecurityProperties.Claims claims) {
        this.claims = claims;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenant = jwt.getClaimAsString(claims.getTenant());
        if (tenant == null || tenant.isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token",
                    "The token has no '" + claims.getTenant() + "' claim; Sangam requires a tenant-bound identity", null));
        }
        String principal = jwt.getClaimAsString(claims.getPrincipal());
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        Map<String, Object> all = new LinkedHashMap<>();
        jwt.getClaims().forEach((k, v) -> {
            if (k != null && v != null) {
                all.put(k, v);
            }
        });
        Object seatClaim = jwt.getClaims().get(claims.getSeats());
        if (seatClaim != null) {
            all.put(SEATS, strings(seatClaim));
        } else {
            all.remove(SEATS);
        }
        SangamPrincipal p = new SangamPrincipal(tenant, principal, roles(jwt), "OIDC", all);
        return new SangamAuthenticationToken(p, jwt);
    }

    private Set<String> roles(Jwt jwt) {
        Set<String> out = new LinkedHashSet<>();
        addAll(out, jwt.getClaims().get(claims.getRoles()));
        if (out.isEmpty()) {
            addAll(out, jwt.getClaims().get("groups"));
        }
        if (out.isEmpty() && jwt.getClaims().get("realm_access") instanceof Map<?, ?> realm) {
            addAll(out, realm.get("roles"));
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> c) {
            c.stream().filter(Objects::nonNull).map(String::valueOf).forEach(out::add);
        } else if (value instanceof String s && !s.isBlank()) {
            for (String x : s.split("[,\\s]+")) {
                out.add(x);
            }
        }
        return List.copyOf(out);
    }

    private static void addAll(Set<String> out, Object value) {
        for (String g : strings(value)) {
            out.add(g.startsWith("/") ? g.substring(1) : g); // Keycloak group paths "/name"
        }
    }
}
