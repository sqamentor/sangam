// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.web;

import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * SL-71: every Spring MVC error of the control plane is RFC 9457 {@code application/problem+json}
 * ({@link ResponseEntityExceptionHandler}: unreadable body, missing parameter, wrong method or media type, ...). Before,
 * only {@code ControlPlaneController}'s own handlers (not found, illegal argument, illegal state) answered problems and an
 * unreadable body got Spring Boot's default 400 JSON. A body that is JSON but does not bind names the field
 * ({@code "field"}, as {@code /api} does since SL-81).
 */
@RestControllerAdvice(basePackages = "org.sangam.controlplane")
public class ControlPlaneProblemAdvice extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "The request body is not valid JSON for this operation");
        if (ex.getCause() instanceof DatabindException d && !d.getPath().isEmpty()) {
            String field = fieldPath(d.getPath());
            body.setDetail(field + " is missing, null or not valid for this operation");
            body.setProperty("field", field);
        }
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
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
}
