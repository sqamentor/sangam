// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.util.List;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.tenant.SharedDeployments;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.RequestContext;

/**
 * Decisions 144 and 197 (owner 250 for reading): the shared-deployment registry routes over the engine's
 * {@link SharedDeployments}. The bridge maps shapes and errors only; every rule (platform deployer, lengths, the id is an
 * untenanted engine deployment, revocation is final, the row and its SHARED_DEPLOYMENT_REGISTERED or
 * SHARED_DEPLOYMENT_REVOKED audit event in one transaction) is the engine's. The actor is the authenticated
 * {@link SecurityIdentity} of the request, never a body field. {@link SharedDeployments.SharedDeploymentException#code()}
 * maps to the contract {@code routes/shared_deployments.py}: VALIDATION_FAILED 400, ACTION_NOT_PERMITTED 403, NOT_FOUND
 * 404, SHARED_DEPLOYMENT_REGISTERED and SHARED_DEPLOYMENT_REVOKED 409, SHARED_DEPLOYMENT_INVALID 422.
 */
public final class SharedDeploymentBridge {

    /** One registry row as the contract's {@code SharedDeployment}. */
    public record SharedDeployment(String deploymentId, String registeredBy, String reason, Instant registeredAt,
                                   String revokedBy, Instant revokedAt, String revokeReason) {
    }

    /** The engine calls; {@link #of(SharedDeployments)} for the real engine. */
    public interface Registry {
        SharedDeployments.Registration register(SecurityIdentity actor, String deploymentId, String reason);

        SharedDeployments.Registration revoke(SecurityIdentity actor, String deploymentId, String reason);

        List<SharedDeployments.Registration> list(boolean includeRevoked);

        static Registry of(SharedDeployments engine) {
            return new Registry() {
                @Override
                public SharedDeployments.Registration register(SecurityIdentity actor, String deploymentId, String reason) {
                    return engine.register(actor, deploymentId, reason);
                }

                @Override
                public SharedDeployments.Registration revoke(SecurityIdentity actor, String deploymentId, String reason) {
                    return engine.revoke(actor, deploymentId, reason);
                }

                @Override
                public List<SharedDeployments.Registration> list(boolean includeRevoked) {
                    return engine.list(includeRevoked);
                }
            };
        }
    }

    private final Registry engine;

    public SharedDeploymentBridge(Registry engine) {
        this.engine = engine;
    }

    public List<SharedDeployment> list(RequestContext ctx, boolean includeRevoked) {
        return call(() -> engine.list(includeRevoked)).stream().map(SharedDeploymentBridge::view).toList();
    }

    public SharedDeployment register(RequestContext ctx, String deploymentId, String reason) {
        SecurityIdentity actor = SecurityIdentities.of(ctx);
        return view(call(() -> engine.register(actor, deploymentId, reason)));
    }

    public SharedDeployment revoke(RequestContext ctx, String deploymentId, String reason) {
        SecurityIdentity actor = SecurityIdentities.of(ctx);
        return view(call(() -> engine.revoke(actor, deploymentId, reason)));
    }

    static SharedDeployment view(SharedDeployments.Registration r) {
        return new SharedDeployment(r.deploymentId(), r.registeredBy(), r.reason(), r.registeredAt(), r.revokedBy(),
                r.revokedAt(), r.revokeReason());
    }

    static <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            throw problem(e);
        }
    }

    /** The REST problem for an engine refusal (the cause chain is searched, so a wrapped refusal maps the same). */
    static RuntimeException problem(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiProblem p) {
                return p;
            }
            if (t instanceof SharedDeployments.SharedDeploymentException s) {
                String detail = s.getMessage();
                String prefix = s.code() + ": ";
                if (detail != null && detail.startsWith(prefix)) {
                    detail = detail.substring(prefix.length());
                }
                return switch (s.code()) {
                    case SharedDeployments.VALIDATION_FAILED -> ApiProblem.badRequest(s.code(), detail);
                    case SharedDeployments.NOT_PERMITTED -> ApiProblem.forbidden(s.code(), detail);
                    case SharedDeployments.NOT_FOUND -> new ApiProblem(404, s.code(), detail);
                    case SharedDeployments.REGISTERED, SharedDeployments.REVOKED -> ApiProblem.conflict(s.code(), detail);
                    case SharedDeployments.INVALID -> ApiProblem.unprocessable(s.code(), detail);
                    default -> new IllegalStateException("decision 144: unknown shared-deployment code " + s.code(), s);
                };
            }
        }
        return e;
    }
}
