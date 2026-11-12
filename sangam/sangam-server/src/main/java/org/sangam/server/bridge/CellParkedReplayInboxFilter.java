// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.core.layer.Inbox;
import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.InboxService;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SL-142 / owner decision 61: the receiving side of a parked-message replay ({@link CellParkedReplayTransport}).
 * {@code POST /api/v1/messages/replay} from a service principal with role {@code XCELL} writes the message into this
 * cell's inbox exactly as the broker consumer of the old cell would have:
 * <ul>
 *   <li>source = {@value #SOURCE_HEADER} (the ORIGINAL channel source, not {@code xcell:*}), message id =
 *       {@code Idempotency-Key} (the original message id), event type = {@value #EVENT_TYPE_HEADER}, correlation key =
 *       {@value #CORRELATION_HEADER}, tenant = {@code X-Sangam-Tenant}; the body is the original payload;</li>
 *   <li>the inbox deduplicates on (tenant, source, message id): a repeated replay, or the same message arriving later
 *       from the broker, answers {@code 200 {"status":"DUPLICATE"}}; a new one {@code 202};</li>
 *   <li>the tenant's move state is enforced before this filter by {@link CellTenantStateFilter} (order 40 &lt; 45):
 *       a tenant that is not ACTIVE here is refused, and the sender keeps the item open.</li>
 * </ul>
 * Unlike {@link CellCrossCellInboxFilter} there is no {@code messageName} rule and the event type is not replaced: the
 * message keeps its original meaning and is handled by the handler registered for its event type.
 */
public final class CellParkedReplayInboxFilter extends OncePerRequestFilter {

    public static final String PATH = CellParkedReplayTransport.PATH;
    public static final String SOURCE_HEADER = "X-Sangam-Original-Source";
    public static final String EVENT_TYPE_HEADER = "X-Sangam-Event-Type";
    public static final String CORRELATION_HEADER = "X-Sangam-Correlation-Key";
    private static final int MAX_BODY = 1024 * 1024;
    private static final int MAX_HEADER = 255;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InboxService inbox;

    public CellParkedReplayInboxFilter(InboxService inbox) {
        this.inbox = inbox;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !path.equals(PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object p = request.getAttribute(SangamPrincipal.ATTRIBUTE);
        Set<String> groups = p instanceof SangamPrincipal sp ? sp.groups() : Set.of();
        if (!groups.contains(CellCrossCellInboxFilter.ROLE)) {
            Problems.write(request, response, ApiProblem.forbidden("ACTION_NOT_PERMITTED",
                    "a parked-message replay needs a service principal with role XCELL"));
            return;
        }
        String tenant = trim(request.getHeader("X-Sangam-Tenant"));
        String sourceCell = trim(request.getHeader("X-Sangam-Source-Cell"));
        String source = trim(request.getHeader(SOURCE_HEADER));
        String messageId = trim(request.getHeader("Idempotency-Key"));
        String eventType = trim(request.getHeader(EVENT_TYPE_HEADER));
        String correlation = trim(request.getHeader(CORRELATION_HEADER));
        if (tenant == null || sourceCell == null || source == null || messageId == null) {
            Problems.write(request, response, ApiProblem.badRequest("VALIDATION_FAILED",
                    "X-Sangam-Tenant, X-Sangam-Source-Cell, " + SOURCE_HEADER + " and Idempotency-Key are required"));
            return;
        }
        for (String h : new String[]{tenant, sourceCell, source, messageId, eventType, correlation}) {
            if (h != null && h.length() > MAX_HEADER) {
                Problems.write(request, response, ApiProblem.badRequest("VALIDATION_FAILED",
                        "replay headers are limited to " + MAX_HEADER + " characters"));
                return;
            }
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            Problems.write(request, response, new ApiProblem(413, "PAYLOAD_TOO_LARGE", "replayed message above 1 MiB"));
            return;
        }
        String payload = body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
        String principal = ((SangamPrincipal) p).principalId() + "@" + sourceCell;
        Inbox.ReceiveResult r;
        try {
            r = inbox.receive(new ChannelBinding(source, tenant, Set.of(tenant), principal),
                    new InboundMessage(messageId, eventType, correlation, tenant, payload));
        } catch (PoisonInbound.PoisonMessageException e) {
            Problems.write(request, response, ApiProblem.unprocessable("MALFORMED_BODY", e.getMessage()));
            return;
        } catch (RuntimeException e) {
            if (e.getClass().getSimpleName().contains("Backpressure")) {
                Problems.write(request, response, ApiProblem.unavailable("BACKPRESSURE", e.getMessage(), 5));
                return;
            }
            throw e;
        }
        response.setStatus(r == Inbox.ReceiveResult.DUPLICATE ? 200 : 202);
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"status\":\"" + r.name() + "\",\"messageId\":"
                + JSON.writeValueAsString(messageId) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
