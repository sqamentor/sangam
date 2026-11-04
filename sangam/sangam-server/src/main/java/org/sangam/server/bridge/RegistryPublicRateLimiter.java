// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate limit for the unauthenticated registry verification endpoints (registry README §2: "must be
 * permitted without authentication and rate-limited"). Fixed one-minute windows per client address; above the limit
 * the answer is {@code 429 RATE_LIMITED} with {@code Retry-After}. Node-local by design: a verification endpoint is
 * read-only, so a per-node limit bounds abuse without shared state.
 */
public final class RegistryPublicRateLimiter extends OncePerRequestFilter {

    private record Window(long minute, AtomicInteger count) {
    }

    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RegistryPublicRateLimiter(int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        String client = request.getRemoteAddr();
        Window w = windows.compute(client, (k, old) -> old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger()) : old);
        if (w.count().incrementAndGet() > perMinute) {
            long retry = 60 - (System.currentTimeMillis() / 1000) % 60;
            Problems.write(request, response, new ApiProblem(429, "RATE_LIMITED", null,
                    "Too many verification requests; try again later", null, (int) retry));
            return;
        }
        if (windows.size() > 100_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        chain.doFilter(request, response);
    }
}
