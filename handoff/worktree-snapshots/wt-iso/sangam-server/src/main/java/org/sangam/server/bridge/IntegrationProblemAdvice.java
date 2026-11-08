// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.integration.lifecycle.ErasureService;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 mapping for sangam-integration exceptions that reach a controller: {@code 503 BACKPRESSURE} with
 * {@code Retry-After} (VI.2.4) and {@code 409 LEGAL_HOLD} for an erasure under legal hold (R61, X.2.3.8).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IntegrationProblemAdvice {

    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);

    @ExceptionHandler(BackpressureGate.BackpressureException.class)
    public ResponseEntity<Map<String, Object>> backpressure(BackpressureGate.BackpressureException e,
                                                            HttpServletRequest req) {
        int retry = (int) Math.max(1, e.retryAfter() == null ? 5 : e.retryAfter().toSeconds());
        ApiProblem p = new ApiProblem(e.httpStatus(), e.code(), null, e.getMessage(), Map.of("level", e.level().name()),
                retry);
        return ResponseEntity.status(HttpStatus.valueOf(e.httpStatus())).contentType(PROBLEM)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(retry)).body(Problems.body(p, req.getRequestURI()));
    }

    @ExceptionHandler(ErasureService.LegalHoldException.class)
    public ResponseEntity<Map<String, Object>> legalHold(ErasureService.LegalHoldException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(PROBLEM)
                .body(Problems.body(new ApiProblem(409, "LEGAL_HOLD", e.getMessage()), req.getRequestURI()));
    }
}
