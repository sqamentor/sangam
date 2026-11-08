// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.config.common.ConfigException;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * problem+json for {@link ConfigController}: {@link ConfigException} by code (NOT_FOUND 404; VERSION_FROZEN,
 * STATE_INVALID, APPROVAL_INVALIDATED 409; PROFILE_INVALID, SOD_VIOLATION, ACTIVATION_REFUSED,
 * CLASSIFICATION_INVALID 422) with its findings, plus {@link ApiProblem} and argument errors.
 */
@RestControllerAdvice(assignableTypes = ConfigController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigProblemAdvice {

    @ExceptionHandler(ConfigException.class)
    public ResponseEntity<Map<String, Object>> config(ConfigException e, HttpServletRequest req) {
        ApiProblem p = new ApiProblem(ModuleProblems.statusOf(e.code()), e.code(), e.getMessage());
        if (e.findings() != null && !e.findings().isEmpty()) {
            p = p.with("findings", List.copyOf(e.findings()));
        }
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> invalid(RuntimeException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("CONFIG_INVALID", e.getMessage()), req);
    }
}
