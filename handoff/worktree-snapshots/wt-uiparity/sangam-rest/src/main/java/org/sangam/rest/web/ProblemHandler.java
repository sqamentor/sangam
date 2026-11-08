// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.core.api.StaleLeaseException;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.OperationLedger;
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

/** Maps every error to RFC 9457 {@code application/problem+json} (ARCHITECTURE §4). */
@RestControllerAdvice(basePackages = "org.sangam.rest.web")
public class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        if (p.status() >= 500) {
            LOG.warn("{} {} -> {} {}", req.getMethod(), req.getRequestURI(), p.status(), p.code());
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
        return respond(ApiProblem.badRequest("MALFORMED_BODY", "The request body is not valid JSON for this operation"), req);
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest req) {
        LOG.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
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
