// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SG-39 / M-113 (owner decision Q-09): traces, in the chart text, how {@code ingress.private} reaches the Ingress. helm
 * is not in the unit-test build, so the template text is checked here (as in {@link HelmProductionSettingsTraceTest});
 * the value rules are in {@link HelmValuesSchemaTest}, and ci-build.yml renders the chart with the real helm.
 *
 * <ol>
 *   <li>The private branch refuses the public {@code ingress.className} with {@code fail} (the schema refuses it
 *       first; this keeps the refusal under {@code --skip-schema-validation}) and fails when no internal class results.</li>
 *   <li>The class is {@code ingress.private.className}, else the preset's {@code className}.</li>
 *   <li>The preset annotations are merged last, so they replace the same keys of {@code ingress.annotations}.</li>
 *   <li>{@code ingressClassName} and the annotations are rendered from the resolved values only, and an empty class
 *       (the GCP preset, whose class is the {@code kubernetes.io/ingress.class} annotation) renders no
 *       {@code ingressClassName}.</li>
 *   <li>values.yaml: private ingress off, and each preset carries its internal-load-balancer annotation.</li>
 * </ol>
 */
class HelmPrivateIngressTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");

    private static String read(String p) throws IOException {
        return Files.readString(CHART.resolve(p)).replace("\r\n", "\n");
    }

    /** The template text between {@code {{- if $private.enabled }}} and the start of the manifest. */
    private static String privateBranch(String ingress) {
        int from = ingress.indexOf("{{- if $private.enabled }}");
        int to = ingress.indexOf("apiVersion: networking.k8s.io/v1");
        assertTrue(from > 0 && to > from, "the private branch precedes the manifest");
        return ingress.substring(from, to);
    }

    @Test
    void thePrivateBranchRefusesThePublicClassAndRequiresAnInternalOne() throws IOException {
        String ingress = read("templates/ingress.yaml");
        assertTrue(ingress.contains("{{- $private := .Values.ingress.private -}}"));
        String branch = privateBranch(ingress);
        assertTrue(Pattern.compile("\\{\\{- if \\.Values\\.ingress\\.className }}\\n\\{\\{- fail \"ingress\\.className is the public"
                + " ingress class and is refused with ingress\\.private\\.enabled=true[^\"]*\" }}\\n\\{\\{- end }}").matcher(branch).find(),
                "private=true with a public ingress.className fails the render");
        assertTrue(Pattern.compile("\\{\\{- if not \\(or \\$className \\(hasKey \\$annotations \"kubernetes\\.io/ingress\\.class\"\\)\\) }}\\n"
                + "\\{\\{- fail \"ingress\\.private\\.enabled=true needs an internal class").matcher(branch).find(),
                "private=true without any class fails the render");
    }

    @Test
    void theClassComesFromThePrivateValueOrThePreset() throws IOException {
        String branch = privateBranch(read("templates/ingress.yaml"));
        assertTrue(branch.contains("{{- with $private.preset }}\n{{- $preset = index $private.presets . | default dict -}}\n{{- end }}"),
                "the preset is looked up by name");
        assertTrue(branch.contains("{{- $className = default ($preset.className | default \"\") $private.className -}}"),
                "ingress.private.className wins over the preset's className");
    }

    @Test
    void thePresetAnnotationsReplaceTheSameUserKeys() throws IOException {
        String ingress = read("templates/ingress.yaml");
        assertTrue(ingress.contains("{{- $annotations := deepCopy (.Values.ingress.annotations | default dict) -}}"));
        // sprig mergeOverwrite: later dictionaries win, so the preset (second) overrides the user annotations (first).
        assertTrue(privateBranch(ingress).contains(
                "{{- $annotations = mergeOverwrite $annotations (deepCopy ($preset.annotations | default dict)) -}}"));
    }

    @Test
    void theManifestRendersOnlyTheResolvedClassAndAnnotations() throws IOException {
        String ingress = read("templates/ingress.yaml");
        String manifest = ingress.substring(ingress.indexOf("apiVersion: networking.k8s.io/v1"));
        assertTrue(Pattern.compile("\\{\\{- with \\$annotations }}\\n\\s+annotations:\\n\\s+\\{\\{- toYaml \\. \\| nindent 4 }}")
                .matcher(manifest).find(), "annotations from the merged map");
        assertTrue(Pattern.compile("\\{\\{- with \\$className }}\\n\\s+ingressClassName: \\{\\{ \\. }}\\n\\s+\\{\\{- end }}")
                .matcher(manifest).find(), "ingressClassName only when the resolved class is not empty");
        assertFalse(manifest.contains(".Values.ingress.className"), "the manifest never reads the raw public class");
        assertFalse(manifest.contains(".Values.ingress.annotations"), "the manifest never reads the raw annotations");
        assertEquals(1, count(ingress, ".Values.ingress.annotations"), "the user annotations are read once, into $annotations");
    }

    @Test
    void valuesDefaultsAndPresets() throws IOException {
        String values = read("values.yaml");
        assertTrue(Pattern.compile("(?m)^ingress:\\n  enabled: false\\n").matcher(values).find(), "no ingress by default");
        assertTrue(Pattern.compile("(?m)^  private:\\n(?:    #.*\\n)*    enabled: false\\n(?:    #.*\\n)*    className: \"\"\\n"
                + "(?:    #.*\\n)*    preset: \"\"\\n").matcher(values).find(), "ingress.private off, no class, no preset");
        String[][] presets = {
                {"aws", "alb", "alb.ingress.kubernetes.io/scheme: internal"},
                {"azure", "azure-application-gateway", "appgw.ingress.kubernetes.io/use-private-ip: \"true\""},
                {"gcp", "\"\"", "kubernetes.io/ingress.class: gce-internal"}};
        for (String[] p : presets) {
            assertTrue(Pattern.compile("(?m)^      " + p[0] + ":\\n(?:        #.*\\n)*        className: " + Pattern.quote(p[1])
                    + "\\n        annotations:\\n          " + Pattern.quote(p[2]) + "$").matcher(values).find(),
                    "preset " + p[0] + ": className " + p[1] + ", " + p[2]);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
