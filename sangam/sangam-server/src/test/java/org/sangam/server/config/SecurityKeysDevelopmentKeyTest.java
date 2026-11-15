// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sangam.security.crypto.MasterKeyProvider;

/**
 * The ephemeral development key-encryption key is one per JVM. Regression: every application context generated its
 * own, while the h2 profile's in-memory database outlives a context; the second context in a JVM then failed with
 * "key unwrap failed ... (wrong KEK or tampered row)" on the first tenant data key the first context had created.
 */
class SecurityKeysDevelopmentKeyTest {

    @Test
    void everyContextOfOneJvmGetsTheSameDevelopmentKey() {
        MasterKeyProvider first = SecurityKeysWiring.developmentKey();
        MasterKeyProvider second = SecurityKeysWiring.developmentKey();
        assertSame(first, second);
        assertTrue(first.ephemeral(), "still the development key: production refuses it");
        assertArrayEquals(first.current().getEncoded(), second.byId(first.currentId()).orElseThrow().getEncoded(),
                "what one context wrapped, the next one unwraps");
    }
}
