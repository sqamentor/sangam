// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.server.config.LiquibaseMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Decision 334 through Spring Boot's real start-up sequence, without the product (an application with one empty
 * configuration class, so only the environment post-processors and initializers of the class path take part):
 * {@code sangamDefaults} is the lowest-precedence source, below {@code defaultProperties}; and the schema defaults
 * follow the environment the host states.
 */
class SangamDefaultsPrecedenceTest {

    @Configuration(proxyBeanMethods = false)
    static class Empty {
    }

    static ConfigurableApplicationContext run(Map<String, Object> defaultProperties, String[] profiles, String... args) {
        SpringApplication app = new SpringApplication(Empty.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(defaultProperties);
        app.setAdditionalProfiles(profiles);
        return app.run(args);
    }

    static List<String> names(ConfigurableEnvironment env) {
        return env.getPropertySources().stream().map(PropertySource::getName).toList();
    }

    @Test
    void aHostDefaultPropertyWinsOverSangamsDefault() {
        Map<String, Object> host = new HashMap<>();
        host.put("sangam.onboarding.environment", "host-default");
        host.put("sangam.security.production", "false");
        host.put("spring.config.location", "optional:classpath:/no-host-config/");
        try (ConfigurableApplicationContext ctx = run(host, new String[] {"dev"})) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            List<String> names = names(env);
            assertEquals(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME, names.get(names.size() - 1), names.toString());
            assertEquals("host-default", env.getProperty("sangam.onboarding.environment"),
                    "SpringApplication.setDefaultProperties beats Sangam's default: " + names);
            assertEquals("full", env.getProperty("operaton.bpm.history-level"), "Sangam's default otherwise");
        }
    }

    @Test
    void aValidatedDevelopmentHostCreatesItsSchema() {
        try (ConfigurableApplicationContext ctx = run(Map.of("spring.config.location",
                "optional:classpath:/no-host-config/"), new String[] {"dev"}, "--sangam.security.production=false")) {
            assertEquals("true", ctx.getEnvironment().getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE));
            assertEquals("startup", ctx.getEnvironment().getProperty(LiquibaseMode.PROPERTY));
        }
    }

    @Test
    void productionAndAnUnstatedEnvironmentChangeNoSchema() {
        // decision 143: a missing switch counts as production; so does the switch with a development profile (198)
        String[][] cases = {{"--sangam.security.production=true"}, {}};
        for (String[] args : cases) {
            for (String[] profiles : new String[][] {{}, {"dev"}}) {
                try (ConfigurableApplicationContext ctx = run(Map.of("spring.config.location",
                        "optional:classpath:/no-host-config/"), profiles, args)) {
                    String what = List.of(args) + " " + List.of(profiles);
                    assertEquals("false", ctx.getEnvironment()
                            .getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE), what);
                    assertEquals("validate", ctx.getEnvironment().getProperty(LiquibaseMode.PROPERTY), what);
                }
            }
        }
        // a non-production switch without a development profile is not a validated development environment
        try (ConfigurableApplicationContext ctx = run(Map.of("spring.config.location",
                "optional:classpath:/no-host-config/"), new String[] {}, "--sangam.security.production=false")) {
            assertEquals("false", ctx.getEnvironment()
                    .getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE));
        }
    }

    @Test
    void anExplicitHostSettingIsHonouredInProductionAsInTheServer() {
        try (ConfigurableApplicationContext ctx = run(Map.of("spring.config.location",
                        "optional:classpath:/no-host-config/", SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE,
                        "true", LiquibaseMode.PROPERTY, "startup"), new String[] {},
                "--sangam.security.production=true")) {
            assertEquals("true", ctx.getEnvironment().getProperty(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE));
            assertEquals("startup", ctx.getEnvironment().getProperty(LiquibaseMode.PROPERTY));
        }
    }

    @Test
    void theOneShotRunsGetWhatTheServersMainGivesThem() {
        Map<String, Object> migrate = SangamDefaultsEnvironmentPostProcessor.environmentDefaults(false,
                LiquibaseMode.MIGRATE_ONLY);
        assertEquals("true", migrate.get(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE));
        assertEquals("false", migrate.get(SangamDefaultsEnvironmentPostProcessor.JOB_EXECUTION_ENABLED));
        Map<String, Object> sql = SangamDefaultsEnvironmentPostProcessor.environmentDefaults(false,
                LiquibaseMode.UPDATE_SQL);
        assertEquals("false", sql.get(SangamDefaultsEnvironmentPostProcessor.ENGINE_SCHEMA_UPDATE));
        assertEquals("false", sql.get(SangamDefaultsEnvironmentPostProcessor.JOB_EXECUTION_ENABLED));
        Map<String, Object> serving = SangamDefaultsEnvironmentPostProcessor.environmentDefaults(false,
                LiquibaseMode.VALIDATE);
        assertNull(serving.get(SangamDefaultsEnvironmentPostProcessor.JOB_EXECUTION_ENABLED), "Sangam's own default");
        assertEquals("${SANGAM_LIQUIBASE_MODE:validate}", serving.get(LiquibaseMode.PROPERTY));
        assertEquals("${SANGAM_LIQUIBASE_MODE:startup}",
                SangamDefaultsEnvironmentPostProcessor.environmentDefaults(true, null).get(LiquibaseMode.PROPERTY));
    }

    @Test
    void theOrderStepLeavesAnEnvironmentWithoutSangamsDefaultsAlone() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("defaultProperties", Map.of("a", "b")));
        List<String> before = names(env);
        SangamDefaultsOrder.moveToEnd(env);
        assertEquals(before, names(env));
        assertFalse(env.getPropertySources().contains(SangamDefaultsEnvironmentPostProcessor.SOURCE_NAME));
    }
}
