// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The module switch defaults the server README states are the defaults of the server's application.yaml (the
 * {@code ${SANGAM_MODULE_<NAME>:<default>}} of {@code sangam.modules.<name>.enabled}). Found when the README said
 * {@code true} for {@code sangam.modules.connectors-health.enabled}, which decision 187 and the code have off.
 */
class ServerReadmeModuleDefaultsTest {

    private static final Pattern ROW = Pattern.compile("^\\|([^|]*)\\|\\s*(true|false)\\b[^|]*\\|", Pattern.MULTILINE);
    private static final Pattern SWITCH = Pattern.compile("`sangam\\.modules\\.([a-z-]+)\\.enabled`");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z_]+:(true|false)}$");

    @SuppressWarnings("unchecked")
    static Map<String, String> yamlDefaults() throws Exception {
        try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/application.yaml"), StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            Map<String, Object> modules = (Map<String, Object>) ((Map<String, Object>) root.get("sangam")).get("modules");
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : modules.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m && m.get("enabled") != null) {
                    String v = String.valueOf(m.get("enabled"));
                    Matcher p = PLACEHOLDER.matcher(v);
                    out.put(e.getKey(), p.matches() ? p.group(1) : v);
                }
            }
            return out;
        }
    }

    @Test
    void everyModuleSwitchDefaultInTheReadmeIsTheApplicationYamlDefault() throws Exception {
        Map<String, String> yaml = yamlDefaults();
        assertEquals("false", yaml.get("connectors-health"), "decision 187: the health connectors module is off");
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int checked = 0;
        Matcher row = ROW.matcher(readme);
        while (row.find()) {
            Matcher s = SWITCH.matcher(row.group(1));
            while (s.find()) {
                String module = s.group(1);
                String stated = row.group(2);
                assertNotNull(yaml.get(module), () -> "README names sangam.modules." + module + ".enabled, which"
                        + " application.yaml does not set");
                assertEquals(yaml.get(module), stated, () -> "README states " + stated + " as the default of"
                        + " sangam.modules." + module + ".enabled; application.yaml has " + yaml.get(module));
                checked++;
            }
        }
        assertTrue(checked >= 3, "the README module rows were found: " + checked);
    }
}
