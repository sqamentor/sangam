// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Supplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.core.quota.TenantQuotas;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SL-43 REST entry point: every authenticated {@code /api/**} and {@code /engine-rest/**} request of a tenant takes one
 * unit of {@link QuotaLimit#API_CALLS_PER_MINUTE}; a request body declared larger than
 * {@link QuotaLimit#DOCUMENT_BYTES} ({@code Content-Length}) is refused before it is read. Over a limit:
 * {@code 429 TENANT_QUOTA_EXCEEDED} with {@code Retry-After} (rate) or {@code 413 TENANT_QUOTA_EXCEEDED} (size), an
 * RFC 9457 problem naming the limit. The tenant is the authenticated principal's only, never a request header: an
 * unauthenticated caller must not be able to spend another tenant's quota. Runs after authentication and the tenant
 * state filter, before back-pressure and the Idempotency-Key filter, so a refused request stores nothing. A quota
 * refused deeper in the request (engine, batch, content) that no controller advice mapped is answered here too.
 */
public final class TenantQuotaFilter extends OncePerRequestFilter {

    private final Supplier<TenantQuotas> quotas;

    public TenantQuotaFilter(Supplier<TenantQuotas> quotas) {
        this.quotas = quotas;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/") || path.startsWith("/engine-rest/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String tenant = tenantOf(request);
        if (tenant == null) {
            chain.doFilter(request, response);
            return;
        }
        TenantQuotas q = quotas.get();
        try {
            q.acquire(tenant, QuotaLimit.API_CALLS_PER_MINUTE, 1);
            long length = request.getContentLengthLong();
            if (length > 0 && isUpload(request)) {
                q.checkSize(tenant, QuotaLimit.DOCUMENT_BYTES, length);
            }
        } catch (TenantQuotaExceededException e) {
            write(request, response, e);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            // a quota refused deeper (engine start, variable size, batch, document) and no controller advice mapped it
            TenantQuotaExceededException quota = TenantQuotaExceededException.find(e);
            if (quota == null || response.isCommitted()) {
                throw e;
            }
            response.resetBuffer();
            write(request, response, quota);
        }
    }

    private static boolean isUpload(HttpServletRequest r) {
        String ct = r.getContentType();
        String m = r.getMethod();
        return ct != null && (m.equals("POST") || m.equals("PUT") || m.equals("PATCH"))
                && !ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    static void write(HttpServletRequest request, HttpServletResponse response, TenantQuotaExceededException e)
            throws IOException {
        Problems.write(request, response, problem(e));
    }

    static ApiProblem problem(TenantQuotaExceededException e) {
        return org.sangam.rest.web.ProblemHandler.quotaProblem(e);
    }

    static String tenantOf(HttpServletRequest request) {
        Object p = request.getAttribute(SangamPrincipal.ATTRIBUTE);
        if (p instanceof SangamPrincipal sp && sp.tenantId() != null && !sp.tenantId().isBlank()
                && !"*".equals(sp.tenantId())) {
            return sp.tenantId();
        }
        return null;
    }
}
