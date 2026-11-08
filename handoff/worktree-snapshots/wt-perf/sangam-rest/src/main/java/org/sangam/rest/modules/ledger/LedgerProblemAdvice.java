// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.ledger;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.core.layer.Jdbc;
import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * problem+json for {@link LedgerController}. The ledger signals business refusals (undefined ledger, frozen account,
 * insufficient availability, invalid stage) as {@link IllegalArgumentException} / {@link IllegalStateException}:
 * those become 422 {@code LEDGER_REJECTED}; database failures stay 500 without detail.
 */
@RestControllerAdvice(assignableTypes = LedgerController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LedgerProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> rejected(RuntimeException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("LEDGER_REJECTED", e.getMessage()), req);
    }

    @ExceptionHandler(Jdbc.LayerSqlException.class)
    public ResponseEntity<Map<String, Object>> database(Jdbc.LayerSqlException e, HttpServletRequest req) {
        return ModuleProblems.respond(new ApiProblem(500, "LEDGER_STORE_ERROR",
                "the ledger store failed; see the server log for the trace id"), req);
    }
}
