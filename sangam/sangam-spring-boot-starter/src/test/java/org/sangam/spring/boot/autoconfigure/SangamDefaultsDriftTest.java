// SPDX-License-Identifier: Apache-2.0
package org.sangam.spring.boot.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/**
 * The host defaults (META-INF/sangam/sangam-defaults.yaml) are sangam-server's application.yaml, key by key, without
 * the standalone server's deployment settings, and with the host module defaults of the owner decision (M-014): the
 * two files never drift apart. Change the server's application.yaml first, then the defaults.
 */
class SangamDefaultsDriftTest {

    /** Keys of the standalone server's deployment, not Sangam defaults for a host. */
    static final List<String> SERVER_ONLY = List.of("server.", "management.", "logging.", "spring.application.",
            "spring.profiles.", "spring.lifecycle.");

    /** Modules a host gets off until it enables them (on in the server, except {@link #SERVER_OFF}). */
    static final List<String> HOST_OFF = List.of("compliance", "pipelines", "telemetry", "config", "packs", "onboarding",
            "ledger", "billing", "registry", "resources", "case", "programme", "geo", "field", "connectors-health",
            "channels", "ai", "ops", "modeller");

    /** The optional modules that are off in the server as well (decision 187: sangam-connectors-health). */
    static final List<String> SERVER_OFF = List.of("connectors-health");

    static Map<String, Object> flat(MapPropertySource s) {
        return new TreeMap<>(s.getSource());
    }

    @Test
    void theDefaultsAreTheServersApplicationYamlWithoutItsDeploymentSettings() {
        Map<String, Object> server = flat(SangamDefaultsEnvironmentPostProcessor.load(new FileSystemResource(
                Path.of("..", "sangam-server", "src", "main", "resources", "application.yaml"))));
        Map<String, Object> defaults = flat(SangamDefaultsEnvironmentPostProcessor.load(
                new ClassPathResource(SangamDefaultsEnvironmentPostProcessor.RESOURCE)));

        Map<String, Object> expected = new TreeMap<>();
        server.forEach((k, v) -> {
            if (SERVER_ONLY.stream().noneMatch(k::startsWith)) {
                expected.put(k, v);
            }
        });
        for (String module : HOST_OFF) {
            String key = "sangam.modules." + module + ".enabled";
            String env = "SANGAM_MODULE_" + module.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            boolean on = !SERVER_OFF.contains(module);
            assertEquals("${" + env + ":" + on + "}", String.valueOf(expected.get(key)),
                    "the server sets " + key + (on ? " on" : " off"));
            expected.put(key, "${" + env + ":false}");
        }
        assertEquals(expected, defaults);
    }
}
