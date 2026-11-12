// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import jakarta.servlet.RequestDispatcher;

import tools.jackson.databind.exc.MismatchedInputException;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * SL-71: every error that reaches the container's error dispatch (a controller family without advice, such as the ops
 * module's {@code OpsController} / {@code OpsUiController}, or an {@code /engine-rest} error dispatch) answers RFC 9457
 * {@code application/problem+json} with the same code {@code /api} gives, instead of Spring Boot's default error JSON.
 */
class ProblemErrorControllerTest {

    static ResponseEntity<Map<String, Object>> dispatch(Throwable e, int status, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/error");
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        return new ProblemErrorController(r -> e).error(req);
    }

    static void problem(ResponseEntity<Map<String, Object>> r, int status, String code) {
        assertEquals(status, r.getStatusCode().value());
        assertEquals("application/problem+json", String.valueOf(r.getHeaders().getContentType()));
        assertEquals(code, r.getBody().get("code"), String.valueOf(r.getBody()));
    }

    @Test
    void anApiProblemOfAModuleControllerWithoutAdviceKeepsItsStatusAndCode() {
        problem(dispatch(new ApiProblem(409, "STALE_HASH", "stale"), 500, "/api/v1/ops/console/x"), 409, "STALE_HASH");
    }

    @Test
    void anUnbindableBodyNamesTheFieldAsOnApi() {
        MismatchedInputException m = MismatchedInputException.from(null, Integer.class, "missing");
        m.prependPath(new Object(), "limit");
        ResponseEntity<Map<String, Object>> r = dispatch(new HttpMessageNotReadableException("bad", m,
                new MockHttpInputMessage(new byte[0])), 400, "/api/v1/ops/ui/search");
        problem(r, 400, "VALIDATION_FAILED");
        assertEquals("limit", r.getBody().get("field"));
    }

    @Test
    void aStatusWithoutExceptionIsAProblemOfThatStatus() {
        ResponseEntity<Map<String, Object>> r = dispatch(null, 404, "/engine-rest/nothing-here");
        problem(r, 404, "NOT_FOUND");
        assertEquals("/engine-rest/nothing-here", r.getBody().get("instance"));
    }

    @Test
    void anUnexpectedErrorIs500WithoutItsMessage() {
        ResponseEntity<Map<String, Object>> r = dispatch(new IllegalStateException("secret internals"), 500, "/api/v1/x");
        problem(r, 500, "INTERNAL_ERROR");
        assertFalse(String.valueOf(r.getBody()).contains("secret internals"), String.valueOf(r.getBody()));
    }

    @Test
    void aStatusTheContainerDecidedIsKeptForAnExceptionWithoutItsOwnMapping() {
        problem(dispatch(new ResponseStatusException(HttpStatus.CONFLICT, "busy"), 409, "/api/v1/x"), 409, "HTTP_409");
    }
}
