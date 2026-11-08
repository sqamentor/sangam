// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.oidc;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.core.security.SecurityIdentity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps verified OIDC claims to a token identity (R50, R67, ARCHITECTURE.md §4 "tenant from the token claim
 * {@code tenant}").
 *
 * <ul>
 *   <li>tenant: claim {@code tenant} (configurable); a token without it is rejected (VI.4 rule 6);</li>
 *   <li>roles: Keycloak realm roles ({@code realm_access.roles}) and client roles of the configured client
 *       ({@code resource_access.<client>.roles}), plus a plain {@code roles} claim for other IdPs;</li>
 *   <li>groups: the {@code groups} claim; Keycloak group paths ({@code /dept/section-a}) lose the leading slash;</li>
 *   <li>the person id is the subject until {@code PrincipalResolver} maps it to a Sangam person;</li>
 *   <li>seats are never taken from the token (AJ-05 step 3).</li>
 * </ul>
 */
public final class KeycloakClaimsMapper {

    private final SecurityProperties props;

    public KeycloakClaimsMapper(SecurityProperties props) {
        this.props = props;
    }

    public SecurityIdentity map(Map<String, Object> claims, String clientIp) {
        Object tenant = claims.get(props.getTenantClaim());
        if (tenant instanceof Collection<?> coll) {
            if (coll.size() != 1) {
                throw new SangamSecurityException.Unauthenticated("TENANT_CLAIM_INVALID", "the token must name exactly one tenant");
            }
            tenant = coll.iterator().next();
        }
        if (tenant == null || tenant.toString().isBlank()) {
            throw new SangamSecurityException.Unauthenticated("TENANT_CLAIM_MISSING", "the token carries no tenant");
        }
        Object sub = claims.get("sub");
        if (sub == null) {
            throw new SangamSecurityException.Unauthenticated("UNAUTHENTICATED", "the token has no subject");
        }
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(strings(nested(claims, "realm_access", "roles")));
        if (props.getClientId() != null) {
            Object ra = claims.get("resource_access");
            if (ra instanceof Map<?, ?> m && m.get(props.getClientId()) instanceof Map<?, ?> client) {
                roles.addAll(strings(client.get("roles")));
            }
        }
        roles.addAll(strings(claims.get("roles")));
        Set<String> groups = new LinkedHashSet<>();
        for (String g : strings(claims.get(props.getGroupsClaim()))) {
            groups.add(g.startsWith("/") ? g.substring(1) : g);
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String k : List.of("acr", "email", "preferred_username", "name", "jti", "sid", "iat")) {
            if (claims.get(k) != null) {
                attrs.put(k, claims.get(k).toString());
            }
        }
        if (claims.get("amr") != null) {
            attrs.put("amr", String.join(",", strings(claims.get("amr"))));
        }
        return new SecurityIdentity(tenant.toString(), sub.toString(), sub.toString(), groups, roles, Set.of(), Set.of(),
                "OIDC", clientIp, null, false, attrs);
    }

    /** R67 MFA pass-through: for tenants that require MFA, the token's {@code acr}/{@code amr} must show it. */
    public void requireMfaIfConfigured(SecurityIdentity id) {
        if (!props.getMfaRequiredTenants().contains(id.tenantId())) {
            return;
        }
        String acr = id.attributes().getOrDefault("acr", "");
        String amr = id.attributes().getOrDefault("amr", "");
        boolean mfa = props.getMfaAcrValues().contains(acr)
                || List.of(amr.split(",")).stream().anyMatch(a -> a.equals("mfa") || a.equals("otp") || a.equals("hwk")
                || a.equals("sms") || a.equals("swk"));
        if (!mfa) {
            throw new SangamSecurityException.Unauthenticated("MFA_REQUIRED", "this department requires multi-factor sign-in");
        }
    }

    private static Object nested(Map<String, Object> claims, String a, String b) {
        Object o = claims.get(a);
        return o instanceof Map<?, ?> m ? m.get(b) : null;
    }

    private static List<String> strings(Object o) {
        if (o instanceof Collection<?> c) {
            return c.stream().filter(x -> x != null).map(Object::toString).toList();
        }
        if (o instanceof String s && !s.isBlank()) {
            return List.of(s.split("[ ,]+"));
        }
        return List.of();
    }
}
