// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression for SG-33: a consumer parent pom managing jackson-databind 2.21.7 must fail fast with the fix. */
class JacksonCompatibilityGuardTest {

    @Test
    void refuses2217() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> JacksonCompatibilityGuard.check("2.21.7", "2.22.3"));
        assertTrue(e.getMessage().contains("2.22.x"), e.getMessage());
        assertTrue(e.getMessage().contains("org.sangam:sangam-bom"), e.getMessage());
        assertTrue(e.getMessage().contains("jackson-bom:2.22.3"), e.getMessage());
    }

    @Test
    void accepts2223() {
        assertDoesNotThrow(() -> JacksonCompatibilityGuard.check("2.22.3", "2.22.3"));
        assertDoesNotThrow(() -> JacksonCompatibilityGuard.check("2.22.0", null));
    }

    @Test
    void refuses2230AndUnknown() {
        assertThrows(IllegalStateException.class, () -> JacksonCompatibilityGuard.check("2.23.0", "2.22.3"));
        assertThrows(IllegalStateException.class, () -> JacksonCompatibilityGuard.check("2.22.3", "2.21.1"));
        assertThrows(IllegalStateException.class, () -> JacksonCompatibilityGuard.check(null, "2.22.3"));
        assertThrows(IllegalStateException.class, () -> JacksonCompatibilityGuard.check("unknown", null));
    }

    @Test
    void theBuildItselfPasses() {
        assertDoesNotThrow(() -> JacksonCompatibilityGuard.check());
    }
}
