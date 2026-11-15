// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Decision 334: {@value SangamDefaultsEnvironmentPostProcessor#SOURCE_NAME} is the lowest-precedence property source of
 * a host, below Spring Boot's {@code defaultProperties} as well.
 *
 * <p>Why a second step: {@code SpringApplication.prepareEnvironment} runs every environment post-processor (which adds
 * the source last, {@link SangamDefaultsEnvironmentPostProcessor}) and only then moves {@code applicationInfo} and
 * {@code defaultProperties} to the end ({@code ApplicationInfoPropertySource.moveToEnd},
 * {@code DefaultPropertiesPropertySource.moveToEnd}, Spring Boot 4.1). A host setting given through
 * {@code SpringApplicationBuilder.properties} or {@code setDefaultProperties} then sat above Sangam's default and lost
 * to it. The initializers of the application run right after that, in {@code prepareContext}, before any bean is
 * created; this one, first among them, puts the source back at the end.
 *
 * <p>The management child context (a separate management port) takes the parent's sources in the parent's order
 * ({@code AbstractApplicationContext.setParent}, {@code StandardEnvironment.merge}), so the order holds there too.
 * Sources Spring adds while the context refreshes are not host settings and may come after it, for example the
 * {@code Management Server} source with only the {@code local.management.port} alias
 * ({@code ManagementContextAutoConfiguration} when management shares the server port). Without the source (the
 * standalone server, or {@code sangam.starter.defaults.enabled=false}) nothing changes.
 */
public class SangamDefaultsOrder implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        moveToEnd(context.getEnvironment());
    }

    /** Moves {@value SangamDefaultsEnvironmentPostProcessor#SOURCE_NAME} to the end, when present. */
    static void moveToEnd(ConfigurableEnvironment environment) {
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> defaults = sources.remove(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME);
        if (defaults != null) {
            sources.addLast(defaults);
        }
    }
}
