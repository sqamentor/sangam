// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * problem+json for the Flowable worker compatibility endpoint. sangam-rest's {@code ProblemHandler} covers only
 * {@code org.sangam.rest.web}; without this advice every {@link ApiProblem} of {@link FlowableWorkerCompatController}
 * (409 FENCING_TOKEN_REQUIRED / STALE_LEASE, 422 INVALID_INPUT / RESERVED_VARIABLE) escaped to the servlet error page,
 * which the security chain denies, so clients saw 403 ACTION_NOT_PERMITTED instead (FWT-009..012, 031).
 */
@RestControllerAdvice(assignableTypes = FlowableWorkerCompatController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FlowableWorkerCompatProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }
}
