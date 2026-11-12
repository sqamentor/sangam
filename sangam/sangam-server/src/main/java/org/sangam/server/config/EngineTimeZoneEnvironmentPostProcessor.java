// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.core.time.EngineTimeZone;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * SL-53: the server refuses to start unless the JVM default time zone is UTC ({@link EngineTimeZone}), before any
 * bean, so no DataSource, Liquibase run or module ever writes a timestamp from a JVM in another zone. The engine
 * bootstrap ({@code SangamEngineConfigurer}) applies the same rule to every other embedding. Registered in
 * {@code META-INF/spring.factories}; runs for the application and for Spring Boot tests alike. The zone is never
 * changed here, and there is no property to skip the check.
 */
public class EngineTimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        EngineTimeZone.requireUtcJvm();
    }
}
