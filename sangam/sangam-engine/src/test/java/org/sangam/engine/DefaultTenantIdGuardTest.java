// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SL-91: the default tenant id is the tenant key of untenanted engine rows (NOT NULL key columns). Only sangam-server's
 * EngineWiring guarded it; any other caller (or a blank {@code sangam.engine.default-tenant-id} bound through
 * {@code @ConfigurationProperties}) could set null or blank. The setter itself now refuses it.
 */
class DefaultTenantIdGuardTest {

    @Test
    void nullOrBlankIsRefusedAndTheDefaultIsKept() {
        SangamEngineSettings s = new SangamEngineSettings();
        for (String bad : new String[]{null, "", " ", "\t"}) {
            assertThrows(IllegalArgumentException.class, () -> s.setDefaultTenantId(bad), "refused: [" + bad + "]");
        }
        assertEquals("default", s.getDefaultTenantId(), "a refused value leaves the default untouched");
    }

    @Test
    void aRealTenantIdIsAccepted() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setDefaultTenantId("RJ");
        assertEquals("RJ", s.getDefaultTenantId());
    }
}
