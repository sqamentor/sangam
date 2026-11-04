// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the IR-4 tenant move states on every request of the tenant (cell tenant-admin calls are exempt):
 * <ul>
 *   <li>FROZEN: mutations (POST/PUT/PATCH/DELETE) and inbound events → {@code 503 TENANT_FROZEN} with
 *       {@code Retry-After}; reads and the completion of work already locked ({@code /api/v1/work/{id}/...}) continue,
 *       so in-flight work drains; new {@code fetch-and-lock} is refused;</li>
 *   <li>IMPORTING: every request → 503 (the tenant is not open yet);</li>
 *   <li>MOVED / PURGED: every request → {@code 421 TENANT_MOVED} with {@code targetCell}, so a stale gateway
 *       re-resolves the route.</li>
 * </ul>
 * The tenant comes from the authenticated principal, else the {@code X-Sangam-Tenant} header (public endpoints).
 * Runs after authentication and before the back-pressure / idempotency filters' command execution.
 */
public final class CellTenantStateFilter extends OncePerRequestFilter {

    public static final String ADMIN_PREFIX = "/api/v1/admin/cell/";
    private static final int RETRY_AFTER_SECONDS = 30;

    private final CellTenantStateStore states;

    public CellTenantStateFilter(CellTenantStateStore states) {
        this.states = states;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith(ADMIN_PREFIX) || !(path.startsWith("/api/") || path.startsWith("/engine-rest/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = tenantOf(request);
        if (tenant == null || tenant.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        CellTenantStateStore.TenantState s = states.get(tenant);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        switch (s.state()) {
            case ACTIVE -> chain.doFilter(request, response);
            case FROZEN -> {
                if (isMutation(request) && !isInFlightWorkCompletion(path)) {
                    Problems.write(request, response, new ApiProblem(503, "TENANT_FROZEN", null,
                            "Tenant " + tenant + " is being moved; retry shortly", Map.of("tenant", tenant),
                            RETRY_AFTER_SECONDS));
                    return;
                }
                chain.doFilter(request, response);
            }
            case IMPORTING -> Problems.write(request, response, new ApiProblem(503, "TENANT_IMPORTING", null,
                    "Tenant " + tenant + " is being imported into this cell", Map.of("tenant", tenant),
                    RETRY_AFTER_SECONDS));
            case MOVED, PURGED -> {
                ApiProblem p = new ApiProblem(421, "TENANT_MOVED", "Misdirected request",
                        "Tenant " + tenant + " has moved to another cell", s.targetCell() == null
                        ? Map.of("tenant", tenant) : Map.of("tenant", tenant, "targetCell", s.targetCell()), null);
                if (s.targetCell() != null) {
                    response.setHeader("X-Sangam-Target-Cell", s.targetCell());
                }
                Problems.write(request, response, p);
            }
        }
    }

    private static boolean isMutation(HttpServletRequest r) {
        String m = r.getMethod();
        return m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE");
    }

    /** Completing, failing, extending or releasing work that is already locked lets the tenant drain (VI.3). */
    private static boolean isInFlightWorkCompletion(String path) {
        return path.startsWith("/api/v1/work/") && !path.equals("/api/v1/work/fetch-and-lock");
    }

    static String tenantOf(HttpServletRequest request) {
        Object p = request.getAttribute(SangamPrincipal.ATTRIBUTE);
        if (p instanceof SangamPrincipal sp && sp.tenantId() != null && !sp.tenantId().isBlank()
                && !"*".equals(sp.tenantId())) {
            return sp.tenantId();
        }
        String h = request.getHeader("X-Sangam-Tenant");
        return h == null || h.isBlank() ? request.getParameter("tenant") : h.trim();
    }
}
