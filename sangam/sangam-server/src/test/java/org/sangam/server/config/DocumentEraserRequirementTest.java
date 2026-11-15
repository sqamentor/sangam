// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sangam.integration.lifecycle.LifecyclePorts;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decisions 143 and 152 for data erasure: the erasure service of the server never runs with the no-op document eraser
 * outside a validated development environment. Before, {@code IntegrationWiring.sangamErasureService} fell back to
 * {@code DocumentEraser.NONE} in every environment (and no eraser bean existed), so an erasure reported success while
 * the instance's documents stayed in the document store.
 */
class DocumentEraserRequirementTest {

    private static MockEnvironment development() {
        // decision 143: a development installation states the switch; without it the environment is production
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
        return env;
    }

    @Test
    void productionWithoutADocumentEraserRefusesTheStartByName() {
        MockEnvironment helm = new MockEnvironment().withProperty("sangam.security.production", "true");
        helm.setActiveProfiles("postgres", "oidc");
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("postgres", "prod");
        MockEnvironment unset = new MockEnvironment();
        MockEnvironment contradictory = new MockEnvironment().withProperty("sangam.security.production", "true");
        contradictory.setActiveProfiles("h2");
        for (MockEnvironment env : List.of(helm, prodProfile, unset, contradictory)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> DocumentEraserRequirement.require(null, env));
            assertTrue(refused.getMessage().startsWith(DocumentEraserRequirement.REFUSAL), refused.getMessage());
        }
        assertEquals("DOCUMENT_ERASER_REQUIRED", DocumentEraserRequirement.REFUSAL);
    }

    @Test
    void aConfiguredEraserIsUsedInEveryEnvironment() {
        LifecyclePorts.DocumentEraser eraser = (tenant, instances, reason) -> 7;
        MockEnvironment helm = new MockEnvironment().withProperty("sangam.security.production", "true");
        helm.setActiveProfiles("postgres", "oidc");
        assertSame(eraser, DocumentEraserRequirement.require(eraser, helm));
        assertSame(eraser, DocumentEraserRequirement.require(eraser, development()));
    }

    @Test
    void onlyDevelopmentMayRunWithoutOne() {
        assertSame(LifecyclePorts.DocumentEraser.NONE, DocumentEraserRequirement.require(null, development()));
    }
}
