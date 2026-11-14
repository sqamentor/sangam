// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.RepositoryService;
import org.sangam.rest.port.ApiProblem;

/**
 * SL-77: the required fields of a task's Sangam form, enforced when the task is completed through the REST API.
 *
 * <p>A user task names its form with {@code operaton:formKey="sangam:<formKey>"} or {@code "sangam:<formKey>:<n>"}
 * (pinned version, same convention as sangam-flowable-ports' {@code SangamFormService}). The form is a
 * {@code *.form.json} resource deployed in the SAME engine deployment as the task's process or case definition (a pack
 * deploys its {@code forms/*.form.json} with its BPMN, {@code PackDeployer}), whose top-level {@code key} equals
 * {@code <formKey>}. Its required variables are every {@code fields[]} entry with {@code "required": true} (variable
 * name = {@code id}, else {@code key}) and the {@code decision.variable} when {@code decision.required} is true.
 *
 * <p>Deployments are immutable, so the index is cached per deployment id. A form resource that is not valid JSON, or two
 * form resources of one deployment with the same key, fail closed ({@code 422 FORM_INVALID} / {@code FORM_AMBIGUOUS}):
 * a required field must never be skipped because the form could not be read.
 */
public final class TaskFormRequirements {

    /** Form-key prefix of Sangam forms. */
    public static final String PREFIX = "sangam:";
    private static final String SUFFIX = ".form.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RepositoryService repository;
    private final ConcurrentHashMap<String, Map<String, List<String>>> byDeployment = new ConcurrentHashMap<>();
    /** Definition id → deployment id; definitions are immutable. */
    private final ConcurrentHashMap<String, String> definitionDeployments = new ConcurrentHashMap<>();

    public TaskFormRequirements(RepositoryService repository) {
        this.repository = repository;
    }

    /**
     * The required variable names of the form {@code formKey} deployed in {@code deploymentId}; empty when the key is
     * not a Sangam form key, there is no deployment, or the deployment has no such form.
     */
    public List<String> required(String deploymentId, String formKey) {
        String key = formKeyOf(formKey);
        if (key == null || deploymentId == null) {
            return List.of();
        }
        return byDeployment.computeIfAbsent(deploymentId, this::index).getOrDefault(key, List.of());
    }

    /**
     * The required variables of a task's form: the form is looked up in the deployment of the task's process
     * definition, else of its case definition; a task without either (standalone) has none.
     */
    public List<String> requiredForTask(String processDefinitionId, String caseDefinitionId, String formKey) {
        if (formKeyOf(formKey) == null) {
            return List.of();
        }
        String deploymentId = null;
        if (processDefinitionId != null) {
            deploymentId = definitionDeployments.computeIfAbsent("P|" + processDefinitionId, k -> OperatonSupport.call(
                    () -> repository.getProcessDefinition(processDefinitionId).getDeploymentId()));
        } else if (caseDefinitionId != null) {
            deploymentId = definitionDeployments.computeIfAbsent("C|" + caseDefinitionId, k -> OperatonSupport.call(
                    () -> repository.getCaseDefinition(caseDefinitionId).getDeploymentId()));
        }
        return required(deploymentId, formKey);
    }

    /** {@code sangam:<key>} or {@code sangam:<key>:<number>} → {@code <key>}; any other form key → null. */
    static String formKeyOf(String formKey) {
        if (formKey == null || !formKey.startsWith(PREFIX)) {
            return null;
        }
        String rest = formKey.substring(PREFIX.length());
        int colon = rest.lastIndexOf(':');
        if (colon > 0 && rest.substring(colon + 1).matches("\\d{1,9}")) {
            rest = rest.substring(0, colon);
        }
        return rest.isBlank() ? null : rest;
    }

    /** The required variables of one form document (see the class comment), in form order, without duplicates. */
    static List<String> requiredOf(JsonNode form) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode fields = form.path("fields");
        if (fields.isArray()) {
            for (JsonNode f : fields) {
                if (f.path("required").asBoolean(false)) {
                    String name = text(f.get("id"));
                    if (name == null) {
                        name = text(f.get("key"));
                    }
                    if (name != null) {
                        out.add(name);
                    }
                }
            }
        }
        JsonNode decision = form.path("decision");
        if (decision.path("required").asBoolean(false)) {
            String name = text(decision.get("variable"));
            if (name != null) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The names of {@code required} that have no value in {@code values}: absent, null, a blank string, or an empty
     * collection, map or array. {@code false} and {@code 0} are values.
     */
    static List<String> missing(List<String> required, Map<String, Object> values) {
        List<String> out = new ArrayList<>();
        for (String name : required) {
            if (absent(values == null ? null : values.get(name))) {
                out.add(name);
            }
        }
        return out;
    }

    static boolean absent(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof CharSequence s) {
            return s.toString().isBlank();
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        if (v.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(v) == 0;
        }
        return false;
    }

    /** The problem for missing required fields: 422 {@code REQUIRED_FIELDS_MISSING} naming every field. */
    static ApiProblem problem(String formKey, List<String> missing) {
        return ApiProblem.unprocessable("REQUIRED_FIELDS_MISSING",
                        "Required form fields are missing: " + String.join(", ", missing))
                .with("fields", List.copyOf(missing))
                .with("formKey", formKey);
    }

    private Map<String, List<String>> index(String deploymentId) {
        List<String> names = OperatonSupport.call(() -> repository.getDeploymentResourceNames(deploymentId));
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String name : names) {
            if (!name.endsWith(SUFFIX)) {
                continue;
            }
            JsonNode form = parse(deploymentId, name);
            String key = text(form.get("key"));
            if (key == null) {
                continue;
            }
            if (out.put(key, requiredOf(form)) != null) {
                throw ApiProblem.unprocessable("FORM_AMBIGUOUS",
                        "Deployment " + deploymentId + " holds more than one form with key " + key);
            }
        }
        return Map.copyOf(out);
    }

    private JsonNode parse(String deploymentId, String name) {
        try (InputStream in = OperatonSupport.call(() -> repository.getResourceAsStream(deploymentId, name))) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw ApiProblem.unprocessable("FORM_INVALID", "Form resource " + name + " is not valid JSON");
        }
    }

    private static String text(JsonNode n) {
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }
}
