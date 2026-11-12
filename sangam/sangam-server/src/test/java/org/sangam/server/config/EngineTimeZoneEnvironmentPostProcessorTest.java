// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * SL-53: the server refuses a JVM that is not in UTC before any bean (so before the first JDBC connection), for the
 * application and for every Spring Boot test; it never changes the zone itself.
 */
class EngineTimeZoneEnvironmentPostProcessorTest {

    @Test
    void isRegisteredInSpringFactories() {
        // instantiate only ours: other post-processors need Boot-supplied constructor arguments
        assertTrue(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(EnvironmentPostProcessor.class, null, SpringFactoriesLoader.FailureHandler.handleMessage(
                        (msg, ex) -> { }))
                .stream().anyMatch(p -> p instanceof EngineTimeZoneEnvironmentPostProcessor));
    }

    @Test
    void refusesANonUtcJvmAndAcceptsUtc() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
            IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                    new EngineTimeZoneEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), null));
            assertTrue(e.getMessage().contains("-Duser.timezone=UTC") && e.getMessage().contains("sangam.engine.timer-zone"),
                    e.getMessage());
            assertEquals("Europe/Berlin", TimeZone.getDefault().getID(), "refused, never overridden");
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertDoesNotThrow(() ->
                    new EngineTimeZoneEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), null));
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
