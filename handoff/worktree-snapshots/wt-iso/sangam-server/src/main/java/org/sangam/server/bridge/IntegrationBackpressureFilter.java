// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.integration.relay.BackpressureGate;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Back-pressure on new work (VI.2.4, CX-7, K15): when the outbox budget is at or above the back-pressure level,
 * {@code POST /api/v1/process-instances} (category START) and {@code POST /api/v1/events/*} (INBOUND_EVENT) get
 * {@code 503 BACKPRESSURE} with {@code Retry-After}; continuations, task completions and drafts still pass. Runs after
 * authentication and before sangam-rest's Idempotency-Key filter, so a refused start stores nothing (X.2.2.4 step 3).
 */
public final class IntegrationBackpressureFilter extends OncePerRequestFilter {

    private final BackpressureGate gate;

    public IntegrationBackpressureFilter(BackpressureGate gate) {
        this.gate = gate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        BackpressureGate.Category category = category(path(req));
        if (category != null) {
            try {
                gate.check(category);
            } catch (BackpressureGate.BackpressureException e) {
                int retry = (int) Math.max(1, e.retryAfter() == null ? 5 : e.retryAfter().toSeconds());
                Problems.write(req, res, new ApiProblem(e.httpStatus(), e.code(), null,
                        "New submissions are paused for a short time (outbox " + e.level() + "); drafts are kept",
                        null, retry));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static BackpressureGate.Category category(String path) {
        if (path.equals("/api/v1/process-instances") || path.equals("/api/v1/case-instances")
                || path.equals("/api/v1/portal/applications")) {
            return BackpressureGate.Category.valueOf("START");
        }
        if (path.startsWith("/api/v1/events/") && !path.startsWith("/api/v1/events/channels")
                && !path.startsWith("/api/v1/events/definitions")) {
            return BackpressureGate.Category.valueOf("INBOUND_EVENT");
        }
        return null;
    }

    private static String path(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
