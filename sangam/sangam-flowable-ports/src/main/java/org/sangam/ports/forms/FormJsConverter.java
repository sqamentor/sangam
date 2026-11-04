// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.ports.support.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a form-js schema ({@code {"components":[{"key","type","label","validate":{required,min,max,minLength,
 * maxLength,pattern},"values":[{label,value}]}]}}, the format of the bpmn.io form editor used by the Sangam modeller
 * EC-02/EC-04) and Flowable form JSON ({@code {"fields":[{"id","type","name","required","params"}]}}) into a
 * {@link FormDefinition}. Layout-only components (text, spacer, group headers) are skipped; groups are flattened.
 */
public final class FormJsConverter {

    private FormJsConverter() {
    }

    public static FormDefinition convert(String key, String json) {
        JsonNode root = Json.tree(json);
        List<FormDefinition.Field> fields = new ArrayList<>();
        if (root.has("components")) {
            collectFormJs(root.get("components"), fields);
        } else if (root.has("fields")) {
            for (JsonNode f : root.get("fields")) {
                fields.add(flowableField(f));
            }
        } else {
            return Json.read(json, FormDefinition.class);
        }
        List<FormDefinition.Outcome> outcomes = new ArrayList<>();
        if (root.has("outcomes")) {
            for (JsonNode o : root.get("outcomes")) {
                outcomes.add(new FormDefinition.Outcome(o.path("id").asText(o.path("name").asText()), o.path("name").asText()));
            }
        }
        String name = root.path("name").asText(root.path("id").asText(key));
        return new FormDefinition(key, name, fields, outcomes);
    }

    private static void collectFormJs(JsonNode components, List<FormDefinition.Field> out) {
        for (JsonNode c : components) {
            String type = c.path("type").asText();
            if ("group".equals(type) || "dynamiclist".equals(type)) {
                collectFormJs(c.path("components"), out);
                continue;
            }
            if (!c.has("key")) {
                continue;
            }
            JsonNode v = c.path("validate");
            out.add(new FormDefinition.Field(c.get("key").asText(), mapFormJsType(type), text(c, "label"), null, null,
                    v.path("required").asBoolean(false), c.path("readonly").asBoolean(false) || c.path("disabled").asBoolean(false),
                    intOrNull(v, "minLength"), intOrNull(v, "maxLength"), doubleOrNull(v, "min"), doubleOrNull(v, "max"),
                    text(v, "pattern"), options(c.path("values"), "value", "label"), Json.plain(c.get("defaultValue")), null, null));
        }
    }

    private static FormDefinition.Field flowableField(JsonNode f) {
        String type = switch (f.path("type").asText("text")) {
            case "multi-line-text" -> "textarea";
            case "integer" -> "integer";
            case "decimal" -> "decimal";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "dropdown" -> "select";
            case "radio-buttons" -> "radio";
            case "upload" -> "document";
            case "expression" -> "expression";
            default -> "text";
        };
        return new FormDefinition.Field(f.path("id").asText(), type, text(f, "name"), null, null, f.path("required").asBoolean(false),
                f.path("readOnly").asBoolean(false), null, null, null, null, null, options(f.path("options"), "id", "name"),
                Json.plain(f.get("value")), null, null);
    }

    static String mapFormJsType(String t) {
        return switch (t) {
            case "textfield" -> "text";
            case "textarea" -> "textarea";
            case "number" -> "decimal";
            case "checkbox" -> "boolean";
            case "datetime" -> "datetime";
            case "select" -> "select";
            case "radio" -> "radio";
            case "checklist", "taglist" -> "multiselect";
            default -> "text";
        };
    }

    private static List<FormDefinition.Option> options(JsonNode arr, String valueField, String labelField) {
        if (arr == null || !arr.isArray()) {
            return null;
        }
        List<FormDefinition.Option> out = new ArrayList<>();
        for (JsonNode o : arr) {
            String value = o.path(valueField).asText(o.path("name").asText());
            out.add(new FormDefinition.Option(value, o.path(labelField).asText(value)));
        }
        return out;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? null : v.intValue();
    }

    private static Double doubleOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || !v.isNumber() ? null : v.doubleValue();
    }
}
