// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.impl.identity.Authentication;
import org.sangam.rest.port.SangamPrincipal;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SL-45: scopes the Operaton REST API ({@code /engine-rest/**}) to the caller's tenant. Operaton's tenant check
 * ({@code tenantCheckEnabled=true}) applies only while an engine authentication is set on the thread; without one every
 * query, get-by-id, deployment, start, completion and batch of {@code /engine-rest} saw and changed every tenant. This
 * filter sets the token's identity with exactly the token's tenant for the duration of the request, so the engine
 * itself filters queries (another tenant's objects do not exist), refuses commands on other tenants' objects and
 * refuses deployments into another tenant. A request without a Sangam principal or tenant is refused (fail closed).
 * The previous engine authentication of the thread is restored afterwards.
 */
public final class EngineRestTenantFilter extends OncePerRequestFilter {

    static final String PREFIX = "/engine-rest";

    private final Supplier<IdentityService> identity;
    private final Supplier<java.util.Collection<String>> adminGroups;

    /**
     * @param identity    the engine's identity service (resolved per request: the engine starts after the chain)
     * @param adminGroups the engine's Operaton admin groups, which bypass its tenant check
     */
    public EngineRestTenantFilter(Supplier<IdentityService> identity, Supplier<java.util.Collection<String>> adminGroups) {
        this.identity = identity;
        this.adminGroups = adminGroups;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SangamPrincipal p = request.getAttribute(SangamPrincipal.ATTRIBUTE) instanceof SangamPrincipal sp ? sp : null;
        IdentityService ids = identity.get();
        if (p == null || p.tenantId() == null || p.tenantId().isBlank() || ids == null) {
            ProblemResponses.write(request, response, 403, "TENANT_REQUIRED", "Tenant required",
                    "The Operaton REST API is available only to an identity of one tenant");
            return;
        }
        Authentication previous = ids.getCurrentAuthentication();
        // an Operaton admin group would switch the engine's tenant check off: never carried over from a token
        List<String> groups = p.groups() == null ? new ArrayList<>() : new ArrayList<>(p.groups());
        groups.removeAll(adminGroups.get());
        ids.setAuthentication(p.principalId(), groups, List.of(p.tenantId().trim()));
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                ids.clearAuthentication();
            } else {
                ids.setAuthentication(previous);
            }
        }
    }
}
