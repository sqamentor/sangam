// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.arazzo;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/** The Arazzo field names, id forms and small JSON helpers of {@link ArazzoImporter}. */
final class ArazzoSyntax {

    static final Pattern VERSION = Pattern.compile("1\\.[01]\\.[0-9]{1,4}");
    /** Arazzo name, workflowId and stepId characters. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,120}");
    /** The contract id and operation id forms of {@code OperationRef} (M-068). */
    static final Pattern CONTRACT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,120}");
    static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_.-]{1,200}");
    static final Pattern OUTPUT_NAME = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    static final Pattern SOURCE_OPERATION = Pattern.compile("\\$sourceDescriptions\\.([A-Za-z0-9_-]+)\\.(.+)");
    static final Pattern OPERATION_PATH = Pattern.compile("\\{\\$sourceDescriptions\\.([A-Za-z0-9_-]+)\\.url\\}#(/.*)");
    static final Set<String> IN = Set.of("path", "query", "header", "cookie");

    static final Set<String> ROOT_KEYS = Set.of("arazzo", "info", "sourceDescriptions", "workflows", "components");
    static final Set<String> SOURCE_KEYS = Set.of("name", "url", "type");
    static final Set<String> COMPONENT_KEYS = Set.of("inputs", "parameters", "successActions", "failureActions");
    static final Set<String> WORKFLOW_KEYS = Set.of("workflowId", "summary", "description", "inputs", "dependsOn",
            "steps", "successActions", "failureActions", "outputs", "parameters");
    static final Set<String> STEP_KEYS = Set.of("stepId", "description", "operationId", "operationPath", "workflowId",
            "parameters", "requestBody", "successCriteria", "onSuccess", "onFailure", "outputs");
    static final Set<String> PARAMETER_KEYS = Set.of("name", "in", "value");
    static final Set<String> REUSABLE_KEYS = Set.of("reference", "value");
    static final Set<String> BODY_KEYS = Set.of("contentType", "payload", "replacements");
    static final Set<String> CRITERION_KEYS = Set.of("condition", "context", "type");
    static final Set<String> ACTION_KEYS = Set.of("name", "type", "workflowId", "stepId", "criteria", "retryAfter",
            "retryLimit");

    private ArazzoSyntax() {
    }

    static boolean operationExists(JsonNode openApi, String operationId) {
        JsonNode paths = openApi.get("paths");
        if (paths == null || !paths.isObject()) {
            return false;
        }
        for (Iterator<JsonNode> it = paths.elements(); it.hasNext(); ) {
            JsonNode item = it.next();
            for (Iterator<JsonNode> ops = item.elements(); ops.hasNext(); ) {
                JsonNode op = ops.next();
                if (op.isObject() && operationId.equals(text(op, "operationId"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An XML NCName from an Arazzo id: other characters become '_', and a leading digit or '-' gets a '_' prefix. */
    static String ncName(String id) {
        String s = id.replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_') ? "_" + s : s;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
