// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * Regression: the PostgreSQL driver sends the JVM zone, and images without tzdata "backward" links reject legacy ids
 * (Windows JDK "India Standard Time" = Asia/Calcutta). The normalizer must be registered with Spring Boot so it runs
 * before any DataSource for the application and for every Spring Boot test.
 */
class LegacyTimeZoneEnvironmentPostProcessorTest {

    @Test
    void isRegisteredInSpringFactories() {
        // instantiate only ours: other post-processors need Boot-supplied constructor arguments
        assertTrue(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(EnvironmentPostProcessor.class, null, SpringFactoriesLoader.FailureHandler.handleMessage(
                        (msg, ex) -> { }))
                .stream().anyMatch(p -> p instanceof LegacyTimeZoneEnvironmentPostProcessor));
    }

    @Test
    void normalizesALegacyJvmZone() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Calcutta"));
            new LegacyTimeZoneEnvironmentPostProcessor().postProcessEnvironment(new StandardEnvironment(), null);
            assertEquals("Asia/Kolkata", TimeZone.getDefault().getID());
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
