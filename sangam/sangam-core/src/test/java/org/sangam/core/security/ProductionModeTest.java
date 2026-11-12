// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-109: the one production predicate. */
class ProductionModeTest {

    @Test
    void thePropertyAloneIsProductionAsHelmSetsIt() {
        assertTrue(ProductionMode.isProduction(true, List.of("postgres", "oidc")));
        assertTrue(ProductionMode.isProduction(true, null));
    }

    @Test
    void aProdProfileAloneIsStillProduction() {
        assertTrue(ProductionMode.isProduction(false, List.of("postgres", "prod")));
        assertTrue(ProductionMode.isProduction(false, List.of("PROD-eu")));
        assertTrue(ProductionMode.isProductionProfile("h2,production"));
    }

    @Test
    void developmentIsNotProduction() {
        assertFalse(ProductionMode.isProduction(false, List.of("h2", "dev")));
        assertFalse(ProductionMode.isProduction(false, List.of()));
        assertFalse(ProductionMode.isProduction(false, null));
        assertFalse(ProductionMode.isProductionProfile((String) null));
        assertFalse(ProductionMode.isProductionProfile(Arrays.asList("h2", null)));
    }

    @Test
    void thePropertyNameIsTheOneHelmSets() {
        // Helm: SANGAM_SECURITY_PRODUCTION (relaxed binding) -> sangam.security.production
        assertTrue(ProductionMode.PROPERTY.equals("sangam.security.production"));
    }
}
