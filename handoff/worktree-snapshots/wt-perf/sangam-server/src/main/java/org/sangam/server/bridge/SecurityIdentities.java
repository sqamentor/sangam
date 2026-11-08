// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.security.tenant.TenantContext;

/** Conversions between the REST layer's identity ({@link RequestContext}, {@link SangamPrincipal}) and sangam-security's. */
public final class SecurityIdentities {

    /** Request attribute carrying the resolved {@link SecurityIdentity} (set by {@link SecurityIdentityFilter}). */
    public static final String ATTRIBUTE = SecurityIdentity.class.getName();

    private SecurityIdentities() {
    }

    /**
     * The identity for a port call: the one resolved for this request (TenantContext, with seats and roles from the
     * organisation model) when it belongs to the same tenant and principal, else a token identity built from the context.
     */
    public static SecurityIdentity of(RequestContext ctx) {
        SecurityIdentity current = TenantContext.current().orElse(null);
        if (current != null && current.tenantId().equals(ctx.tenantId())
                && (current.system() || current.personId().equals(ctx.principalId())
                || current.subject().equals(ctx.principalId()))) {
            return current;
        }
        if (ctx.authMethod() != null && ctx.authMethod().startsWith("SYSTEM")) {
            return SecurityIdentity.system(ctx.tenantId(), ctx.authMethod());
        }
        Set<String> acting = ctx.actingForSeatId() == null ? Set.of() : Set.of(ctx.actingForSeatId());
        Set<String> seats = ctx.seatId() == null ? Set.of() : Set.of(ctx.seatId());
        return new SecurityIdentity(ctx.tenantId(), ctx.principalId(), ctx.principalId(), ctx.groups(), ctx.groups(),
                seats, acting, ctx.authMethod(), ctx.clientIp(), null, false, Map.of());
    }

    /** The unresolved token identity of an authenticated {@link SangamPrincipal}. */
    public static SecurityIdentity fromPrincipal(SangamPrincipal p, String clientIp) {
        Set<String> seats = new LinkedHashSet<>();
        if (p.claims().get("seats") instanceof Collection<?> c) {
            c.forEach(s -> seats.add(String.valueOf(s)));
        }
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        for (String k : new String[]{"name", "preferred_username", "email", "sid", "jti"}) {
            Object v = p.claims().get(k);
            if (v != null) {
                attributes.put(k, String.valueOf(v));
            }
        }
        return new SecurityIdentity(p.tenantId(), p.principalId(), p.principalId(), p.groups(), p.groups(), seats,
                Set.of(), p.authMethod(), clientIp, null, false, attributes);
    }

    /** The {@link SangamPrincipal} for an identity authenticated by sangam-security (API tokens). */
    public static SangamPrincipal toPrincipal(SecurityIdentity id) {
        Set<String> groups = new LinkedHashSet<>(id.groups());
        groups.addAll(id.roles());
        Map<String, Object> claims = new java.util.LinkedHashMap<>(id.attributes());
        claims.put("seats", java.util.List.copyOf(id.seats()));
        return new SangamPrincipal(id.tenantId(), id.personId(), groups, id.authMethod(), claims);
    }
}
