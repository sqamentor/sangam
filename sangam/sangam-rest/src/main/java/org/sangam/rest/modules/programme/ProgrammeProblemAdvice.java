// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.programme;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.programme.ProgrammeException;
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

/** Maps {@link ProgrammeException} ({@code status()/code()}) to RFC 9457 problem+json ({@code CONFLICT} → 409). */
@RestControllerAdvice(assignableTypes = ProgrammeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProgrammeProblemAdvice {

    @ExceptionHandler(ProgrammeException.class)
    public ResponseEntity<Map<String, Object>> programme(ProgrammeException e, HttpServletRequest req) {
        ApiProblem p = new ApiProblem(e.status(), e.code().name(), e.getMessage());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
