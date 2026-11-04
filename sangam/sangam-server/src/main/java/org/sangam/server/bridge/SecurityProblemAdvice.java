// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.core.security.SangamSecurityException;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps sangam-security's {@link SangamSecurityException} (and its subclasses: {@code AccessDenied},
 * {@code SodViolation} 422, {@code PowerLimitExceeded} 422, {@code ApprovalInvalidated} 409, ...) to RFC 9457
 * problem+json with the exception's own status and code (sangam-security README note 9). Ordered before sangam-rest's
 * {@code ProblemHandler}, whose catch-all would otherwise answer 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityProblemAdvice {

    private static final MediaType PROBLEM = MediaType.parseMediaType(Problems.MEDIA_TYPE);

    @ExceptionHandler(SangamSecurityException.class)
    public ResponseEntity<Map<String, Object>> security(SangamSecurityException e, HttpServletRequest req) {
        ApiProblem p = new ApiProblem(e.httpStatus(), e.code(), e.getMessage());
        if (e instanceof SangamSecurityException.AccessDenied denied) {
            p = p.with("policyVersion", denied.policyVersion());
        }
        return ResponseEntity.status(HttpStatus.valueOf(e.httpStatus())).contentType(PROBLEM)
                .body(Problems.body(p, req.getRequestURI()));
    }
}
