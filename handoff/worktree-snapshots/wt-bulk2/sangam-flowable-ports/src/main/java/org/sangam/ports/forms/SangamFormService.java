// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ports.support.Json;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Form engine (FLOWABLE-INTO-OPERATON FC-39 Flowable {@code FormService}/{@code FormRepositoryService}; EC-02 runtime; R47):
 * versioned definitions ({@code FORM_DEF}), task and start forms with server-side validation, outcomes, and
 * variable-scoped task forms (fields with {@code scope=local} read and write task-local variables).
 *
 * <p>Form key convention on BPMN user tasks / start events: {@code operaton:formKey="sangam:<formKey>"} (latest
 * version) or {@code "sangam:<formKey>:<version>"} (pinned, R43). Other form keys (embedded/camunda-forms) are left
 * to their renderers. Callers authorise the task (VI.4) and pass WS-SECURITY's payload hash on completion (VI.4 rule 5).
 */
public class SangamFormService {

    public static final String PREFIX = "sangam:";
    public static final String OUTCOME_VARIABLE_SUFFIX = "_outcome";

    public record DeployedForm(String tenantId, String key, int version, String sha256, FormDefinition definition) {
    }

    /** A form ready to render: definition plus current values of its fields. */
    public record RenderedForm(DeployedForm form, Map<String, Object> values) {
    }

    private final ProcessEngine engine;
    private final Dialect dialect;
    private final Transactions tx;

    public SangamFormService(ProcessEngine engine, Dialect dialect, Transactions tx) {
        this.engine = engine;
        this.dialect = dialect;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ repository

    /** Deploys a form (Sangam, form-js or Flowable JSON). Same content → same version. */
    public DeployedForm deploy(String tenant, String key, String json, String appDeploymentId) {
        FormDefinition def = FormJsConverter.convert(key, json);
        String canonical = Json.write(def);
        String sha = Hashes.hex(Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        return tx.inTransaction(c -> {
            Optional<DeployedForm> latest = latest(c, tenant, def.key());
            if (latest.isPresent() && latest.get().sha256().equals(sha)) {
                return latest.get();
            }
            int v = latest.map(f -> f.version() + 1).orElse(1);
            Jdbc.update(c, "INSERT INTO FORM_DEF(tenant_id, form_key, version, definition, sha256, app_deployment_id, created_at)"
                    + " VALUES (?,?,?,?,?,?," + dialect.now() + ")", t(tenant), def.key(), v, canonical, sha, appDeploymentId);
            return new DeployedForm(tenant, def.key(), v, sha, def);
        });
    }

    public Optional<DeployedForm> form(String tenant, String key, Integer version) {
        return tx.inTransaction(c -> version == null ? latest(c, tenant, key)
                : Jdbc.one(c, "SELECT version, sha256, definition FROM FORM_DEF WHERE tenant_id = ? AND form_key = ? AND version = ?",
                rs -> new DeployedForm(tenant, key, rs.getInt(1), rs.getString(2), Json.read(rs.getString(3), FormDefinition.class)),
                t(tenant), key, version));
    }

    private Optional<DeployedForm> latest(Connection c, String tenant, String key) {
        List<DeployedForm> l = Jdbc.query(c, "SELECT version, sha256, definition FROM FORM_DEF WHERE tenant_id = ? AND form_key = ? ORDER BY version DESC",
                rs -> new DeployedForm(tenant, key, rs.getInt(1), rs.getString(2), Json.read(rs.getString(3), FormDefinition.class)), t(tenant), key);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    // ------------------------------------------------------------------ task forms

    public RenderedForm getTaskForm(String taskId) {
        Task task = task(taskId);
        DeployedForm form = resolve(task.getTenantId(), task.getFormKey());
        Map<String, Object> local = engine.getTaskService().getVariablesLocal(taskId);
        Map<String, Object> all = engine.getTaskService().getVariables(taskId);
        Map<String, Object> values = new LinkedHashMap<>();
        for (FormDefinition.Field f : form.definition().fields()) {
            Object v = f.local() ? local.get(f.variableName()) : all.get(f.variableName());
            values.put(f.id(), v != null ? v : f.defaultValue());
        }
        return new RenderedForm(form, values);
    }

    /**
     * Validates and completes. Returns the validation result; the task completes only when it is valid. The chosen
     * outcome (if the form defines outcomes) is stored as {@code <formKey>_outcome}.
     */
    public FormValidator.Result submitTaskForm(String taskId, Map<String, Object> values, String outcome) {
        Task task = task(taskId);
        DeployedForm form = resolve(task.getTenantId(), task.getFormKey());
        FormValidator.Result r = validateWithOutcome(form.definition(), values, outcome);
        if (!r.valid()) {
            return r;
        }
        Map<String, Object> processVars = new HashMap<>();
        Map<String, Object> localVars = new HashMap<>();
        for (Map.Entry<String, Object> e : r.values().entrySet()) {
            FormDefinition.Field f = form.definition().field(e.getKey());
            (f.local() ? localVars : processVars).put(f.variableName(), e.getValue());
        }
        if (outcome != null) {
            processVars.put(form.key() + OUTCOME_VARIABLE_SUFFIX, outcome);
        }
        if (!localVars.isEmpty()) {
            engine.getTaskService().setVariablesLocal(taskId, localVars);
        }
        engine.getTaskService().complete(taskId, processVars);
        return r;
    }

    /** Saves a draft (task-local, no completion, no validation of requiredness). */
    public void saveTaskDraft(String taskId, Map<String, Object> values) {
        Task task = task(taskId);
        DeployedForm form = resolve(task.getTenantId(), task.getFormKey());
        Map<String, Object> draft = new LinkedHashMap<>();
        for (FormDefinition.Field f : form.definition().fields()) {
            if (values.containsKey(f.id()) && !f.isReadOnly()) {
                draft.put(f.id(), values.get(f.id()));
            }
        }
        engine.getTaskService().setVariableLocal(taskId, "_sangamFormDraft", org.operaton.bpm.engine.variable.Variables.stringValue(Json.write(draft)));
    }

    // ------------------------------------------------------------------ start forms

    public DeployedForm getStartForm(String processDefinitionId) {
        String key = engine.getFormService().getStartFormKey(processDefinitionId);
        String tenant = engine.getRepositoryService().getProcessDefinition(processDefinitionId).getTenantId();
        return resolve(tenant, key);
    }

    /** Validates and starts; returns the result and (when valid) the new process instance id. */
    public Map.Entry<FormValidator.Result, String> submitStartForm(String processDefinitionId, String businessKey, Map<String, Object> values) {
        DeployedForm form = getStartForm(processDefinitionId);
        FormValidator.Result r = FormValidator.validate(form.definition(), values);
        if (!r.valid()) {
            return Map.entry(r, "");
        }
        Map<String, Object> vars = new HashMap<>();
        r.values().forEach((k, v) -> vars.put(form.definition().field(k).variableName(), v));
        String id = engine.getRuntimeService().startProcessInstanceById(processDefinitionId, businessKey, vars).getId();
        return Map.entry(r, id);
    }

    // ------------------------------------------------------------------ helpers

    FormValidator.Result validateWithOutcome(FormDefinition def, Map<String, Object> values, String outcome) {
        FormValidator.Result r = FormValidator.validate(def, values);
        if (!def.outcomes().isEmpty() && (outcome == null || def.outcomes().stream().noneMatch(o -> o.id().equals(outcome)))) {
            Map<String, List<String>> errors = new LinkedHashMap<>(r.errors());
            errors.put("_outcome", List.of("choose one of " + def.outcomes().stream().map(FormDefinition.Outcome::id).toList()));
            return new FormValidator.Result(false, errors, r.values());
        }
        return r;
    }

    private DeployedForm resolve(String tenant, String formKey) {
        if (formKey == null || !formKey.startsWith(PREFIX)) {
            throw new ProcessEngineException("form key '" + formKey + "' is not a Sangam form (expected sangam:<key>[:<version>])");
        }
        String rest = formKey.substring(PREFIX.length());
        int colon = rest.lastIndexOf(':');
        String key = rest;
        Integer version = null;
        if (colon > 0) {
            try {
                version = Integer.parseInt(rest.substring(colon + 1));
                key = rest.substring(0, colon);
            } catch (NumberFormatException e) {
                // key contains ':'; no version
            }
        }
        String k = key;
        Integer v = version;
        return form(tenant, k, v).orElseThrow(() -> new ProcessEngineException("form " + k + (v == null ? "" : " v" + v) + " not deployed"));
    }

    private Task task(String taskId) {
        Task t = engine.getTaskService().createTaskQuery().taskId(taskId).initializeFormKeys().singleResult();
        if (t == null) {
            throw new ProcessEngineException("task " + taskId + " not found");
        }
        return t;
    }

    private static String t(String tenant) {
        return tenant == null ? "" : tenant;
    }
}
