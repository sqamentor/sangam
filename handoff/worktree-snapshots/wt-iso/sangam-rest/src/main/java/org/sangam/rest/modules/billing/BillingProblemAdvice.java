// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.billing;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** problem+json for {@link BillingController}: connector failures arrive as {@link ApiProblem}; refusals 422. */
@RestControllerAdvice(assignableTypes = BillingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BillingProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> rejected(RuntimeException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("BILLING_REJECTED", e.getMessage()), req);
    }
}
