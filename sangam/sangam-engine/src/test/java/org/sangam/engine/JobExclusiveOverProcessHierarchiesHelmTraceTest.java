// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * M-055: the Helm value reaches the engine. values.yaml default = the engine default, the schema types it, and the
 * server ConfigMap renders it under the variable name {@link SangamEngineSettings#applyEnvironment} reads (a text
 * trace like sangam-server's HelmProductionSettingsTraceTest; {@code helm template} itself runs in ci-build.yml).
 */
class JobExclusiveOverProcessHierarchiesHelmTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");
    private static final String ENV = "SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES";

    private static String read(String p) throws IOException {
        return Files.readString(CHART.resolve(p)).replace("\r\n", "\n");
    }

    @Test
    void valuesDefaultEqualsTheEngineDefault() throws IOException {
        Matcher m = Pattern.compile("(?m)^  jobExecutor:\\n(?:    .*\\n)*?    exclusiveOverProcessHierarchies: (true|false)$")
                .matcher(read("values.yaml"));
        assertTrue(m.find(), "server.jobExecutor.exclusiveOverProcessHierarchies in values.yaml");
        assertEquals(String.valueOf(new SangamEngineSettings().isJobExclusiveOverProcessHierarchies()), m.group(1));
    }

    @Test
    void schemaTypesItAsBoolean() throws IOException {
        assertTrue(Pattern.compile("\"jobExecutor\": \\{\\s*\"type\": \"object\",\\s*\"properties\": \\{\\s*"
                        + "\"exclusiveOverProcessHierarchies\": \\{ \"type\": \"boolean\" }")
                .matcher(read("values.schema.json")).find(), "values.schema.json server.jobExecutor");
    }

    @Test
    void configMapRendersTheVariableTheEngineReads() throws IOException {
        assertTrue(read("templates/server-configmap.yaml").contains(
                ENV + ": {{ .Values.server.jobExecutor.exclusiveOverProcessHierarchies | quote }}"));
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.applyEnvironment(Map.of(ENV, "false")); // what "| quote" renders for false
        assertFalse(s.isJobExclusiveOverProcessHierarchies(), "the rendered name is the one the engine reads");
    }
}
