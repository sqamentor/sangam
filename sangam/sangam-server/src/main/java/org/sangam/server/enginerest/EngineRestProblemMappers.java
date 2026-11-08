// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.enginerest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;

import org.glassfish.jersey.server.ResourceConfig;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.rest.exception.ExceptionHandler;
import org.operaton.bpm.engine.rest.exception.ProcessEngineExceptionHandler;
import org.operaton.bpm.engine.rest.exception.RestException;
import org.operaton.bpm.engine.rest.exception.RestExceptionHandler;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.ProblemHandler;
import org.sangam.rest.web.Problems;

/**
 * Sangam refusals on the Operaton REST API ({@code /engine-rest/**}). Operaton's JAX-RS mappers turn every exception
 * that is not one of its own into {@code 500}, so a tenant quota refusal (SL-43, thrown by
 * {@code TenantQuotaEnginePlugin} inside the engine command) or a tenant isolation refusal (SL-13 / SL-45) answered
 * 500 on {@code /engine-rest} while {@code /api} answered 429/413/403. These mappers take the three exception types
 * Operaton maps ({@link Throwable}, {@link ProcessEngineException}, {@link RestException}), look for a Sangam refusal
 * anywhere in the cause chain ({@link ProblemHandler#tenantRefusal}, the same source {@code /api} uses) and answer
 * exactly like {@code /api}: {@code application/problem+json}, the stable code, {@code Retry-After} for rate limits.
 * Every other exception is handed to Operaton's own mapper of the same type, so engine-rest behaviour is unchanged.
 *
 * <p>Jersey picks the mapper of the nearest exception type and, between two of the same type, the one with the
 * smaller priority value; these are registered with {@link #PRIORITY} (below Operaton's default
 * {@link Priorities#USER}), so they win deterministically, whatever the registration order.
 */
public final class EngineRestProblemMappers {

    /** Smaller than Operaton's default {@link Priorities#USER}: preferred over its mapper of the same type. */
    public static final int PRIORITY = Priorities.USER - 1000;

    private EngineRestProblemMappers() {
    }

    /** Registers the mappers on the engine-rest Jersey application. */
    public static void register(ResourceConfig config) {
        config.register(Any.class, PRIORITY);
        config.register(Engine.class, PRIORITY);
        config.register(Rest.class, PRIORITY);
    }

    static Response problem(ApiProblem p, UriInfo uri) {
        Response.ResponseBuilder b = Response.status(p.status()).type(Problems.MEDIA_TYPE)
                .entity(Problems.bytes(p, uri == null ? null : uri.getRequestUri().getPath()));
        if (p.retryAfterSeconds() != null) {
            b.header(HttpHeaders.RETRY_AFTER, Integer.toString(p.retryAfterSeconds()));
        }
        return b.build();
    }

    /** Everything Operaton maps with its generic {@link ExceptionHandler} (a quota exception thrown as is). */
    @Priority(PRIORITY)
    public static final class Any implements ExceptionMapper<Throwable> {

        private final ExceptionHandler operaton = new ExceptionHandler();

        @Context
        UriInfo uri;

        @Override
        public Response toResponse(Throwable t) {
            ApiProblem p = ProblemHandler.tenantRefusal(t);
            return p != null ? problem(p, uri) : operaton.toResponse(t);
        }
    }

    /** Engine exceptions (an isolation refusal, or a quota exception wrapped by the engine). */
    @Priority(PRIORITY)
    public static final class Engine implements ExceptionMapper<ProcessEngineException> {

        private final ProcessEngineExceptionHandler operaton = new ProcessEngineExceptionHandler();

        @Context
        UriInfo uri;

        @Override
        public Response toResponse(ProcessEngineException t) {
            ApiProblem p = ProblemHandler.tenantRefusal(t);
            return p != null ? problem(p, uri) : operaton.toResponse(t);
        }
    }

    /** Operaton REST exceptions (which wrap engine exceptions in some resources). */
    @Priority(PRIORITY)
    public static final class Rest implements ExceptionMapper<RestException> {

        private final RestExceptionHandler operaton = new RestExceptionHandler();

        @Context
        UriInfo uri;

        @Override
        public Response toResponse(RestException t) {
            ApiProblem p = ProblemHandler.tenantRefusal(t);
            return p != null ? problem(p, uri) : operaton.toResponse(t);
        }
    }
}
