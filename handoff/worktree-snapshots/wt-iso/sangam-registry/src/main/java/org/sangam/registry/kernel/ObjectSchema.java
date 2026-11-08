// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.kernel;

import org.sangam.registry.RegistryException;
import org.sangam.registry.support.Maps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The attribute schema of a configured type: its fields and whether unknown attributes are allowed.
 */
public record ObjectSchema(List<FieldDefinition> fields, boolean additionalAllowed) {

    public static final ObjectSchema OPEN = new ObjectSchema(List.of(), true);

    public static ObjectSchema from(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return OPEN;
        }
        List<FieldDefinition> f = new ArrayList<>();
        for (Map<String, Object> fm : Maps.maps(m, "fields")) {
            f.add(FieldDefinition.from(fm));
        }
        return new ObjectSchema(List.copyOf(f), Maps.bool(m, "additionalAllowed", f.isEmpty()));
    }

    public Map<String, Object> toMap() {
        return Maps.of("fields", fields.stream().map(FieldDefinition::toMap).toList(), "additionalAllowed", additionalAllowed);
    }

    public FieldDefinition field(String name) {
        for (FieldDefinition f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Validates attributes against the schema.
     *
     * @param attrs    the new attribute values (complete for a create, a patch for an update)
     * @param existing current attributes for an update (null for a create)
     * @return the complete normalised attribute map
     */
    public Map<String, Object> validate(Map<String, Object> attrs, Map<String, Object> existing) {
        Map<String, Object> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        List<String> errors = new ArrayList<>();
        Map<String, Object> in = attrs == null ? Map.of() : attrs;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            FieldDefinition f = field(e.getKey());
            if (f == null) {
                if (!additionalAllowed) {
                    errors.add(e.getKey() + ": not a field of this type");
                    continue;
                }
                merged.put(e.getKey(), e.getValue());
                continue;
            }
            if (existing != null && f.immutable() && existing.containsKey(f.name())
                    && !java.util.Objects.equals(String.valueOf(existing.get(f.name())), String.valueOf(e.getValue()))) {
                errors.add(f.name() + ": immutable (use an endorsement)");
                continue;
            }
            merged.put(f.name(), e.getValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDefinition f : fields) {
            try {
                Object v = f.normalise(merged.get(f.name()));
                if (v != null) {
                    out.put(f.name(), v);
                }
            } catch (IllegalArgumentException ex) {
                errors.add(ex.getMessage());
            }
        }
        if (additionalAllowed) {
            merged.forEach((k, v) -> {
                if (field(k) == null && v != null) {
                    out.put(k, v);
                }
            });
        }
        if (!errors.isEmpty()) {
            throw RegistryException.invalid("schema validation failed: " + String.join("; ", errors));
        }
        return out;
    }

    public List<String> sensitiveFields() {
        return fields.stream().filter(FieldDefinition::sensitive).map(FieldDefinition::name).toList();
    }

    public List<String> publicFields() {
        return fields.stream().filter(FieldDefinition::publicField).map(FieldDefinition::name).toList();
    }
}
