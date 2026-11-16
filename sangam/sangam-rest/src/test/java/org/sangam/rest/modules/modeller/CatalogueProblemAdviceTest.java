// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.rest.port.ApiProblem;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The catalogue-scoped advice maps the two catalogue controllers' own refusals and nothing else: no plain argument or
 * state error is remapped (that would change their answers), and no other controller is in its scope; neither
 * controller keeps a local handler that could answer differently.
 */
class CatalogueProblemAdviceTest {

    @Test
    void theAdviceCoversBothCatalogueControllersAndOnlyTheirOwnRefusals() {
        RestControllerAdvice a = CatalogueProblemAdvice.class.getAnnotation(RestControllerAdvice.class);
        assertEquals(Set.of(ExtensionCatalogueController.class, CatalogueHostController.class),
                Set.copyOf(Arrays.asList(a.assignableTypes())));
        assertEquals(0, a.basePackages().length + a.basePackageClasses().length + a.value().length + a.annotations().length);
        Set<Class<?>> handled = Arrays.stream(CatalogueProblemAdvice.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .flatMap(m -> Arrays.stream(m.getAnnotation(ExceptionHandler.class).value()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(ApiProblem.class, ExtensionCatalogue.CatalogueException.class), handled);
        for (Class<?> c : List.of(ExtensionCatalogueController.class, CatalogueHostController.class)) {
            assertEquals(List.of(), Arrays.stream(c.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(ExceptionHandler.class)).map(m -> m.getName()).toList(),
                    c.getSimpleName() + " answers through CatalogueProblemAdvice only");
        }
    }
}
