// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * M-016: on an instance dedicated to one tenant ({@link DedicatedTenant}), every {@code /api/} and
 * {@code /engine-rest/} request that names another tenant is refused with {@code 421 TENANT_NOT_ON_INSTANCE} before
 * it reaches any data: a misrouted request (stale gateway route, a token of another tenant) can never read or write in
 * this tenant's database. The answer never names the bound tenant.
 *
 * <p>The request's tenant is read exactly as the IR-4 move-state filter reads it ({@link CellTenantStateFilter#tenantOf}:
 * the authenticated principal, else {@code X-Sangam-Tenant} or the {@code tenant} parameter). The cell tenant-admin
 * routes ({@value CellTenantStateFilter#ADMIN_PREFIX}{@code tenants/{tenant}/...}, called by the control plane for
 * moves) carry the tenant in the path, which is checked too, so another tenant cannot be imported into a dedicated
 * instance. Requests without a tenant (health, public metadata) pass. Runs after authentication and just before the
 * move-state filter.
 *
 * <p>The same filter enforces owner decision 97 on a <b>shared</b> instance ({@link SharedInstanceIsolation}): a tenant
 * recorded with isolation DATABASE or SCHEMA is not served there, with the same answer.
 */
public final class DedicatedTenantFilter extends OncePerRequestFilter {

    public static final String CODE = "TENANT_NOT_ON_INSTANCE";
    private static final String ADMIN_TENANTS = CellTenantStateFilter.ADMIN_PREFIX + "tenants/";

    private final java.util.function.Predicate<String> serves;

    /** An instance dedicated to one tenant (M-016). */
    public DedicatedTenantFilter(DedicatedTenant binding) {
        this(binding::admits);
    }

    /** A shared instance (decision 97). */
    public DedicatedTenantFilter(SharedInstanceIsolation shared) {
        this(shared::serves);
    }

    private DedicatedTenantFilter(java.util.function.Predicate<String> serves) {
        this.serves = serves;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.startsWith("/api/") || path.startsWith("/engine-rest/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = CellTenantStateFilter.tenantOf(request);
        String pathTenant = adminPathTenant(path(request));
        String refused = !serves.test(tenant) ? tenant : !serves.test(pathTenant) ? pathTenant : null;
        if (refused != null) {
            Problems.write(request, response, new ApiProblem(421, CODE, "Misdirected request",
                    "Tenant " + refused + " is not served by this instance", Map.of("tenant", refused), null));
            return;
        }
        chain.doFilter(request, response);
    }

    /** The {@code {tenant}} of {@code /api/v1/admin/cell/tenants/{tenant}/...}, else null. */
    static String adminPathTenant(String path) {
        if (!path.startsWith(ADMIN_TENANTS)) {
            return null;
        }
        String rest = path.substring(ADMIN_TENANTS.length());
        int slash = rest.indexOf('/');
        String t = slash < 0 ? rest : rest.substring(0, slash);
        return t.isBlank() ? null : t;
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
