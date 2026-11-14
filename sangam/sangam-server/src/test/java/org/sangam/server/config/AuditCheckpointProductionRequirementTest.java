// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Decision 126 part 1 (C-11, B-05): signed audit checkpoints are required for every tenant in production. Without
 * {@code sangam.audit.checkpoint.private-key-ref} the production start is refused (no waiver exists); with it, the
 * checkpoint wiring is on and its scheduler covers every tenant with audit events. Outside production the key stays
 * optional (owner Q-04). Written for the test phase (not run here).
 */
class AuditCheckpointProductionRequirementTest {

    static MockEnvironment helmProduction() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    @Test
    void productionWithoutTheCheckpointKeyIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AuditCheckpointWiring.requireInProduction(helmProduction()));
        assertTrue(e.getMessage().contains("decision 126") && e.getMessage().contains("private-key-ref"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.requireInProduction(
                helmProduction().withProperty("sangam.audit.checkpoint.private-key-ref", " ")));
        MockEnvironment prodProfile = new MockEnvironment();
        prodProfile.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> AuditCheckpointWiring.requireInProduction(prodProfile));
    }

    @Test
    void productionWithTheKeyStarts() {
        assertDoesNotThrow(() -> AuditCheckpointWiring.requireInProduction(helmProduction()
                .withProperty("sangam.audit.checkpoint.private-key-ref", "secret://audit/checkpoint-private")
                .withProperty("sangam.audit.checkpoint.public-key-ref", "secret://audit/checkpoint-public")));
    }

    @Test
    void developmentDoesNotNeedTheKey() {
        MockEnvironment dev = new MockEnvironment().withProperty("sangam.security.production", "false");
        dev.setActiveProfiles("h2");
        assertDoesNotThrow(() -> AuditCheckpointWiring.requireInProduction(dev));
    }
}
