// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Decision 165: the DSC purge wiring is off unless sangam.dsc.enabled=true, the same switch as the signing flow. */
class DscPurgeWiringTest {

    @Test
    void thePurgeIsOffUnlessDscIsEnabled() {
        ConditionalOnProperty c = DscPurgeWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(c);
        assertEquals(List.of("sangam.dsc.enabled"), List.of(c.name()));
        assertEquals("true", c.havingValue());
        assertFalse(c.matchIfMissing(), "off by default");
        ConditionalOnProperty signing = DscSigningWiring.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals(List.of(signing.name()), List.of(c.name()), "one switch for signing and its purge");
    }
}
