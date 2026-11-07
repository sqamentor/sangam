// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.modeller.lease.EditLeases;
import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** problem+json for {@link ModellerController}: a lost lease is 409, a bad catalogue or refused input 422. */
@RestControllerAdvice(assignableTypes = {ModellerController.class, ServiceMocksController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ModellerProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler(EditLeases.StaleEditor.class)
    public ResponseEntity<Map<String, Object>> stale(EditLeases.StaleEditor e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.conflict("EDIT_LEASE_LOST", "The edit lease is no longer held"), req);
    }

    @ExceptionHandler({ExtensionCatalogue.CatalogueException.class, IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> rejected(RuntimeException e, HttpServletRequest req) {
        return ModuleProblems.respond(ApiProblem.unprocessable("MODELLER_REJECTED", e.getMessage()), req);
    }
}
