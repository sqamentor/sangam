// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * files, profiles, system properties, environment and even Spring Boot's {@code defaultProperties}, so the host can
 * override every key. Runs after {@link ConfigDataEnvironmentPostProcessor} has added the host's configuration (a
 * property source added earlier would sit above it).
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
            environment.getPropertySources().addLast(load(new ClassPathResource(RESOURCE,
                    SangamDefaultsEnvironmentPostProcessor.class.getClassLoader())));
        }
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
