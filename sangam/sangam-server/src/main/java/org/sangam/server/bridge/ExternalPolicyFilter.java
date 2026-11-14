// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.CommandCatalog;
import org.sangam.rest.web.Problems;
import org.sangam.rest.web.RequestContexts;
import org.sangam.security.policy.external.ExternalPolicyGate;
import org.sangam.security.policy.external.ExternalPolicyRequest;
import org.sangam.security.policy.external.ExternalPolicyRequirement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Owner decisions 166 and 200 (M-083): the external deny-only policy check at command entry. For every state-changing
 * Sangam REST command of the {@link CommandCatalog}, every external-worker call and every state-changing
 * {@code /engine-rest} call ({@link ExternalPolicyTargets}) it asks {@link ExternalPolicyGate} before the call starts:
 * <ul>
 *   <li>it runs after authentication (Spring Security, order -100, sets the principal) and before the
 *       {@code IdempotencyFilter}, so no idempotency key is claimed, no command transaction is open and no L2 lock is
 *       held when the remote call is made (decision 86; the gate asserts it);</li>
 *   <li>{@code ALLOW_CONTINUE} only lets the command go on: Sangam's own endpoint and object checks still run and still
 *       refuse (deny-only, nothing is granted here);</li>
 *   <li>a DENY answers {@code 403 POLICY_DENIED} (extension {@code reason_code}); an unavailable service for a command
 *       that requires the check answers {@code 503 POLICY_UNAVAILABLE} with {@code Retry-After} (fail closed);</li>
 *   <li>each refusal is recorded as {@code AUTH_DENIED} on the denial chain of {@code sec:<tenant>} (the same name and
 *       chain as Sangam's own refusals). The refusal does not depend on that record: a failed audit write is logged and
 *       the command is still refused.</li>
 * </ul>
 * Requests that match no command, commands this installation does not check ({@code NONE}), and requests without an
 * authenticated principal (refused later with 401) pass through unchanged. A checked call whose caller has no tenant
 * (the policy request needs one) is refused with 401 before the call (fail closed). The request body is never read.
 */
public final class ExternalPolicyFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalPolicyFilter.class);

    /** 403: the external policy service refused the command. */
    public static final String POLICY_DENIED = "POLICY_DENIED";
    /** 503: the external policy service required for the command gave no usable answer. */
    public static final String POLICY_UNAVAILABLE = "POLICY_UNAVAILABLE";
    /** The existing X.4.0.3 name of an authorisation refusal. */
    static final String AUDIT_EVENT = "AUTH_DENIED";

    /**
     * Writes the refusal record: in the server the REST {@code DenialAudit} (denial chain of {@code chainId}, own leaf
     * transaction, SL-132).
     */
    @FunctionalInterface
    public interface RefusalAudit {
        void record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload);
    }

    private final ExternalPolicyTargets targets;
    private final ExternalPolicyGate gate;
    private final RefusalAudit audit;
    private final int retryAfterSeconds;

    public ExternalPolicyFilter(ExternalPolicyTargets targets, ExternalPolicyGate gate, RefusalAudit audit,
                                int retryAfterSeconds) {
        if (targets == null || gate == null || audit == null) {
            throw new IllegalArgumentException("policy targets, policy gate and denial audit are required");
        }
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retry-after must be at least 1 s");
        }
        this.targets = targets;
        this.gate = gate;
        this.audit = audit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ExternalPolicyTargets.covers(request.getMethod(), path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var match = targets.resolve(request.getMethod(), path(request));
        if (match.isEmpty() || RequestContexts.principal(request) == null
                || gate.requirement(match.get().commandType()) == ExternalPolicyRequirement.NONE) {
            chain.doFilter(request, response);
            return;
        }
        ExternalPolicyTargets.Target command = match.get();
        RequestContext ctx;
        try {
            ctx = RequestContexts.from(request);
        } catch (ApiProblem p) {
            Problems.write(request, response, p);
            return;
        }
        ExternalPolicyRequest policyRequest = new ExternalPolicyRequest(ctx.tenantId(), ctx.principalId(),
                ctx.authMethod(), ctx.groups(), command.commandType(), command.method(), command.pathTemplate(),
                command.pathParams());
        ExternalPolicyGate.Verdict verdict = gate.evaluate(policyRequest);
        if (verdict instanceof ExternalPolicyGate.Refuse refuse) {
            refuse(request, response, ctx, command, refuse);
            return;
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, RequestContext ctx,
                        ExternalPolicyTargets.Target command, ExternalPolicyGate.Refuse refuse) throws IOException {
        boolean denied = refuse.result() == ExternalPolicyGate.Result.DENIED;
        String code = denied ? POLICY_DENIED : POLICY_UNAVAILABLE;
        record(ctx, command, code, refuse);
        LOG.info("decision 166: {} refused for tenant {}: {} ({})", command.commandType(), ctx.tenantId(), code,
                refuse.reasonCode());
        ApiProblem problem = denied
                ? ApiProblem.forbidden(POLICY_DENIED, "The command was refused by the installation's policy service")
                        .with("reason_code", refuse.reasonCode())
                : ApiProblem.unavailable(POLICY_UNAVAILABLE, "The policy service that this command requires did not"
                        + " answer; the command was not started. Retry later", retryAfterSeconds);
        Problems.write(request, response, problem);
    }

    private void record(RequestContext ctx, ExternalPolicyTargets.Target command, String code,
                        ExternalPolicyGate.Refuse refuse) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", command.commandType());
        payload.put("outcome", "DENIED");
        payload.put("reason", code);
        payload.put("policy_source", "external");
        payload.put("external_reason_code", refuse.reasonCode());
        payload.put("external_policy_id", refuse.policyId());
        payload.values().removeIf(java.util.Objects::isNull);
        try {
            audit.record(ctx, "sec:" + ctx.tenantId(), AUDIT_EVENT, payload);
        } catch (RuntimeException e) {
            // deny-only: the refusal stands without its record; the failure is visible in the log and the metric
            LOG.warn("decision 166: the AUTH_DENIED record of a refused {} could not be written: {}",
                    command.commandType(), e.getClass().getSimpleName());
            io.micrometer.core.instrument.Metrics.counter("sangam.security.external.policy.audit.failures",
                    "command", command.commandType()).increment();
        }
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctxPath = request.getContextPath();
        return ctxPath != null && !ctxPath.isEmpty() && uri.startsWith(ctxPath) ? uri.substring(ctxPath.length()) : uri;
    }
}
