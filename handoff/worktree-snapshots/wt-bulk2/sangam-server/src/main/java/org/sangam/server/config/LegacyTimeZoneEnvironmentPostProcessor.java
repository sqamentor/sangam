// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.layer.LegacyTimeZones;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Normalizes a legacy JVM default time zone ({@link LegacyTimeZones}) before any bean, so the first JDBC connection
 * already sends the canonical id. Registered in {@code META-INF/spring.factories}; runs for the application and for
 * Spring Boot tests alike.
 */
public class LegacyTimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        LegacyTimeZones.normalizeJvmDefault();
    }
}
