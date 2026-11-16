// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.rest.modules.config.ModuleProblems;
import org.sangam.rest.port.ApiProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * problem+json for the two catalogue controllers (owner decision 380): {@link ExtensionCatalogueController} (the
 * consumer side, list and install) and {@link CatalogueHostController} (the hosted catalogue, also its public routes).
 * Both answer their own {@link ApiProblem} and {@link ExtensionCatalogue.CatalogueException} refusals here, with the
 * same status and code mapping ({@link ExtensionCatalogueController#problem}) and the request's own path as
 * {@code instance}, instead of through the servlet error dispatch: that dispatch is kept for signed-in callers only
 * (FWT-D11), so an anonymous consumer of a public hosted route would get 401 for a 404, and a signed-in caller's
 * problem would name {@code /error} as its instance. Nothing else is mapped: a plain {@link IllegalArgumentException}
 * keeps the server's generic answer, as on every other route.
 */
@RestControllerAdvice(assignableTypes = {ExtensionCatalogueController.class, CatalogueHostController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogueProblemAdvice {

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Map<String, Object>> api(ApiProblem p, HttpServletRequest req) {
        return ModuleProblems.respond(p, req);
    }

    @ExceptionHandler(ExtensionCatalogue.CatalogueException.class)
    public ResponseEntity<Map<String, Object>> refused(ExtensionCatalogue.CatalogueException e, HttpServletRequest req) {
        return ModuleProblems.respond(ExtensionCatalogueController.problem(e), req);
    }
}
