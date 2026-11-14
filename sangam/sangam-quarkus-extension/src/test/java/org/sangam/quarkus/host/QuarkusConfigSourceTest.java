// SPDX-License-Identifier: Apache-2.0
package org.sangam.quarkus.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

import org.junit.jupiter.api.Test;
import org.sangam.assembly.host.SangamProperties;

/**
 * The config-source seam reads the MicroProfile configuration Quarkus builds (SmallRye Config here, without booting
 * Quarkus): present keys, absent keys, Quarkus' ordinal order, and the typed parsing of {@link SangamProperties}.
 * Written for the test phase (not run).
 */
class QuarkusConfigSourceTest {

    @Test
    void readsTheHostConfigurationInItsOwnOrder() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("sangam.modules.security.enabled", "false",
                        "sangam.shutdown.drain", "PT5S", "a.b", "low"), "low", 100))
                .withSources(new PropertiesConfigSource(Map.of("a.b", "high"), "high", 200))
                .build();
        SangamProperties p = QuarkusConfigSource.over(config);
        assertFalse(p.getBoolean("sangam.modules.security.enabled", true));
        assertEquals(Duration.ofSeconds(5), p.getDuration("sangam.shutdown.drain", Duration.ofSeconds(30)));
        assertEquals("high", p.get("a.b"), "the higher ordinal wins, as in Quarkus");
        assertNull(p.get("not.there"));
        assertTrue(p.getBoolean("not.there", true));
    }

    @Test
    void anInvalidValueIsRefusedNotDefaulted() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(Map.of("sangam.modules.security.enabled", "maybe"), "s", 100))
                .build();
        SangamProperties p = QuarkusConfigSource.over(config);
        assertThrows(IllegalArgumentException.class, () -> p.getBoolean("sangam.modules.security.enabled", true));
    }
}
