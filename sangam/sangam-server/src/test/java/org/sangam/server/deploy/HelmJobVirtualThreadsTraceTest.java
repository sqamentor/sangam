// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.sangam.engine.SangamEngineSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-054: {@code server.jobExecutor.virtualThreads} reaches the engine as {@code SANGAM_JOB_VIRTUAL_THREADS}. The
 * values.yaml default equals the engine default, the server ConfigMap renders it under the name
 * {@link SangamEngineSettings#applyEnvironment} reads, and the schema types it (also checked by
 * {@link HelmValuesSchemaTest}). A text trace in the style of sangam-engine's JobExclusiveOverProcessHierarchiesHelmTraceTest.
 */
class HelmJobVirtualThreadsTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");
    private static final String ENV = "SANGAM_JOB_VIRTUAL_THREADS";

    private static String read(String p) throws IOException {
        return Files.readString(CHART.resolve(p)).replace("\r\n", "\n");
    }

    @Test
    void valuesDefaultEqualsTheEngineDefault() throws IOException {
        Matcher m = Pattern.compile("(?m)^  jobExecutor:\\n(?:    .*\\n)*?    virtualThreads: (true|false)$").matcher(read("values.yaml"));
        assertTrue(m.find(), "server.jobExecutor.virtualThreads in values.yaml");
        assertEquals(String.valueOf(new SangamEngineSettings().isJobVirtualThreads()), m.group(1));
        assertEquals("false", m.group(1), "off by default until the soak evidence (R30)");
    }

    @Test
    void configMapRendersTheVariableTheEngineReads() throws IOException {
        assertTrue(read("templates/server-configmap.yaml").contains(
                ENV + ": {{ .Values.server.jobExecutor.virtualThreads | quote }}"));
        SangamEngineSettings s = new SangamEngineSettings();
        s.applyEnvironment(Map.of(ENV, "true")); // what "| quote" renders for true
        assertTrue(s.isJobVirtualThreads(), "the rendered name is the one the engine reads");
    }

    @Test
    void schemaTypesItAsBoolean() throws IOException {
        assertTrue(Pattern.compile("\"jobExecutor\": \\{\\s*\"type\": \"object\",\\s*\"properties\": \\{\\s*"
                + "\"exclusiveOverProcessHierarchies\": \\{ \"type\": \"boolean\" },\\s*\"virtualThreads\": \\{ \"type\": \"boolean\" }").matcher(read("values.schema.json")).find());
    }
}
