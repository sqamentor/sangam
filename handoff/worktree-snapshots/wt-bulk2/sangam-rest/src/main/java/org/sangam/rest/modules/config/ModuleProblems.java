// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.config;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.web.Problems;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * problem+json responses for the module controllers under {@code org.sangam.rest.modules}. The shared
 * {@code ProblemHandler} covers only {@code org.sangam.rest.web}, so each module advice maps {@link ApiProblem} and
 * plain argument errors as well as its own exceptions.
 */
public final class ModuleProblems {

    private ModuleProblems() {
    }

    public static ResponseEntity<Map<String, Object>> respond(ApiProblem p, HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        if (p.retryAfterSeconds() != null) {
            h.set(HttpHeaders.RETRY_AFTER, Integer.toString(p.retryAfterSeconds()));
        }
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }

    /** Status for a module error code: not found 404; state / precondition conflicts 409; everything else 422. */
    public static int statusOf(String code) {
        if (code == null) {
            return 422;
        }
        return switch (code) {
            case "NOT_FOUND", "TYPE_UNKNOWN", "SCHEME_UNKNOWN" -> 404;
            case "VERSION_FROZEN", "STATE_INVALID", "APPROVAL_INVALIDATED", "DUPLICATE", "CONFLICT" -> 409;
            default -> 422;
        };
    }
}
