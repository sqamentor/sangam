// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.port.SeatHoldings;

/**
 * Builds the {@link RequestContext} from the authenticated {@link SangamPrincipal} (request attribute set by the
 * server's authentication layer) and the Sangam headers, and tags the current OpenTelemetry span (R13). The seat
 * context of the headers is accepted only when it is among the {@link SeatHoldings} the host resolved for the request.
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
        if (org.sangam.core.layer.TenantKey.SHARED.equals(p.tenantId())) {
            // SL-86: the reserved key of rows shared by all tenants is never a caller's tenant
            throw new ApiProblem(401, "UNAUTHENTICATED", "The token's tenant claim is a reserved value");
        }
        String seat = blankToNull(request.getHeader(SEAT_HEADER));
        String actingFor = blankToNull(request.getHeader(ACTING_FOR_HEADER));
        if (seat != null || actingFor != null) {
            // IX.5 rules 1 and 4, K7: the caller only names the seat context; whether that person holds the seat now
            // is the host's seat directory's answer for this request. Checked here, where every request context is
            // built, so no port or adapter ever sees a seat that was not verified (403 SEAT_NOT_HELD otherwise).
            SeatHoldings.require(request.getAttribute(SeatHoldings.ATTRIBUTE) instanceof SeatHoldings held ? held : null,
                    seat, actingFor);
        }
        RequestContext ctx = new RequestContext(p.tenantId(), p.principalId(), p.groups(), seat, actingFor,
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
