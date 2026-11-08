// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ops;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.ops.bulk.BulkIngest;
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
 * problem+json for the bulk routes. The core {@code ProblemHandler} is scoped to {@code org.sangam.rest.web}, so a module
 * controller needs its own advice: without it an {@link ApiProblem} (404, 413, 429 ...) escapes to the servlet error
 * page and the caller sees an unrelated 401 from {@code /error}.
 */
@RestControllerAdvice(assignableTypes = BulkController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BulkProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> problem(ApiProblem p, HttpServletRequest req) {
        return send(p, req);
    }

    @ExceptionHandler(BulkIngest.BulkException.class)
    public ResponseEntity<Map<String, Object>> bulk(BulkIngest.BulkException e, HttpServletRequest req) {
        return send(BulkController.problem(e), req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e, HttpServletRequest req) {
        return send(ApiProblem.badRequest("VALIDATION_FAILED", e.getMessage()), req);
    }

    private static ResponseEntity<Map<String, Object>> send(ApiProblem p, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        if (p.retryAfterSeconds() != null) {
            h.set(HttpHeaders.RETRY_AFTER, Integer.toString(p.retryAfterSeconds()));
        }
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
