// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.registry;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

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
 * RFC 9457 mapping of sangam-rest's {@link ApiProblem} (permission interceptor, R41 problems) for the registry module's
 * controllers; the module's own {@code RegistryExceptionHandler} keeps mapping {@code RegistryException}.
 */
@RestControllerAdvice(basePackages = "org.sangam.registry.rest")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistryApiProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> problem(ApiProblem p, HttpServletRequest request) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        if (p.retryAfterSeconds() != null) {
            h.set(HttpHeaders.RETRY_AFTER, Integer.toString(p.retryAfterSeconds()));
        }
        return new ResponseEntity<>(Problems.body(p, request.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
