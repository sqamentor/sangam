// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.Problems;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * IR-3 receiving side of cross-cell messages. A {@code POST /api/v1/messages} that carries
 * {@code X-Sangam-Source-Cell} is accepted only from a service principal with role {@code XCELL}
 * (authority group {@code XCELL}); it is written to the integration inbox, exactly once:
 * <ul>
 *   <li>source {@code xcell:<sourceCell>}, {@code message_id} = the {@code Idempotency-Key} (the sender's
 *       operationId), tenant = {@code X-Sangam-Tenant} (the target tenant), event type {@value #EVENT_TYPE};</li>
 *   <li>a duplicate key answers {@code 200 {"status":"DUPLICATE"}} (the sender treats 2xx and 409 as done);</li>
 *   <li>the inbox handler {@link CellCrossCellInboxHandler} correlates the message later, in an engine transaction.</li>
 * </ul>
 * Plain {@code /api/v1/messages} calls without the header are not touched.
 */
public final class CellCrossCellInboxFilter extends OncePerRequestFilter {

    public static final String EVENT_TYPE = "sangam.xcell.message";
    public static final String ROLE = "XCELL";
    private static final int MAX_BODY = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InboxService inbox;

    public CellCrossCellInboxFilter(InboxService inbox) {
        this.inbox = inbox;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !path.equals("/api/v1/messages")
                || request.getHeader("X-Sangam-Source-Cell") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object p = request.getAttribute(SangamPrincipal.ATTRIBUTE);
        Set<String> groups = p instanceof SangamPrincipal sp ? sp.groups() : Set.of();
        if (!groups.contains(ROLE)) {
            Problems.write(request, response, ApiProblem.forbidden("ACTION_NOT_PERMITTED",
                    "cross-cell messages need a service principal with role XCELL"));
            return;
        }
        String sourceCell = request.getHeader("X-Sangam-Source-Cell").trim();
        String tenant = request.getHeader("X-Sangam-Tenant");
        String key = request.getHeader("Idempotency-Key");
        if (tenant == null || tenant.isBlank() || key == null || key.isBlank() || sourceCell.isBlank()) {
            Problems.write(request, response, ApiProblem.badRequest("VALIDATION_FAILED",
                    "X-Sangam-Tenant, X-Sangam-Source-Cell and Idempotency-Key are required"));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            Problems.write(request, response, new ApiProblem(413, "PAYLOAD_TOO_LARGE", "cross-cell message above 1 MiB"));
            return;
        }
        Map<?, ?> payload;
        try {
            payload = JSON.readValue(body, Map.class);
        } catch (IOException e) {
            Problems.write(request, response, ApiProblem.badRequest("MALFORMED_BODY", "the message is not a JSON object"));
            return;
        }
        Object messageName = payload.get("messageName");
        if (messageName == null || messageName.toString().isBlank()) {
            Problems.write(request, response, ApiProblem.unprocessable("VALIDATION_FAILED", "messageName is required"));
            return;
        }
        Object bk = payload.get("businessKey");
        String sourceTenant = request.getHeader("X-Sangam-Source-Tenant");
        String principal = ((SangamPrincipal) p).principalId() + (sourceTenant == null ? "" : "@" + sourceTenant);
        Inbox.ReceiveResult r;
        try {
            r = inbox.receive(new ChannelBinding("xcell:" + sourceCell, tenant.trim(), Set.of(tenant.trim()), principal),
                    new InboundMessage(key.trim(), EVENT_TYPE, bk == null ? null : bk.toString(), tenant.trim(),
                            new String(body, StandardCharsets.UTF_8)));
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
                + JSON.writeValueAsString(key.trim()) + "}").getBytes(StandardCharsets.UTF_8));
    }
}
