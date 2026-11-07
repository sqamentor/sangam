// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sangam's own versioned template format for reusable elements (kind ELEMENT), palette entries (PALETTE) and composite form components
 * (FORM_COMPONENT). An ELEMENT template names the BPMN element types it applies to and fields bound to task properties, with validation
 * rules. {@link #apply} validates values and returns the property values the modeller writes. A published version is immutable; a change
 * is a new version. "Save task as template" is {@link #fromTask}.
 *
 * <pre>{"id":"notify","version":1,"appliesTo":["bpmn:ServiceTask"],"fields":[{"name":"topic","binding":"operaton:topic","required":true,"pattern":"[a-z.]+","default":"x"}]}</pre>
 */
public final class ElementTemplates {

    public enum Kind { ELEMENT, PALETTE, FORM_COMPONENT, PROCESS, DMN }

    public record Field(String name, String binding, boolean required, String pattern, Object defaultValue, List<String> choices) {
    }

    public record Template(String id, int version, Kind kind, List<String> appliesTo, List<Field> fields, Map<String, Object> document) {
    }

    public record Applied(Map<String, Object> properties, List<String> errors) {
    }

    private static final Set<String> BINDING_PREFIX = Set.of("operaton:", "bpmn:", "input:", "output:", "form:");

    private final Dialect dialect;
    private final Transactions tx;

    public ElementTemplates(Dialect dialect, Transactions tx) {
        this.dialect = dialect;
        this.tx = tx;
    }

    /** Validates and publishes the next version of a template; returns it. */
    public Template publish(String tenantId, Kind kind, Map<String, Object> document, String actor) {
        String id = String.valueOf(document.get("id"));
        if (!id.matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("template id is invalid");
        }
        List<String> errors = validate(kind, document);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return tx.inTransaction(c -> {
            int v = Jdbc.one(c, "SELECT COALESCE(MAX(version),0)+1 FROM MOD_TEMPLATE WHERE tenant_id=? AND template_id=?", rs -> rs.getInt(1), tenantId, id).orElse(1);
            Map<String, Object> doc = new LinkedHashMap<>(document);
            doc.put("version", v);
            Jdbc.update(c, "INSERT INTO MOD_TEMPLATE(tenant_id,template_id,version,kind,document,created_by,created_at) VALUES (?,?,?,?,?,?,?)", tenantId, id, v, kind.name(), Json.write(doc), actor, Jdbc.dbNow(c, dialect));
            return parse(kind, doc);
        });
    }

    public Optional<Template> find(String tenantId, String id, Integer versionOrNull) {
        return tx.inTransaction(c -> versionOrNull == null
                ? Jdbc.one(c, "SELECT kind,document FROM MOD_TEMPLATE t WHERE tenant_id=? AND template_id=? AND version=(SELECT MAX(version) FROM MOD_TEMPLATE x WHERE x.tenant_id=t.tenant_id AND x.template_id=t.template_id)",
                rs -> parse(Kind.valueOf(rs.getString(1)), Json.readMap(rs.getString(2))), tenantId, id)
                : Jdbc.one(c, "SELECT kind,document FROM MOD_TEMPLATE WHERE tenant_id=? AND template_id=? AND version=?", rs -> parse(Kind.valueOf(rs.getString(1)), Json.readMap(rs.getString(2))), tenantId, id, versionOrNull));
    }

    /** Latest version of each template of a kind, for the palette. */
    public List<Template> latest(String tenantId, Kind kind) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT kind,document FROM MOD_TEMPLATE t WHERE tenant_id=? AND kind=? AND version=(SELECT MAX(version) FROM MOD_TEMPLATE x WHERE x.tenant_id=t.tenant_id AND x.template_id=t.template_id) ORDER BY template_id",
                rs -> parse(Kind.valueOf(rs.getString(1)), Json.readMap(rs.getString(2))), tenantId, kind.name()));
    }

    public static Applied apply(Template t, String elementType, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        if (!t.appliesTo().isEmpty() && !t.appliesTo().contains(elementType)) {
            errors.add("template does not apply to " + elementType);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sangam:templateId", t.id());
        props.put("sangam:templateVersion", t.version());
        for (Field f : t.fields()) {
            Object v = values.containsKey(f.name()) ? values.get(f.name()) : f.defaultValue();
            if (v == null || v.toString().isEmpty()) {
                if (f.required()) {
                    errors.add(f.name() + " is required");
                }
                continue;
            }
            if (f.pattern() != null && !Pattern.matches(f.pattern(), v.toString())) {
                errors.add(f.name() + " does not match " + f.pattern());
            }
            if (f.choices() != null && !f.choices().isEmpty() && !f.choices().contains(v.toString())) {
                errors.add(f.name() + " must be one of " + f.choices());
            }
            props.put(f.binding(), v);
        }
        return new Applied(props, errors);
    }

    /** Builds a template document from an existing task's properties, each as an optional field with its current value as default. */
    public static Map<String, Object> fromTask(String id, String elementType, Map<String, Object> taskProperties) {
        List<Map<String, Object>> fields = new ArrayList<>();
        taskProperties.forEach((binding, value) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", binding.substring(binding.indexOf(':') + 1));
            f.put("binding", binding);
            f.put("required", false);
            f.put("default", value);
            fields.add(f);
        });
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("appliesTo", List.of(elementType));
        doc.put("fields", fields);
        return doc;
    }

    @SuppressWarnings("unchecked")
    static List<String> validate(Kind kind, Map<String, Object> doc) {
        List<String> errors = new ArrayList<>();
        Object rawFields = doc.get("fields");
        if (!(rawFields instanceof List<?>)) {
            return kind == Kind.ELEMENT ? List.of("fields must be a list") : errors;
        }
        Set<String> names = new java.util.HashSet<>();
        for (Object o : (List<?>) rawFields) {
            if (!(o instanceof Map<?, ?> m)) {
                errors.add("a field must be an object");
                continue;
            }
            String name = String.valueOf(m.get("name"));
            String binding = String.valueOf(m.get("binding"));
            if (!names.add(name)) {
                errors.add("duplicate field " + name);
            }
            if (BINDING_PREFIX.stream().noneMatch(binding::startsWith)) {
                errors.add("field " + name + " has an unknown binding " + binding);
            }
            Object pattern = m.get("pattern");
            if (pattern != null) {
                try {
                    Pattern.compile(pattern.toString());
                } catch (RuntimeException e) {
                    errors.add("field " + name + " has an invalid pattern");
                }
            }
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static Template parse(Kind kind, Map<String, Object> d) {
        List<Field> fields = new ArrayList<>();
        if (d.get("fields") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                fields.add(new Field(String.valueOf(m.get("name")), String.valueOf(m.get("binding")), Boolean.TRUE.equals(m.get("required")), (String) m.get("pattern"), m.get("default"), (List<String>) m.get("choices")));
            }
        }
        List<String> applies = d.get("appliesTo") instanceof List<?> a ? a.stream().map(String::valueOf).toList() : List.of();
        return new Template(String.valueOf(d.get("id")), ((Number) d.get("version")).intValue(), kind, applies, fields, d);
    }
}
