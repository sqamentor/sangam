// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.connectorshealth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** problem+json for {@link HealthHooksController}. */
@RestControllerAdvice(assignableTypes = HealthHooksController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HealthHooksProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> rejected(IllegalArgumentException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("NOTIFICATION_REJECTED", e.getMessage()), req);
    }
}
