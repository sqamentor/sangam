// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-168: the three leaf-pool sizes (X1-05) and the engine tracing switch (M-062) are server keys that operators could
 * not set through the chart or compose. Each now goes the whole way: the server reads the env name with its default
 * (application.yaml), values.yaml carries the same default, the server ConfigMap renders exactly that env name from
 * the value, the schema types it, and compose passes the same env with the same default. A text trace in the style of
 * {@link HelmJobVirtualThreadsTraceTest}.
 */
class HelmLeafPoolsAndEngineTracingTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");
    private static final Path COMPOSE = Path.of("..", "deploy", "compose", "docker-compose.yml");
    private static final Path APP = Path.of("src", "main", "resources", "application.yaml");

    /** env name, values path below .Values, the values.yaml line under its parent, the schema property. */
    private record Key(String env, String valuesPath, String valuesKey, String schemaKey, String type) {
    }

    private static final List<Key> KEYS = List.of(
            new Key("SANGAM_QUOTA_POOL_SIZE", "database.pool.leafPools.quota", "quota", "quota", "integer"),
            new Key("SANGAM_SECURITY_DENIAL_AUDIT_POOL_SIZE", "database.pool.leafPools.securityDenialAudit",
                    "securityDenialAudit", "securityDenialAudit", "integer"),
            new Key("SANGAM_COMPLIANCE_DENIAL_AUDIT_POOL_SIZE", "database.pool.leafPools.complianceDenialAudit",
                    "complianceDenialAudit", "complianceDenialAudit", "integer"),
            new Key("SANGAM_TRACING_ENGINE_ENABLED", "otel.engineTracing", "engineTracing", "engineTracing", "boolean"));

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** The default the server applies: {@code ${ENV:default}} in application.yaml. */
    private static String serverDefault(String app, String env) {
        Matcher m = Pattern.compile("\\$\\{" + env + ":([^}]*)}").matcher(app);
        assertTrue(m.find(), env + " is read by application.yaml");
        return m.group(1);
    }

    @Test
    void valuesDefaultsEqualTheServerDefaults() throws IOException {
        String app = read(APP);
        String values = read(CHART.resolve("values.yaml"));
        for (Key k : KEYS) {
            Matcher m = Pattern.compile("(?m)^\\s+" + k.valuesKey() + ": (\\S+)$").matcher(values);
            assertTrue(m.find(), k.valuesPath() + " in values.yaml");
            assertEquals(serverDefault(app, k.env()), m.group(1), k.valuesPath() + " default");
        }
    }

    @Test
    void theConfigMapRendersTheEnvTheServerReads() throws IOException {
        String cm = read(CHART.resolve("templates/server-configmap.yaml"));
        for (Key k : KEYS) {
            assertTrue(cm.contains("  " + k.env() + ": {{ .Values." + k.valuesPath() + " | quote }}"), k.env());
        }
    }

    @Test
    void theSchemaTypesEachValue() throws IOException {
        String schema = read(CHART.resolve("values.schema.json"));
        for (Key k : KEYS) {
            assertTrue(Pattern.compile("\"" + k.schemaKey() + "\": \\{ \"type\": \"" + k.type() + "\"").matcher(schema).find(),
                    k.schemaKey() + " typed " + k.type());
        }
        assertTrue(schema.contains("\"leafPools\": {"), "leafPools object under database.pool");
    }

    @Test
    void composePassesTheSameEnvWithTheServerDefault() throws IOException {
        String app = read(APP);
        String compose = read(COMPOSE);
        for (Key k : KEYS) {
            assertTrue(compose.contains("    " + k.env() + ": ${" + k.env() + ":-" + serverDefault(app, k.env()) + "}"),
                    k.env() + " in docker-compose.yml");
        }
    }
}
