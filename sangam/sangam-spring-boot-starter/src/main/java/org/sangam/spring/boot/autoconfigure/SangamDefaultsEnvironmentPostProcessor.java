// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.server.config.LiquibaseMode;
import org.sangam.server.config.ProductionEnvironment;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * M-014 (owner decision 2026-11-12): Sangam's application defaults for an embedding application, from
 * {@value #RESOURCE}, as the lowest-precedence property source {@value #SOURCE_NAME}: below the host's configuration
 * files, profiles, system properties, environment and Spring Boot's {@code defaultProperties}
 * ({@code SpringApplication.setDefaultProperties}, {@code SpringApplicationBuilder.properties}), so the host can override
 * every key (decision 334). Runs after {@link ConfigDataEnvironmentPostProcessor} has added the host's configuration (a
 * property source added earlier would sit above it). Spring Boot moves {@code defaultProperties} to the end after
 * every environment post-processor has run ({@code SpringApplication.prepareEnvironment}), which put them below this
 * source; {@link SangamDefaultsOrder} therefore moves this source to the end again once the environment is prepared.
 *
 * <p><b>Schema (decision 334).</b> Three defaults depend on the environment the host states
 * ({@link ProductionEnvironment}, decisions 143 and 198; never a switch of the starter's own), see
 * {@link #environmentDefaults}: only a validated development environment gets the engine schema and the product
 * changelog applied at start. Everywhere else the starter neither creates nor changes schema: pending product
 * changesets refuse the start, and missing engine tables refuse it by name ({@code ENGINE_SCHEMA_MISSING},
 * {@code EngineSchemaGuard}). A value the host sets itself wins, as for every key.
 *
 * <p>Also the start-time check of the host's Spring Boot line (backlog M-014: Boot {@value #BOOT_LINE}x); the UTC
 * guard ({@code EngineTimeZoneEnvironmentPostProcessor}, ADR-D-27), the Jackson 2.22 guard and the Liquibase 4.x guard
 * come with the product jars and run in a host as in the server.
 *
 * <p>Not applied to the standalone server ({@code SangamServerApplication} as a source): its own application.yaml is
 * the source these defaults are derived from. {@code sangam.starter.defaults.enabled=false} switches them off.
 */
public class SangamDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String RESOURCE = "META-INF/sangam/sangam-defaults.yaml";
    public static final String SOURCE_NAME = "sangamDefaults";
    public static final String ENABLED_PROPERTY = "sangam.starter.defaults.enabled";
    /** The Operaton starter's engine schema setting ({@code false}: check only; {@code true}: create or upgrade). */
    public static final String ENGINE_SCHEMA_UPDATE = "operaton.bpm.database.schema-update";
    /** The Operaton starter's job executor switch. */
    public static final String JOB_EXECUTION_ENABLED = "operaton.bpm.job-execution.enabled";
    /** The Spring Boot line Sangam is built and tested on. */
    public static final String BOOT_LINE = "4.1.";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (isStandaloneServer(application)) {
            return;
        }
        requireBootLine(SpringBootVersion.getVersion());
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, true)) {
            return;
        }
        if (!environment.getPropertySources().contains(SOURCE_NAME)) {
            // read before the source is added: what the host itself states
            Map<String, Object> schema = environmentDefaults(
                    ProductionEnvironment.developmentDefaultsAllowed(environment), hostLiquibaseMode(environment));
            MapPropertySource defaults = load(new ClassPathResource(RESOURCE,
                    SangamDefaultsEnvironmentPostProcessor.class.getClassLoader()));
            defaults.getSource().putAll(schema);
            environment.getPropertySources().addLast(defaults);
        }
    }

    /** The Liquibase mode the host asks for (property, else the {@code SANGAM_LIQUIBASE_MODE} variable), or null. */
    static LiquibaseMode hostLiquibaseMode(ConfigurableEnvironment environment) {
        String mode = environment.getProperty(LiquibaseMode.PROPERTY);
        if (mode == null || mode.isBlank()) {
            mode = environment.getProperty(LiquibaseMode.ENV);
        }
        return mode == null || mode.isBlank() ? null : LiquibaseMode.parse(mode);
    }

    /**
     * Decision 334: the defaults that depend on the environment, over the values of {@value #RESOURCE}.
     * <ul>
     *   <li>{@value #ENGINE_SCHEMA_UPDATE}: {@code true} in a validated development environment (the engine creates
     *       its tables on the empty development database), {@code false} everywhere else (check only);</li>
     *   <li>{@code sangam.liquibase.mode}: {@code startup} (apply the product changelog) in development,
     *       {@code validate} (refuse a start with pending changesets, change nothing) everywhere else;
     *       {@code SANGAM_LIQUIBASE_MODE} still selects the mode, as in the server;</li>
     *   <li>a one-shot schema run the operator asks for ({@code migrate-only}, {@code update-sql},
     *       {@code release-locks}; deploy README 1.3) gets what the server's {@code main} gives it
     *       ({@code LiquibaseMode.applySystemProperties}): no job executor, and for {@code migrate-only} the engine
     *       creates or upgrades its schema in that run.</li>
     * </ul>
     *
     * @param development {@code ProductionEnvironment.developmentDefaultsAllowed} of the host's environment
     * @param hostMode    the Liquibase mode the host set, or null
     */
    static Map<String, Object> environmentDefaults(boolean development, LiquibaseMode hostMode) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean migrate = hostMode == LiquibaseMode.MIGRATE_ONLY;
        out.put(ENGINE_SCHEMA_UPDATE, development || migrate ? "true" : "false");
        out.put(LiquibaseMode.PROPERTY, "${" + LiquibaseMode.ENV + ":" + (development ? "startup" : "validate") + "}");
        if (hostMode != null && hostMode.oneShot()) {
            out.put(JOB_EXECUTION_ENABLED, "false");
        }
        return out;
    }

    static boolean isStandaloneServer(SpringApplication application) {
        return application != null && application.getAllSources().stream().anyMatch(s ->
                s instanceof Class<?> c && SangamProductImportSelector.SERVER_APPLICATION.equals(c.getName()));
    }

    /** Fails the start-up with a clear message on a Spring Boot line Sangam does not support. */
    static void requireBootLine(String version) {
        if (version != null && !version.startsWith(BOOT_LINE)) {
            throw new IllegalStateException("Sangam needs Spring Boot " + BOOT_LINE + "x (the Operaton 2.2 starter and"
                    + " Sangam are built on it); this application runs Spring Boot " + version + ". Use Spring Boot "
                    + BOOT_LINE + "x and import sangam-bom before the Spring Boot BOM.");
        }
    }

    /** The defaults as one map property source (values unwrapped, placeholders kept for resolution at read time). */
    static MapPropertySource load(Resource resource) {
        List<PropertySource<?>> docs;
        try {
            docs = new YamlPropertySourceLoader().load(SOURCE_NAME, resource);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (PropertySource<?> doc : docs) {
            if (doc instanceof EnumerablePropertySource<?> e) {
                for (String name : e.getPropertyNames()) {
                    Object v = e.getProperty(name);
                    values.putIfAbsent(name, v instanceof OriginTrackedValue o ? o.getValue() : v);
                }
            }
        }
        return new MapPropertySource(SOURCE_NAME, values);
    }
}
