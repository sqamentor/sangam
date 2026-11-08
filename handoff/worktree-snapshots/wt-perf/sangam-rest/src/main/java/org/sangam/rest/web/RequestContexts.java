// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;

/**
 * Builds the {@link RequestContext} from the authenticated {@link SangamPrincipal} (request attribute set by the
 * server's authentication layer) and the Sangam headers, and tags the current OpenTelemetry span (R13).
 */
public final class RequestContexts {

    public static final String SEAT_HEADER = "X-Sangam-Seat";
    public static final String ACTING_FOR_HEADER = "X-Sangam-Acting-For-Seat";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CACHE = RequestContexts.class.getName() + ".ctx";

    private RequestContexts() {
    }

    public static SangamPrincipal principal(HttpServletRequest request) {
        Object p = request.getAttribute(SangamPrincipal.ATTRIBUTE);
        return p instanceof SangamPrincipal sp ? sp : null;
    }

    public static RequestContext from(HttpServletRequest request) {
        Object cached = request.getAttribute(CACHE);
        if (cached instanceof RequestContext c) {
            return c;
        }
        SangamPrincipal p = principal(request);
        if (p == null) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "Authentication is required");
        }
        if (p.tenantId() == null || p.tenantId().isBlank()) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "The token carries no tenant claim");
        }
        RequestContext ctx = new RequestContext(p.tenantId(), p.principalId(), p.groups(),
                blankToNull(request.getHeader(SEAT_HEADER)), blankToNull(request.getHeader(ACTING_FOR_HEADER)),
                blankToNull(request.getHeader(IDEMPOTENCY_HEADER)), traceId(), request.getRemoteAddr(),
                p.authMethod());
        Telemetry.tagContext(ctx);
        request.setAttribute(CACHE, ctx);
        return ctx;
    }

    public static String traceId() {
        SpanContext sc = Span.current().getSpanContext();
        return sc.isValid() ? sc.getTraceId() : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
