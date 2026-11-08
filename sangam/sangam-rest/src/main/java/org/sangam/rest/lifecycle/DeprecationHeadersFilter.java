// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import io.micrometer.core.instrument.Metrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SL-38: lifecycle headers for operations in the {@link ApiDeprecationRegistry}.
 * <ul>
 *   <li>Before the sunset date the request runs normally and the response carries
 *       {@code Deprecation: @<unix-seconds>} (RFC 9745), {@code Sunset: <HTTP-date>} (RFC 8594),
 *       {@code Link: <changelog>; rel="deprecation"} and, when the registry names one,
 *       {@code Link: <successor>; rel="successor-version"} (RFC 5829).</li>
 *   <li>From the sunset date (00:00 UTC) the operation is retired: {@code 410 API_SUNSET} problem, same headers, and
 *       the request does not reach the controller.</li>
 * </ul>
 * Headers are set before the chain runs so that they survive a committed or buffered body.
 */
public class DeprecationHeadersFilter extends OncePerRequestFilter {

    /** RFC 9110 section 5.6.7 IMF-fixdate (two-digit day; {@code RFC_1123_DATE_TIME} would print {@code 1 Jan}). */
    static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);

    private final ApiDeprecationRegistry registry;
    private final Clock clock;

    public DeprecationHeadersFilter(ApiDeprecationRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return registry.entries().isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiDeprecationRegistry.Entry> hit = registry.find(request.getMethod(), path(request));
        if (hit.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ApiDeprecationRegistry.Entry e = hit.get();
        Instant sunsetAt = e.sunset().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant deprecatedAt = e.deprecated().atStartOfDay(ZoneOffset.UTC).toInstant();
        response.setHeader("Deprecation", "@" + deprecatedAt.getEpochSecond());
        response.setHeader("Sunset", HTTP_DATE.format(sunsetAt.atOffset(ZoneOffset.UTC)));
        response.addHeader("Link", "<" + e.link() + ">; rel=\"deprecation\"");
        if (e.successor() != null) {
            response.addHeader("Link", "<" + request.getContextPath() + ApiDeprecationRegistry.BASE + e.successor()
                    + ">; rel=\"successor-version\"");
        }
        if (!clock.instant().isBefore(sunsetAt)) {
            Metrics.counter("api.deprecated.calls", "state", "sunset", "method", e.method(), "path", e.path()).increment();
            Problems.write(request, response, new ApiProblem(410, "API_SUNSET", null,
                    e.method() + " " + ApiDeprecationRegistry.BASE + e.path() + " was retired on " + e.sunset()
                            + "; see " + e.link(),
                    e.successor() == null ? Map.of() : Map.of("successor", ApiDeprecationRegistry.BASE + e.successor()),
                    null));
            return;
        }
        Metrics.counter("api.deprecated.calls", "state", "deprecated", "method", e.method(), "path", e.path()).increment();
        chain.doFilter(request, response);
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
