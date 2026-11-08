// SPDX-License-Identifier: Apache-2.0
package org.sangam.ai.draft;

import com.fasterxml.jackson.databind.JsonNode;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.dmn.Dmn;
import org.sangam.ai.expr.ExpressionPlayground;
import org.sangam.integration.connector.Json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation every generated draft must pass before a human may approve it. These are structural checks; the package
 * validation in sangam-rest remains an extra gate through the {@link DraftService.Sink}.
 */
public final class DraftValidators {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[A-Za-z0-9_.]+}");

    private DraftValidators() {
    }

    public static List<String> validate(DraftService.Kind kind, String content, Map<String, Object> context) {
        return switch (kind) {
            case BPMN -> bpmn(content);
            case DMN -> dmn(content);
            case APP -> app(content);
            case FEEL_EXPRESSION -> feel(content);
            case DASHBOARD -> dashboard(content);
            case TRANSLATION -> translation(content, context);
            case AGENT_PROMPT -> content == null || content.isBlank() ? List.of("empty prompt") : List.of();
        };
    }

    static List<String> bpmn(String xml) {
        try {
            var model = Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Bpmn.validateModel(model);
            if (model.getModelElementsByType(Process.class).isEmpty()) {
                return List.of("the model has no process");
            }
            return List.of();
        } catch (RuntimeException e) {
            return List.of("BPMN: " + e.getMessage());
        }
    }

    static List<String> dmn(String xml) {
        try {
            Dmn.validateModel(Dmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            return List.of();
        } catch (RuntimeException e) {
            return List.of("DMN: " + e.getMessage());
        }
    }

    /** A full app is a JSON manifest {@code {"artifacts":[{"kind","name","content"}]}}; each artifact is validated by kind. */
    static List<String> app(String json) {
        JsonNode root = Json.tree(json.getBytes(StandardCharsets.UTF_8));
        if (root == null || !root.path("artifacts").isArray() || root.path("artifacts").isEmpty()) {
            return List.of("app manifest needs a non-empty artifacts array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode a : root.path("artifacts")) {
            String name = a.path("name").asText("?");
            try {
                DraftService.Kind k = DraftService.Kind.valueOf(a.path("kind").asText());
                if (k == DraftService.Kind.APP) {
                    out.add(name + ": nested apps are not allowed");
                    continue;
                }
                for (String e : validate(k, a.path("content").asText(""), Map.of())) {
                    out.add(name + ": " + e);
                }
            } catch (IllegalArgumentException e) {
                out.add(name + ": unknown kind " + a.path("kind").asText());
            }
        }
        return out;
    }

    static List<String> feel(String expr) {
        return ExpressionPlayground.syntaxErrors(expr);
    }

    static List<String> dashboard(String json) {
        JsonNode root = Json.tree(json.getBytes(StandardCharsets.UTF_8));
        if (root == null || !root.path("widgets").isArray()) {
            return List.of("dashboard needs a widgets array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode w : root.path("widgets")) {
            if (!w.hasNonNull("type") || !w.hasNonNull("query")) {
                out.add("each widget needs type and query");
            }
        }
        return out;
    }

    /** Every source key is present and every placeholder of the source survives. {@code context.source} is the catalogue. */
    @SuppressWarnings("unchecked")
    static List<String> translation(String json, Map<String, Object> context) {
        JsonNode root = Json.tree(json.getBytes(StandardCharsets.UTF_8));
        if (root == null || !root.isObject()) {
            return List.of("translation must be a JSON object of key to text");
        }
        List<String> out = new ArrayList<>();
        Map<String, Object> source = context.get("source") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        source.forEach((k, v) -> {
            if (!root.hasNonNull(k)) {
                out.add("missing key " + k);
                return;
            }
            Matcher ph = PLACEHOLDER.matcher(String.valueOf(v));
            while (ph.find()) {
                if (!root.get(k).asText().contains(ph.group())) {
                    out.add("key " + k + " lost placeholder " + ph.group());
                }
            }
        });
        return out;
    }
}
