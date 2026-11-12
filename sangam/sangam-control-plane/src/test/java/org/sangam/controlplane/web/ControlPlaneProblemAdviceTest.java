// SPDX-License-Identifier: Apache-2.0
package org.sangam.controlplane.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import tools.jackson.databind.exc.MismatchedInputException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** SL-71: the control plane answers an unreadable or unbindable body with a problem that names the field. */
class ControlPlaneProblemAdviceTest {

    static final HttpInputMessage BODY = new MockHttpInputMessage(new byte[0]);

    static ProblemDetail answer(HttpMessageNotReadableException e) {
        ResponseEntity<Object> r = new ControlPlaneProblemAdvice().handleHttpMessageNotReadable(e, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/cp/tenants")));
        assertEquals(400, r.getStatusCode().value());
        return (ProblemDetail) r.getBody();
    }

    @Test
    void anUnbindableValueNamesTheField() {
        MismatchedInputException m = MismatchedInputException.from(null, Integer.class, "missing");
        m.prependPath(new Object(), "capacity");
        m.prependPath(new Object(), "cell");
        ProblemDetail p = answer(new HttpMessageNotReadableException("bad", m, BODY));
        assertEquals("cell.capacity", p.getProperties().get("field"));
        assertEquals(400, p.getStatus());
    }

    @Test
    void aBodyThatIsNotJsonIsAProblemWithoutField() {
        ProblemDetail p = answer(new HttpMessageNotReadableException("not json", BODY));
        assertNull(p.getProperties() == null ? null : p.getProperties().get("field"));
        assertEquals("The request body is not valid JSON for this operation", p.getDetail());
    }
}
