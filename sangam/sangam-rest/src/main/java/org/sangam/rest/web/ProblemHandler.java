// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;

import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.OperationLedger;
import org.sangam.core.quota.TenantQuotaExceededException;
import org.sangam.engine.deploy.TenantIsolationException;
import org.sangam.rest.port.ApiProblem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.sangam.core.security.LogSafe;

/** Maps every error to RFC 9457 {@code application/problem+json} (ARCHITECTURE §4). */
@RestControllerAdvice(basePackages = "org.sangam.rest.web")
public class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        if (p.status() >= 500) {
            LOG.warn("{} {} -> {} {}", LogSafe.text(req.getMethod()), LogSafe.text(req.getRequestURI()), p.status(),
                    LogSafe.text(p.code()));
        }
        return respond(p, req);
    }

    @ExceptionHandler(StaleLeaseException.class)
    public ResponseEntity<Map<String, Object>> staleLease(StaleLeaseException e, HttpServletRequest req) {
        return respond(ApiProblem.staleLease(e.workId(), e.reason().name(), e.getMessage()), req);
    }

    @ExceptionHandler(OperationLedger.StaleLeaseException.class)
    public ResponseEntity<Map<String, Object>> ledgerStale(OperationLedger.StaleLeaseException e, HttpServletRequest req) {
        return respond(new ApiProblem(409, "STALE_LEASE", e.getMessage()), req);
    }

    @ExceptionHandler(IdempotencyStore.ClaimLostException.class)
    public ResponseEntity<Map<String, Object>> claimLost(IdempotencyStore.ClaimLostException e, HttpServletRequest req) {
        return respond(new ApiProblem(409, "IDEMPOTENCY_IN_PROGRESS", null, e.getMessage(), null, 1), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        // SL-81: well-formed JSON whose value does not bind (a required number or flag missing or null, a wrong type)
        // names the field: 400 VALIDATION_FAILED with "field". Only a body that is not JSON stays MALFORMED_BODY.
        if (e.getCause() instanceof DatabindException d && !d.getPath().isEmpty()) {
            String field = fieldPath(d.getPath());
            String detail = d instanceof MismatchedInputException m && m.getTargetType() != null
                    ? field + " is missing, null or not a valid " + m.getTargetType().getSimpleName()
                    : field + " is not valid for this operation";
            return respond(ApiProblem.badRequest("VALIDATION_FAILED", detail).with("field", field), req);
        }
        return respond(ApiProblem.badRequest("MALFORMED_BODY", "The request body is not valid JSON for this operation"), req);
    }

    /** {@code lines[2].amount} from Jackson's reference chain. */
    static String fieldPath(List<JacksonException.Reference> path) {
        StringBuilder b = new StringBuilder();
        for (JacksonException.Reference r : path) {
            if (r.getPropertyName() != null) {
                b.append(b.isEmpty() ? "" : ".").append(r.getPropertyName());
            } else if (r.getIndex() >= 0) {
                b.append('[').append(r.getIndex()).append(']');
            }
        }
        return b.toString();
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingRequestHeaderException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badParam(Exception e, HttpServletRequest req) {
        return respond(ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage()), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        return respond(ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage()), req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return respond(new ApiProblem(413, "PAYLOAD_TOO_LARGE", e.getMessage()), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return respond(new ApiProblem(405, "METHOD_NOT_ALLOWED", e.getMessage()), req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return respond(new ApiProblem(415, "UNSUPPORTED_MEDIA_TYPE", e.getMessage()), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return respond(new ApiProblem(404, "NOT_FOUND", "No such endpoint"), req);
    }

    /** SL-43: 429 (rate, with Retry-After) or 413 (size) {@code TENANT_QUOTA_EXCEEDED}, naming the limit. */
    @ExceptionHandler(TenantQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> quota(TenantQuotaExceededException e, HttpServletRequest req) {
        return respond(quotaProblem(e), req);
    }

    public static ApiProblem quotaProblem(TenantQuotaExceededException e) {
        String detail = e.limit().isRate()
                ? "Tenant " + e.tenantId() + " is over its " + e.limit().key() + " quota (" + e.allowed() + "); retry after "
                + e.retryAfterSeconds() + " s"
                : "Tenant " + e.tenantId() + " is over its " + e.limit().key() + " quota (" + e.allowed() + " allowed, "
                + e.requested() + " requested)";
        return new ApiProblem(e.httpStatus(), TenantQuotaExceededException.CODE, null, detail, e.details(),
                e.retryAfterSeconds());
    }

    /**
     * SL-13 / SL-45: 403 {@code TENANT_ISOLATION_VIOLATION}, naming the element and both tenants. A member without a
     * value is left out ({@code targetTenant} of a refused shared definition, which has no tenant): {@link ApiProblem}
     * copies its extensions with {@code Map.copyOf}, which refuses a null value, so a null here made the problem itself
     * fail and the refusal answered 500 (on {@code /engine-rest}: Jersey's "exception mapper failure").
     */
    public static ApiProblem isolationProblem(TenantIsolationException e) {
        Map<String, Object> ext = new java.util.LinkedHashMap<>();
        ext.put("element", e.element());
        ext.put("tenant", e.callerTenant());
        ext.put("targetTenant", e.targetTenant());
        ext.values().removeIf(java.util.Objects::isNull);
        return new ApiProblem(TenantIsolationException.HTTP_STATUS, TenantIsolationException.CODE, null, e.getMessage(),
                ext, null);
    }

    /**
     * The Sangam problem for a refusal anywhere in a cause chain (engines, proxies and JAX-RS wrap exceptions), or null.
     * One source for {@code /api} and the {@code /engine-rest} JAX-RS mappers, so both answer identically.
     */
    public static ApiProblem tenantRefusal(Throwable t) {
        TenantQuotaExceededException quota = TenantQuotaExceededException.find(t);
        if (quota != null) {
            return quotaProblem(quota);
        }
        TenantIsolationException isolation = TenantIsolationException.find(t);
        return isolation == null ? null : isolationProblem(isolation);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        ApiProblem refusal = tenantRefusal(e);
        if (refusal != null) {
            return respond(refusal, req);   // wrapped by the engine or an adapter
        }
        LOG.error("Unhandled error on {} {}", LogSafe.text(req.getMethod()), LogSafe.text(req.getRequestURI()), e);
        return respond(new ApiProblem(500, "INTERNAL_ERROR", "An unexpected error occurred; see the server log for the trace id"), req);
    }

    private static ResponseEntity<Map<String, Object>> respond(ApiProblem p, HttpServletRequest req) {
        if ("STALE_LEASE".equals(p.code())) {
            io.micrometer.core.instrument.Metrics.counter("sangam.lease.stale").increment();
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(PROBLEM);
        if (p.retryAfterSeconds() != null) {
            h.set(HttpHeaders.RETRY_AFTER, Integer.toString(p.retryAfterSeconds()));
        }
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
