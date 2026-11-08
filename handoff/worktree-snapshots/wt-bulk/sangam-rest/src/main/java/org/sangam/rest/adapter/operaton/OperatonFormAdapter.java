// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;

/**
 * Forms contract (R47 / X.2.5, FLOWABLE-INTO-OPERATON FC-39) on Operaton deployments. Drafts are persisted in the product table
 * {@code FORM_DRAFT} (X.2.5.8): the form version hash in {@code form_version_hash} (changeset 0016-03; the legacy int
 * {@code form_version} is written as 0), the data in {@code data_ciphertext}: encrypted with the tenant's data key by
 * sangam-security's {@code FieldEncryptor} when bound (X.2.5.8 rule 1; AES-GCM, context = draft id), else UTF-8 JSON. Expired drafts
 * are invisible and deleted lazily on access.
 *
 * <p>A form version is read from the tenant's deployment resources, newest deployment first:
 * {@code <key>.form} (form-js layout), {@code <key>.schema.json} (data schema, authoritative),
 * {@code <key>.policy.json} (field policy, authoritative, default deny) and {@code <key>.i18n.<lang>.json}.
 * {@code versionHash = sha256hex(schemaHash|layoutHash|policyHash)}, each part the sha256 hex of the raw bytes or
 * {@code -}. A task is pinned to the version in its variable {@code sangamFormVersion} (X.2.5.8, in-flight versions).
 *
 * <p>Field policy JSON: {@code {"tasks": {"<taskDefinitionKey>": {"<field>": "editable|readonly|hidden|masked"}},
 * "start": {...}, "fields": {...}}}; a value may also be {@code {"access": "editable"}}. A submitted field that is not
 * {@code editable} for the task (or start form) is rejected with {@code FIELD_NOT_EDITABLE}.
 */
public final class OperatonFormAdapter implements FormPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper DRAFT_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final String DRAFT_COLS = "tenant_id, draft_id, task_id, form_key, form_version_hash, owner_kind,"
            + " owner_principal_id, owner_seat_id, revision, data_ciphertext, updated_at, expires_at,"
            + " last_editor_principal_id";
    private static final long MAX_DRAFT_BYTES = 1024 * 1024;
    private static final String ENC_PREFIX = "sgm-enc1:";
    private static final String KEY_ID = "field-encryptor";
    private volatile org.sangam.core.security.FieldEncryptor encryptor;

    /** Binds sangam-security's FieldEncryptor: new and re-saved drafts are encrypted; clear legacy drafts stay readable. */
    public void setEncryptor(org.sangam.core.security.FieldEncryptor encryptor) {
        this.encryptor = encryptor;
    }
    private volatile org.sangam.rest.extension.TenantCustomFieldRegistry customFields =
            org.sangam.rest.extension.TenantCustomFieldRegistry.empty();
    private volatile java.util.function.Supplier<java.time.LocalDate> today = () -> java.time.LocalDate.now(java.time.ZoneOffset.UTC);

    /** SL-40: tenant custom fields in force on {@code today} are validated with the form's data schema. */
    public void setCustomFields(org.sangam.rest.extension.TenantCustomFieldRegistry registry,
                                java.util.function.Supplier<java.time.LocalDate> today) {
        this.customFields = registry == null ? org.sangam.rest.extension.TenantCustomFieldRegistry.empty() : registry;
        if (today != null) {
            this.today = today;
        }
    }
    private static final Duration DRAFT_TTL = Duration.ofDays(30);
    private static final String NONE = "-";

    private final RepositoryService repository;
    private final TaskService tasks;
    private final AuditPort audit;
    private final RestProperties props;
    private final FormSchemaValidator validator = new FormSchemaValidator();

    /** Deployments are immutable: cache per (deploymentId, key). Empty = the deployment has no such form. */
    private final ConcurrentHashMap<String, Optional<FormVersion>> cache = new ConcurrentHashMap<>();
    private final TxSupport tx;

    public OperatonFormAdapter(RepositoryService repository, TaskService tasks, AuditPort audit, RestProperties props,
                               TxSupport tx) {
        this.tx = tx;
        this.repository = repository;
        this.tasks = tasks;
        this.audit = audit;
        this.props = props;
    }

    // ------------------------------------------------------------------ form versions

    @Override
    public FormVersion get(RequestContext ctx, String formKey, String version) {
        if (formKey == null || formKey.isBlank() || "drafts".equals(formKey)) {
            throw ApiProblem.notFound("Form", formKey);
        }
        List<Deployment> deployments = OperatonSupport.call(() -> repository.createDeploymentQuery()
                .tenantIdIn(ctx.tenantId()).includeDeploymentsWithoutTenantId()
                .orderByDeploymentTime().desc().list());
        boolean first = true;
        for (Deployment d : deployments) {
            Optional<FormVersion> fv = load(d, formKey);
            if (fv.isEmpty()) {
                continue;
            }
            FormVersion f = fv.get();
            String state = first ? "ACTIVE" : "RETIRED";
            first = false;
            if (version == null || version.equals(f.versionHash())) {
                return withState(f, state);
            }
        }
        throw ApiProblem.notFound("Form", version == null ? formKey : formKey + "@" + version);
    }

    @Override
    public ValidationResult validate(RequestContext ctx, String formKey, ValidateRequest request) {
        String pinned = null;
        String taskDefinitionKey = null;
        if (request.taskId() != null) {
            Task task = OperatonSupport.call(() -> tasks.createTaskQuery().taskId(request.taskId())
                    .tenantIdIn(ctx.tenantId()).singleResult());
            if (task == null) {
                throw ApiProblem.notFound("Task", request.taskId());
            }
            taskDefinitionKey = task.getTaskDefinitionKey();
            Object v = OperatonSupport.call(() -> tasks.getVariable(task.getId(), "sangamFormVersion"));
            pinned = v == null ? null : v.toString();
        }
        FormVersion form = get(ctx, formKey, pinned);
        List<FieldError> errors = new ArrayList<>();
        if (request.formVersion() != null && !request.formVersion().equals(form.versionHash())) {
            errors.add(FormSchemaValidator.error("", "FORM_VERSION_STALE"));
        }
        JsonNode data = JSON.valueToTree(request.data() == null ? Map.of() : request.data());
        List<org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition> custom = customFields.isEmpty()
                ? List.of() : customFields.inForce(ctx.tenantId(),
                        org.sangam.rest.extension.TenantCustomFieldRegistry.FORM_PREFIX + formKey, today.get());
        java.util.Set<String> customEditable = new java.util.HashSet<>();
        custom.stream().filter(f -> !f.readOnly()).forEach(f -> customEditable.add(f.key()));
        if (form.dataSchema() != null || !custom.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> base = form.dataSchema() instanceof Map<?, ?> m ? (Map<String, Object>) m
                    : form.dataSchema() == null ? null : JSON.convertValue(form.dataSchema(), Map.class);
            JsonNode schema = JSON.valueToTree(org.sangam.rest.extension.TenantCustomFieldRegistry.extendSchema(base, custom));
            errors.addAll(validator.validate(schema, data));
            if (form.dataSchema() == null && form.layout() != null) {
                errors.addAll(validator.validateLayoutRequired(JSON.valueToTree(form.layout()), data));
            }
        } else if (form.layout() != null) {
            errors.addAll(validator.validateLayoutRequired(JSON.valueToTree(form.layout()), data));
        }
        if (form.fieldPolicy() != null) {
            errors.addAll(applyPolicy(JSON.valueToTree(form.fieldPolicy()), taskDefinitionKey, data, customEditable));
        }
        return new ValidationResult(errors.isEmpty(), form.versionHash(), List.copyOf(errors));
    }

    /** Default deny (X.2.5.1): a submitted top-level field must be listed as editable for this task / start form. */
    static List<FieldError> applyPolicy(JsonNode policy, String taskDefinitionKey, JsonNode data,
                                        java.util.Set<String> customEditable) {
        JsonNode rules = null;
        if (taskDefinitionKey != null) {
            rules = policy.path("tasks").get(taskDefinitionKey);
        } else {
            rules = policy.get("start");
        }
        if (rules == null || rules.isNull()) {
            rules = policy.get("fields");
        }
        List<FieldError> errors = new ArrayList<>();
        if (!data.isObject()) {
            return errors;
        }
        var it = data.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            String pointer = "/" + FormSchemaValidator.escape(field);
            JsonNode rule = rules == null ? null : (rules.has(field) ? rules.get(field) : rules.get(pointer));
            String access = rule == null ? (customEditable.contains(field) ? "editable" : "hidden")
                    : rule.isTextual() ? rule.asText() : rule.path("access").asText("hidden");
            if (!"editable".equals(access)) {
                errors.add(FormSchemaValidator.error(pointer, "FIELD_NOT_EDITABLE"));
            }
        }
        return errors;
    }

    private Optional<FormVersion> load(Deployment d, String key) {
        return cache.computeIfAbsent(d.getId() + "|" + key, k -> OperatonSupport.call(() -> {
            List<String> names = repository.getDeploymentResourceNames(d.getId());
            byte[] layout = resource(d, names, key + ".form");
            byte[] schema = resource(d, names, key + ".schema.json");
            byte[] policy = resource(d, names, key + ".policy.json");
            if (layout == null && schema == null) {
                return Optional.empty();
            }
            Map<String, Map<String, String>> i18n = new LinkedHashMap<>();
            String prefix = key + ".i18n.";
            for (String n : names) {
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (base.startsWith(prefix) && base.endsWith(".json")) {
                    String lang = base.substring(prefix.length(), base.length() - ".json".length());
                    byte[] b = read(d, n);
                    i18n.put(lang, parse(b, new TypeReference<Map<String, String>>() {
                    }, n));
                }
            }
            String sh = hash(schema);
            String lh = hash(layout);
            String ph = hash(policy);
            String versionHash = Hashes.hex(Hashes.sha256(sh + "|" + lh + "|" + ph));
            return Optional.of(new FormVersion(key, versionHash, sh, lh, ph,
                    schema == null ? null : parse(schema, new TypeReference<Object>() {
                    }, key + ".schema.json"),
                    layout == null ? null : parse(layout, new TypeReference<Object>() {
                    }, key + ".form"),
                    policy == null ? null : parse(policy, new TypeReference<Object>() {
                    }, key + ".policy.json"),
                    Map.copyOf(i18n), d.getId(), "ACTIVE"));
        }));
    }

    private byte[] resource(Deployment d, List<String> names, String fileName) {
        for (String n : names) {
            if (n.equals(fileName) || n.endsWith("/" + fileName)) {
                return read(d, n);
            }
        }
        return null;
    }

    private byte[] read(Deployment d, String name) {
        try (InputStream in = repository.getResourceAsStream(d.getId(), name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read deployment resource " + name, e);
        }
    }

    private static <T> T parse(byte[] b, TypeReference<T> type, String name) {
        try {
            return JSON.readValue(b, type);
        } catch (IOException e) {
            throw ApiProblem.unprocessable("FORM_INVALID", "Form resource " + name + " is not valid JSON");
        }
    }

    private static String hash(byte[] b) {
        return b == null ? NONE : Hashes.hex(Hashes.sha256(b));
    }

    private static FormVersion withState(FormVersion f, String state) {
        return new FormVersion(f.key(), f.versionHash(), f.dataSchemaHash(), f.layoutHash(), f.fieldPolicyHash(),
                f.dataSchema(), f.layout(), f.fieldPolicy(), f.i18n(), f.deploymentId(), state);
    }

    // ------------------------------------------------------------------ drafts (X.2.5.8)

    @Override
    public Page<Draft> drafts(RequestContext ctx, String taskId, Page.PageRequest page) {
        purgeExpired(ctx.tenantId());
        List<Object> params = new ArrayList<>(List.of(ctx.tenantId()));
        params.add(ctx.seatId());
        params.add(ctx.principalId());
        String where = "tenant_id=? AND ((owner_kind='SEAT' AND owner_seat_id=?) OR (owner_kind='PERSON'"
                + " AND owner_principal_id=?))";
        if (taskId != null) {
            where += " AND task_id=?";
            params.add(taskId);
        }
        String sql = "SELECT " + DRAFT_COLS + " FROM FORM_DRAFT WHERE " + where + " ORDER BY updated_at DESC";
        List<Draft> visible = tx.withConnection(c -> Jdbc.query(c, sql, this::draftRow, params.toArray()))
                .stream().filter(d -> visibleTo(ctx, d)).toList();
        return Page.slice(visible, page, props.getCountCap());
    }

    @Override
    public Draft draft(RequestContext ctx, String draftId) {
        purgeExpired(ctx.tenantId());
        Draft d = draftId == null ? null : load(ctx.tenantId(), draftId);
        if (d == null || !visibleTo(ctx, d)) {
            throw ApiProblem.notFound("Draft", draftId);
        }
        return d;
    }

    @Override
    public Draft createDraft(RequestContext ctx, SaveDraftRequest request) {
        if (request.formKey() == null || request.formKey().isBlank()) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "formKey is required").with("field", "formKey");
        }
        if (request.taskId() != null) {
            requireTask(ctx, request.taskId());
        }
        String ownerKind = request.ownerKind() != null ? request.ownerKind()
                : (ctx.seatId() != null && request.taskId() != null ? "SEAT" : "PERSON");
        if (!ownerKind.equals("PERSON") && !ownerKind.equals("SEAT")) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "ownerKind must be PERSON or SEAT").with("field", "ownerKind");
        }
        if (ownerKind.equals("SEAT") && ctx.seatId() == null) {
            throw ApiProblem.badRequest("SEAT_CONTEXT_REQUIRED", "A SEAT draft needs the X-Sangam-Seat header");
        }
        Map<String, Object> data = sized(request.data());
        Instant now = Instant.now();
        Draft d = new Draft(org.sangam.core.layer.Hashes.uuidV7().toString(), ctx.tenantId(), request.taskId(),
                request.formKey(), request.formVersion(), ownerKind, ctx.principalId(),
                ownerKind.equals("SEAT") ? ctx.seatId() : null, 1, data, now, now.plus(DRAFT_TTL), ctx.principalId());
        tx.withConnection(c -> Jdbc.update(c, "INSERT INTO FORM_DRAFT(" + DRAFT_COLS + ", form_version, key_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,'" + KEY_ID + "')", d.tenantId(), d.draftId(), d.taskId(), d.formKey(),
                d.formVersion(), d.ownerKind(), d.ownerPrincipalId(), d.ownerSeatId(), d.revision(), bytes(d.tenantId(), d.draftId(), d.data()),
                d.updatedAt(), d.expiresAt(), d.lastEditorPrincipalId()));
        return d;
    }

    @Override
    public Draft saveDraft(RequestContext ctx, String draftId, long expectedRevision, SaveDraftRequest request) {
        return tx.inTx(() -> {
            Draft cur = draft(ctx, draftId);
            if (cur.revision() != expectedRevision) {
                throw new ApiProblem(409, "DRAFT_CONFLICT", null,
                        "The draft was saved elsewhere (revision " + cur.revision() + ")",
                        Map.of("current", cur, "currentRevision", cur.revision()), null);
            }
            Map<String, Object> data = sized(request.data());
            Instant now = Instant.now();
            Draft d = new Draft(cur.draftId(), cur.tenantId(), cur.taskId(), cur.formKey(),
                    request.formVersion() == null ? cur.formVersion() : request.formVersion(), cur.ownerKind(),
                    cur.ownerPrincipalId(), cur.ownerSeatId(), cur.revision() + 1, data, now, now.plus(DRAFT_TTL),
                    ctx.principalId());
            int n = tx.withConnection(c -> Jdbc.update(c, "UPDATE FORM_DRAFT SET form_version_hash=?, revision=?,"
                            + " data_ciphertext=?, updated_at=?, expires_at=?, last_editor_principal_id=?"
                            + " WHERE tenant_id=? AND draft_id=? AND revision=?",
                    d.formVersion(), d.revision(), bytes(d.tenantId(), d.draftId(), d.data()), d.updatedAt(), d.expiresAt(),
                    d.lastEditorPrincipalId(), d.tenantId(), d.draftId(), cur.revision()));
            if (n == 0) {
                Draft now2 = load(ctx.tenantId(), draftId);
                throw new ApiProblem(409, "DRAFT_CONFLICT", null, "The draft was saved elsewhere",
                        now2 == null ? Map.of() : Map.of("current", now2, "currentRevision", now2.revision()), null);
            }
            return d;
        });
    }

    @Override
    public void deleteDraft(RequestContext ctx, String draftId) {
        Draft d = draft(ctx, draftId);
        tx.withConnection(c -> Jdbc.update(c, "DELETE FROM FORM_DRAFT WHERE tenant_id=? AND draft_id=?",
                ctx.tenantId(), draftId));
        // Discarding a seat draft written by a previous seat holder is a class-B event (X.2.5.8 rule 2).
        if ("SEAT".equals(d.ownerKind()) && !Objects.equals(d.lastEditorPrincipalId(), ctx.principalId())) {
            String chain = "sec:" + ctx.tenantId();
            if (d.taskId() != null) {
                Task t = OperatonSupport.call(() -> tasks.createTaskQuery().taskId(d.taskId()).singleResult());
                if (t != null && t.getProcessInstanceId() != null) {
                    chain = t.getProcessInstanceId();
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("draftId", d.draftId());
            payload.put("formKey", d.formKey());
            payload.put("seatId", d.ownerSeatId());
            payload.put("draftedBy", d.lastEditorPrincipalId());
            if (d.taskId() != null) {
                payload.put("taskId", d.taskId());
            }
            payload.put("actor", ctx.actor());
            audit.record(ctx, chain, "DRAFT_DISCARDED_BY_HANDOVER", payload);
        }
    }

    private static boolean visibleTo(RequestContext ctx, Draft d) {
        if (!Objects.equals(ctx.tenantId(), d.tenantId())) {
            return false;
        }
        if ("SEAT".equals(d.ownerKind())) {
            return ctx.seatId() != null && ctx.seatId().equals(d.ownerSeatId());
        }
        return Objects.equals(ctx.principalId(), d.ownerPrincipalId());
    }

    /** Lazy expiry (X.2.5.8 rule 4): expired drafts of the tenant are deleted before any read. */
    private void purgeExpired(String tenant) {
        tx.withConnection(c -> Jdbc.update(c, "DELETE FROM FORM_DRAFT WHERE tenant_id=? AND expires_at<?",
                tenant, Instant.now()));
    }

    private Draft load(String tenant, String draftId) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + DRAFT_COLS + " FROM FORM_DRAFT WHERE tenant_id=?"
                + " AND draft_id=?", this::draftRow, tenant, draftId)).orElse(null);
    }

    private Draft draftRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Draft(rs.getString("draft_id"), rs.getString("tenant_id"), rs.getString("task_id"),
                rs.getString("form_key"), rs.getString("form_version_hash"), rs.getString("owner_kind"),
                rs.getString("owner_principal_id"), rs.getString("owner_seat_id"), rs.getLong("revision"),
                data(rs.getString("tenant_id"), rs.getString("draft_id"), rs.getBytes("data_ciphertext")),
                Jdbc.instant(rs, "updated_at"), Jdbc.instant(rs, "expires_at"),
                rs.getString("last_editor_principal_id"));
    }

    /** Draft data at rest: tenant-key AES-GCM envelope when a FieldEncryptor is bound (X.2.5.8 rule 1), else JSON. */
    private byte[] bytes(String tenant, String draftId, Map<String, Object> data) {
        Map<String, Object> d = data == null ? Map.of() : data;
        if (encryptor != null) {
            return (ENC_PREFIX + encryptor.encryptJson(tenant, "form-draft:" + draftId, d))
                    .getBytes(StandardCharsets.UTF_8);
        }
        try {
            return DRAFT_JSON.writeValueAsBytes(d);
        } catch (IOException e) {
            throw ApiProblem.badRequest("VALIDATION_FAILED", "draft data is not serialisable");
        }
    }

    private Map<String, Object> data(String tenant, String draftId, byte[] b) {
        if (b == null || b.length == 0) {
            return new LinkedHashMap<>();
        }
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.startsWith(ENC_PREFIX)) {
            if (encryptor == null) {
                throw ApiProblem.unavailable("ENCRYPTION_UNAVAILABLE", "draft " + draftId
                        + " is encrypted at rest and no FieldEncryptor is configured", 60);
            }
            return new LinkedHashMap<>(encryptor.decryptJson(tenant, "form-draft:" + draftId, s.substring(ENC_PREFIX.length())));
        }
        try {
            return DRAFT_JSON.readValue(b, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("stored draft is not valid JSON", e);
        }
    }

    private void requireTask(RequestContext ctx, String taskId) {
        Task t = OperatonSupport.call(() -> tasks.createTaskQuery().taskId(taskId).tenantIdIn(ctx.tenantId()).singleResult());
        if (t == null) {
            throw ApiProblem.notFound("Task", taskId);
        }
    }

    /** A draft is checked only for size and type (X.2.5.8 rule 2). */
    private static Map<String, Object> sized(Map<String, Object> data) {
        Map<String, Object> d = data == null ? Map.of() : data;
        if (CanonicalJson.write(d).getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES) {
            throw ApiProblem.unprocessable("DRAFT_TOO_LARGE", "A draft may hold at most 1 MiB of data");
        }
        return new LinkedHashMap<>(d);
    }
}
