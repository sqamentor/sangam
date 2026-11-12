// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.enginerest;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.exception.NotFoundException;
import org.operaton.bpm.engine.exception.NullValueException;
import org.operaton.bpm.engine.rest.exception.RestException;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.SangamPrincipal;
import org.sangam.rest.web.Problems;
import org.sangam.server.security.PlatformIssuers;

/**
 * SL-72 (integrator decision 2026-11-12, option B): no existence oracle on {@code /engine-rest} for a tenant identity.
 * A by-id call on another tenant's object answered {@code 500} with Operaton's {@code ENGINE-13034 Cannot <command>
 * '<id>' because it belongs to no authenticated tenant} ({@code CommandLogger#exceptionCommandWithUnauthorizedTenant},
 * thrown by {@code TenantCommandChecker}), while a missing id answered 404, 400 or 500 depending on the resource, with
 * Operaton's message naming the id. So the answer told a tenant that the id exists in another tenant.
 *
 * <p>For a tenant identity every not-found answer is now one uniform {@code 404} problem with code {@code NOT_FOUND},
 * no engine code, no id and no request path: Operaton's own 404 responses, {@link NotFoundException},
 * {@link NullValueException} (Operaton's {@code ensureNotNull} for a missing entity) and the unauthorized-tenant
 * refusal. Platform identities (SL-70, {@link PlatformIssuers}) keep Operaton's original answers for diagnostics.
 * Side effect for tenant identities: a {@link NullValueException} for a missing argument also answers this 404.
 */
public final class EngineRestNotFound {

    /** Operaton's code of the unauthorized-tenant refusal: {@code BaseLogger} formats {@code ENGINE-13034 <text>}. */
    static final String UNAUTHORIZED_TENANT = "ENGINE-13034 ";

    /** The one answer for every not-found of a tenant identity. */
    static final ApiProblem PROBLEM = new ApiProblem(404, "NOT_FOUND", "The object was not found or is not visible to you");

    private EngineRestNotFound() {
    }

    /** The uniform 404 (no request path, which carries the id). */
    public static Response response() {
        return Response.status(404).type(Problems.MEDIA_TYPE).entity(Problems.bytes(PROBLEM, null)).build();
    }

    /** True when {@code request} is not a platform identity's (no principal: treated as a tenant, fail closed). */
    static boolean tenantIdentity(HttpServletRequest request) {
        Object p = request == null ? null : request.getAttribute(SangamPrincipal.ATTRIBUTE);
        return !(p instanceof SangamPrincipal sp && PlatformIssuers.isPlatformIdentity(sp.groups()));
    }

    /** True when {@code t} (or a cause) is a not-found answer of the engine or of Operaton's REST layer. */
    static boolean notFound(Throwable t) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable x = t; x != null && seen.put(x, Boolean.TRUE) == null; x = x.getCause()) {
            if (x instanceof RestException r && r.getStatus() == Response.Status.NOT_FOUND) {
                return true;
            }
            if (x instanceof NotFoundException || x instanceof NullValueException) {
                return true;
            }
            if (x instanceof ProcessEngineException && x.getMessage() != null
                    && x.getMessage().startsWith(UNAUTHORIZED_TENANT)) {
                return true;
            }
        }
        return false;
    }

    /** Any 404 response to a tenant identity (also one built without an exception) gets the uniform body. */
    public static final class TenantResponses implements ContainerResponseFilter {

        @Context
        HttpServletRequest request;

        @Override
        public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
            if (res.getStatus() == 404 && tenantIdentity(request)) {
                res.getHeaders().putSingle(HttpHeaders.CONTENT_TYPE, Problems.MEDIA_TYPE);
                res.setEntity(Problems.bytes(PROBLEM, null), null, MediaType.valueOf(Problems.MEDIA_TYPE));
            }
        }
    }
}
