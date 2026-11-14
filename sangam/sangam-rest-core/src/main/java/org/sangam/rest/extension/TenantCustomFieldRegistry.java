// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.extension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * SL-40: metadata-driven tenant custom fields on registered objects and forms (the Salesforce custom field /
 * Dataverse column idea, as configuration only).
 * <ul>
 *   <li><b>Registered objects.</b> Only object types the platform registers are extensible ({@code form:<formKey>}
 *       for every deployed form, plus object types passed to the constructor). Anything else is refused.</li>
 *   <li><b>Definition.</b> A field key is {@code x_<name>} (it can never collide with a shipped field). Its schema is
 *       a scalar or array fragment in the Sangam form-data profile of JSON Schema (the keywords
 *       {@code FormSchemaValidator} enforces). Regex patterns must compile.</li>
 *   <li><b>Effective dating.</b> Each definition has {@code effectiveFrom} and an optional exclusive
 *       {@code effectiveTo}. A new version of a key starts after the current one and closes it on its start date, so
 *       exactly one version is in force on any date. {@link #retire} closes the current version. Nothing is deleted.</li>
 *   <li><b>Audit.</b> Every define and retire is recorded through {@link Audit} with the full definition, inside the
 *       store's transaction, so the change and its audit row commit or roll back together.</li>
 *   <li><b>Persistence.</b> Definitions live in a {@link Store}: in memory by default, or the shared
 *       {@code TENANT_CUSTOM_FIELD} table ({@code JdbcTenantCustomFieldStore}). Every read goes to the store, so a
 *       definition made on one node is in force on every node from its effective date; nothing is cached here.</li>
 *   <li><b>Validation.</b> {@link #extendSchema} merges the fields in force on a date into a form's data schema, so the
 *       one server-side validator checks shipped and custom fields alike.</li>
 * </ul>
 */
public final class TenantCustomFieldRegistry {

    /** Prefix of the object type of a deployed form. */
    public static final String FORM_PREFIX = "form:";
    private static final Pattern KEY = Pattern.compile("^x_[a-z][a-z0-9_]{0,62}$");
    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "array");
    private static final Set<String> KEYWORDS = Set.of("type", "title", "description", "enum", "const", "minLength",
            "maxLength", "pattern", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "format",
            "x-sangam-scale", "minItems", "maxItems", "items");
    private static final Set<String> FORMATS = Set.of("decimal", "integer-string", "date", "date-time", "email");

    /** Audit sink: (tenant, actor, action, payload). */
    @FunctionalInterface
    public interface Audit {
        void record(String tenantId, String actor, String action, Map<String, Object> payload);
    }

    /**
     * Where the versions of the custom fields are kept. Writes are only ever an insert of a new version and the
     * closing of an existing one ({@code effectiveTo} set), never a delete or a rewrite of any other column.
     */
    public interface Store {

        /** Every version of one key, ordered by version (empty when the key was never defined). */
        List<FieldDefinition> versions(String tenantId, String objectType, String key);

        /** The versions in force on {@code on} for a tenant and object type (any order). */
        List<FieldDefinition> inForce(String tenantId, String objectType, LocalDate on);

        /** Every version of every field of a tenant (any order). */
        List<FieldDefinition> history(String tenantId);

        /**
         * Adds a new version. A version that already exists (a concurrent definition of the same key) is refused
         * with a {@link ConcurrentChangeException}.
         */
        void insert(String tenantId, String actor, FieldDefinition definition);

        /**
         * Sets {@code effectiveTo} of {@code current} (exclusive end). Refused with a {@link ConcurrentChangeException}
         * when the stored version no longer ends where {@code current} says (closed or retired concurrently).
         */
        void close(String tenantId, String actor, FieldDefinition current, LocalDate effectiveTo);

        /** Runs {@code work} as one unit: the store writes and the audit of one change commit together. */
        <T> T inTransaction(Supplier<T> work);

        /** True only when the store is known to hold no definition at all (lets callers skip the per-date read). */
        boolean knownEmpty();
    }

    /** A definition changed concurrently (another node or request); the caller may re-read and retry. */
    public static final class ConcurrentChangeException extends IllegalStateException {
        public ConcurrentChangeException(String message) {
            super("CUSTOM_FIELD_CONFLICT: " + message);
        }
    }

    /** One version of a custom field. {@code effectiveTo} is exclusive; null means open-ended. */
    public record FieldDefinition(String objectType, String key, int version, String label, boolean required,
                                  boolean readOnly, Map<String, Object> schema, LocalDate effectiveFrom,
                                  LocalDate effectiveTo) {

        public boolean inForce(LocalDate on) {
            return !on.isBefore(effectiveFrom) && (effectiveTo == null || on.isBefore(effectiveTo));
        }

        /** This version with {@code effectiveTo} set to {@code to}. */
        public FieldDefinition closedAt(LocalDate to) {
            return new FieldDefinition(objectType, key, version, label, required, readOnly, schema, effectiveFrom, to);
        }

        /** The audit payload: every attribute of the version. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectType", objectType);
            m.put("key", key);
            m.put("version", version);
            m.put("label", label);
            m.put("required", required);
            m.put("readOnly", readOnly);
            m.put("schema", schema);
            m.put("effectiveFrom", effectiveFrom.toString());
            m.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
            return m;
        }
    }

    private final Set<String> registeredObjects;
    private final Audit audit;
    private final Store store;

    /** In-memory definitions (tests, a node without a database). */
    public TenantCustomFieldRegistry(Set<String> registeredObjects, Audit audit) {
        this(registeredObjects, audit, new InMemoryStore());
    }

    /** Definitions kept in {@code store} (production: {@code JdbcTenantCustomFieldStore}). */
    public TenantCustomFieldRegistry(Set<String> registeredObjects, Audit audit, Store store) {
        this.registeredObjects = Set.copyOf(registeredObjects);
        this.audit = audit;
        this.store = java.util.Objects.requireNonNull(store, "store");
    }

    public static TenantCustomFieldRegistry empty() {
        return new TenantCustomFieldRegistry(Set.of(), (t, a, x, p) -> { });
    }

    /** True when no definition can be in force (the store is known to be empty). */
    public boolean isEmpty() {
        return store.knownEmpty();
    }

    /** True for a form object type or a registered object type. */
    public boolean extensible(String objectType) {
        return objectType != null && (objectType.startsWith(FORM_PREFIX) && objectType.length() > FORM_PREFIX.length()
                || registeredObjects.contains(objectType));
    }

    /**
     * Loads definitions from tenant configuration: {@code {"customFields": [{objectType, key, label, required,
     * readOnly, schema, effectiveFrom, effectiveTo}]}}. Entries are applied in order, as {@link #define} calls.
     */
    @SuppressWarnings("unchecked")
    public void loadConfig(String tenantId, String actor, Map<String, Object> config) {
        Object list = config.get("customFields");
        if (list == null) {
            return;
        }
        if (!(list instanceof List<?> l)) {
            throw invalid("customFields must be a list");
        }
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> raw)) {
                throw invalid("each custom field must be an object");
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            Object schema = m.get("schema");
            if (!(schema instanceof Map<?, ?>)) {
                throw invalid("custom field " + m.get("key") + " needs a schema object");
            }
            define(tenantId, actor, str(m.get("objectType")), str(m.get("key")), str(m.get("label")),
                    Boolean.TRUE.equals(m.get("required")), Boolean.TRUE.equals(m.get("readOnly")),
                    (Map<String, Object>) schema, date(m.get("effectiveFrom"), "effectiveFrom"),
                    m.get("effectiveTo") == null ? null : date(m.get("effectiveTo"), "effectiveTo"));
        }
    }

    /** Defines a new version of {@code key}; the version in force before is closed on {@code effectiveFrom}. */
    public FieldDefinition define(String tenantId, String actor, String objectType, String key, String label,
                                  boolean required, boolean readOnly, Map<String, Object> schema,
                                  LocalDate effectiveFrom, LocalDate effectiveTo) {
        return define(tenantId, actor, objectType, key, label, required, readOnly, schema, effectiveFrom, effectiveTo,
                audit);
    }

    /**
     * As {@link #define(String, String, String, String, String, boolean, boolean, Map, LocalDate, LocalDate)}, audited
     * through {@code audit} instead of the registry's sink (a caller that holds the request's audit context).
     */
    public synchronized FieldDefinition define(String tenantId, String actor, String objectType, String key, String label,
                                               boolean required, boolean readOnly, Map<String, Object> schema,
                                               LocalDate effectiveFrom, LocalDate effectiveTo, Audit audit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw invalid("tenant is required");
        }
        if (!extensible(objectType)) {
            throw invalid("object type " + objectType + " is not registered as extensible");
        }
        if (key == null || !KEY.matcher(key).matches()) {
            throw invalid("custom field key must match x_[a-z][a-z0-9_]*: " + key);
        }
        if (effectiveFrom == null) {
            throw invalid("effectiveFrom is required");
        }
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw invalid("effectiveTo must be after effectiveFrom");
        }
        if (required && readOnly) {
            throw invalid(key + ": a read-only field cannot be required");
        }
        checkSchema(key, schema, 0);
        Map<String, Object> frozen = Map.copyOf(schema);
        return store.inTransaction(() -> {
            List<FieldDefinition> versions = store.versions(tenantId, objectType, key);
            int version = 1;
            if (!versions.isEmpty()) {
                FieldDefinition last = versions.get(versions.size() - 1);
                if (!effectiveFrom.isAfter(last.effectiveFrom())) {
                    throw invalid(key + ": a new version must start after " + last.effectiveFrom()
                            + " (history is never rewritten)");
                }
                if (last.effectiveTo() == null || last.effectiveTo().isAfter(effectiveFrom)) {
                    store.close(tenantId, actor, last, effectiveFrom);
                }
                version = last.version() + 1;
            }
            FieldDefinition def = new FieldDefinition(objectType, key, version, label, required, readOnly, frozen,
                    effectiveFrom, effectiveTo);
            store.insert(tenantId, actor, def);
            audit.record(tenantId, actor, "CUSTOM_FIELD_DEFINED", def.toMap());
            return def;
        });
    }

    /** Closes the version in force on {@code effectiveTo} (exclusive). Values already stored stay readable. */
    public FieldDefinition retire(String tenantId, String actor, String objectType, String key, LocalDate effectiveTo) {
        return retire(tenantId, actor, objectType, key, effectiveTo, audit);
    }

    /** As {@link #retire(String, String, String, String, LocalDate)}, audited through {@code audit}. */
    public synchronized FieldDefinition retire(String tenantId, String actor, String objectType, String key,
                                               LocalDate effectiveTo, Audit audit) {
        if (effectiveTo == null) {
            throw invalid("effectiveTo is required");
        }
        return store.inTransaction(() -> {
            List<FieldDefinition> versions = store.versions(tenantId, objectType, key);
            if (versions.isEmpty()) {
                throw invalid("no custom field " + key + " on " + objectType);
            }
            FieldDefinition last = versions.get(versions.size() - 1);
            if (!effectiveTo.isAfter(last.effectiveFrom())) {
                throw invalid(key + ": retirement must be after " + last.effectiveFrom());
            }
            if (last.effectiveTo() != null && !effectiveTo.isBefore(last.effectiveTo())) {
                throw invalid(key + " already ends on " + last.effectiveTo());
            }
            store.close(tenantId, actor, last, effectiveTo);
            FieldDefinition closed = last.closedAt(effectiveTo);
            audit.record(tenantId, actor, "CUSTOM_FIELD_RETIRED", closed.toMap());
            return closed;
        });
    }

    /** Field versions in force for a tenant and object type on a date, ordered by key. */
    public List<FieldDefinition> inForce(String tenantId, String objectType, LocalDate on) {
        List<FieldDefinition> out = new ArrayList<>(store.inForce(tenantId, objectType, on));
        out.sort(Comparator.comparing(FieldDefinition::key));
        return out;
    }

    /** Every version of one field, oldest first (empty when the key was never defined). */
    public List<FieldDefinition> versions(String tenantId, String objectType, String key) {
        List<FieldDefinition> out = new ArrayList<>(store.versions(tenantId, objectType, key));
        out.sort(Comparator.comparingInt(FieldDefinition::version));
        return out;
    }

    /** Every version of every field of a tenant (export, audit review). */
    public List<FieldDefinition> history(String tenantId) {
        List<FieldDefinition> out = new ArrayList<>(store.history(tenantId));
        out.sort(Comparator.comparing(FieldDefinition::objectType).thenComparing(FieldDefinition::key)
                .thenComparingInt(FieldDefinition::version));
        return out;
    }

    /**
     * The base data schema with the fields in force added under {@code properties} (and {@code required}). A base
     * schema that already declares a property of the same name keeps its own (shipped fields win).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> extendSchema(Map<String, Object> base, List<FieldDefinition> fields) {
        Map<String, Object> out = base == null ? new LinkedHashMap<>(Map.of("type", "object")) : new LinkedHashMap<>(base);
        if (fields.isEmpty()) {
            return out;
        }
        Map<String, Object> props = out.get("properties") instanceof Map<?, ?> p
                ? new LinkedHashMap<>((Map<String, Object>) p) : new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        if (out.get("required") instanceof List<?> r) {
            r.forEach(x -> required.add(String.valueOf(x)));
        }
        for (FieldDefinition f : fields) {
            if (props.putIfAbsent(f.key(), f.schema()) == null && f.required()) {
                required.add(f.key());
            }
        }
        out.put("properties", props);
        if (!required.isEmpty()) {
            out.put("required", List.copyOf(required));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void checkSchema(String key, Map<String, Object> schema, int depth) {
        if (schema == null || depth > 2) {
            throw invalid(key + ": schema missing or nested too deeply");
        }
        for (String k : schema.keySet()) {
            if (!KEYWORDS.contains(k)) {
                throw invalid(key + ": schema keyword '" + k + "' is not in the Sangam form-data profile");
            }
        }
        Object type = schema.get("type");
        if (!(type instanceof String t) || !TYPES.contains(t)) {
            throw invalid(key + ": schema type must be one of " + TYPES);
        }
        if ("number".equals(t)) {
            // R33: an exact amount is a decimal string, never a binary float.
            throw invalid(key + ": use {type: string, format: decimal} for numbers (R33)");
        }
        Object format = schema.get("format");
        if (format != null && !FORMATS.contains(format.toString().toLowerCase(Locale.ROOT))) {
            throw invalid(key + ": unknown format " + format);
        }
        if (schema.get("pattern") instanceof String p) {
            try {
                Pattern.compile(p);
            } catch (PatternSyntaxException e) {
                throw invalid(key + ": pattern does not compile");
            }
        }
        if ("array".equals(t)) {
            if (!(schema.get("items") instanceof Map<?, ?> items)) {
                throw invalid(key + ": an array needs items");
            }
            checkSchema(key, (Map<String, Object>) items, depth + 1);
        } else if (schema.containsKey("items")) {
            throw invalid(key + ": items only applies to arrays");
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate date(Object o, String field) {
        if (o instanceof LocalDate d) {
            return d;
        }
        try {
            return LocalDate.parse(String.valueOf(o));
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid(field + " must be an ISO date: " + o);
        }
    }

    private static IllegalArgumentException invalid(String why) {
        return new IllegalArgumentException("CUSTOM_FIELD_INVALID: " + why);
    }

    /**
     * Definitions in memory: tenant -> objectType|key -> versions in order. Not shared between nodes; the
     * registry's own lock serialises writes.
     */
    static final class InMemoryStore implements Store {

        private final Map<String, Map<String, List<FieldDefinition>>> data = new ConcurrentHashMap<>();

        private List<FieldDefinition> list(String tenantId, String objectType, String key) {
            return data.computeIfAbsent(tenantId, t -> new ConcurrentHashMap<>())
                    .computeIfAbsent(objectType + "|" + key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        }

        @Override
        public List<FieldDefinition> versions(String tenantId, String objectType, String key) {
            Map<String, List<FieldDefinition>> tenant = data.get(tenantId);
            List<FieldDefinition> v = tenant == null ? null : tenant.get(objectType + "|" + key);
            return v == null ? List.of() : List.copyOf(v);
        }

        @Override
        public List<FieldDefinition> inForce(String tenantId, String objectType, LocalDate on) {
            List<FieldDefinition> out = new ArrayList<>();
            data.getOrDefault(tenantId, Map.of()).forEach((k, versions) -> {
                if (k.startsWith(objectType + "|")) {
                    versions.stream().filter(v -> v.inForce(on)).forEach(out::add);
                }
            });
            return out;
        }

        @Override
        public List<FieldDefinition> history(String tenantId) {
            List<FieldDefinition> out = new ArrayList<>();
            data.getOrDefault(tenantId, Map.of()).values().forEach(out::addAll);
            return out;
        }

        @Override
        public void insert(String tenantId, String actor, FieldDefinition definition) {
            List<FieldDefinition> versions = list(tenantId, definition.objectType(), definition.key());
            if (versions.stream().anyMatch(v -> v.version() == definition.version())) {
                throw new ConcurrentChangeException(definition.key() + " v" + definition.version() + " already exists");
            }
            versions.add(definition);
        }

        @Override
        public void close(String tenantId, String actor, FieldDefinition current, LocalDate effectiveTo) {
            List<FieldDefinition> versions = list(tenantId, current.objectType(), current.key());
            for (int i = 0; i < versions.size(); i++) {
                FieldDefinition v = versions.get(i);
                if (v.version() == current.version()) {
                    if (!java.util.Objects.equals(v.effectiveTo(), current.effectiveTo())) {
                        throw new ConcurrentChangeException(current.key() + " v" + current.version() + " changed");
                    }
                    versions.set(i, v.closedAt(effectiveTo));
                    return;
                }
            }
            throw new ConcurrentChangeException(current.key() + " v" + current.version() + " does not exist");
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public boolean knownEmpty() {
            return data.values().stream().allMatch(t -> t.values().stream().allMatch(List::isEmpty));
        }
    }
}
