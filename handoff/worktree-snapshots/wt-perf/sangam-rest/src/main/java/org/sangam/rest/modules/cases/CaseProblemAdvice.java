// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.cases;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.casefile.CaseFileException;
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

/** Maps {@link CaseFileException} ({@code status()/code()}) to RFC 9457 problem+json. */
@RestControllerAdvice(assignableTypes = {CaseFileController.class, MeetingController.class, ReferenceController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CaseProblemAdvice {

    @ExceptionHandler(CaseFileException.class)
    public ResponseEntity<Map<String, Object>> caseFile(CaseFileException e, HttpServletRequest req) {
        ApiProblem p = new ApiProblem(e.status(), e.code().name(), e.getMessage());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        return new ResponseEntity<>(Problems.body(p, req.getRequestURI()), h, HttpStatus.valueOf(p.status()));
    }
}
