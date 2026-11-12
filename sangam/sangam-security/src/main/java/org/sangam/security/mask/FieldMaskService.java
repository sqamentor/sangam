// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.mask;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.security.audit.SecurityAuditService;
import org.sangam.security.audit.SecurityEventType;
import org.sangam.security.policy.AuthorizationService;
import org.sangam.security.policy.Permission;
import org.sangam.security.policy.ResourceRegistry;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field-level masks (VI.4 rule 4, K11, X.2.5.5): variables and form fields are classified per resource type and scope
 * key (definition key or form key) as needing {@code READ_VARIABLE} or {@code READ_SENSITIVE}. The projection layer
 * calls {@link #mask} for lists and objects alike, so a field hidden in a list is hidden in the detail view too.
 * Unmasked sensitive reads write {@code SENSITIVE_READ} to the security chain (X.2.3.2).
 */
public final class FieldMaskService {

    public static final String MASK = "***";

    public record FieldRule(String field, Permission required) {
    }

    private final AuthorizationService authz;
    private final SecurityAuditService audit;

    public FieldMaskService(AuthorizationService authz, SecurityAuditService audit) {
        this.authz = authz;
        this.audit = audit;
    }

    public void putRule(Connection c, SecurityIdentity admin, String resourceType, String scopeKey, String field, Permission required) {
        authz.require(c, admin, ResourceRegistry.POLICY, Permission.CHANGE_GRANT, ResourceRegistry.ANY_ID);
        Jdbc.update(c, "DELETE FROM SEC_FIELD_RULE WHERE tenant_id=? AND resource_type=? AND scope_key=? AND field_name=?",
                admin.tenantId(), resourceType, scopeKey, field);
        Jdbc.update(c, "INSERT INTO SEC_FIELD_RULE(tenant_id, resource_type, scope_key, field_name, required_perm) VALUES (?,?,?,?,?)",
                admin.tenantId(), resourceType, scopeKey, field, required.name());
    }

    public List<FieldRule> rules(Connection c, String tenant, String resourceType, String scopeKey) {
        return Jdbc.query(c, "SELECT field_name, required_perm FROM SEC_FIELD_RULE WHERE tenant_id=? AND resource_type=? AND scope_key=?",
                rs -> new FieldRule(rs.getString(1), Permission.parse(rs.getString(2))), tenant, resourceType, scopeKey);
    }

    /**
     * Returns a copy of {@code values} with each classified field the caller may not read replaced by {@link #MASK}.
     * Permissions are checked per resource with the same object predicate as everything else; results are reused for
     * the fields of one call.
     */
    public Map<String, Object> mask(Connection c, SecurityIdentity who, String resourceType, String resourceId, String scopeKey,
                                    Map<String, ?> values) {
        List<FieldRule> rules = rules(c, who.tenantId(), resourceType, scopeKey);
        Map<String, Object> out = new LinkedHashMap<>(values);
        if (rules.isEmpty()) {
            return out;
        }
        Map<Permission, Boolean> allowed = new HashMap<>();
        List<String> sensitiveShown = new ArrayList<>();
        for (FieldRule r : rules) {
            if (!out.containsKey(r.field())) {
                continue;
            }
            boolean ok = allowed.computeIfAbsent(r.required(), p -> authz.allow(c, who, resourceType, p, resourceId, Map.of()));
            if (!ok) {
                out.put(r.field(), MASK);
            } else if (r.required() == Permission.READ_SENSITIVE) {
                sensitiveShown.add(r.field());
            }
        }
        if (!sensitiveShown.isEmpty()) {
            audit.security(c, who, SecurityEventType.SENSITIVE_READ, Map.of("resource_type", resourceType,
                    "resource_id", resourceId, "fields", sensitiveShown));
        }
        return out;
    }

    /**
     * Masks every row of a list exactly as {@link #mask} masks the row as an object (K11), set-based (M-086, R19): the
     * rules are read once, and each classified permission is decided for all row ids in one step
     * ({@link AuthorizationService#allowedIds}, which equals the object check per id) instead of one object check per
     * row and permission. The result, and the {@code SENSITIVE_READ} events (one per row that shows a sensitive field,
     * in row order, with the same payload), are those of calling {@link #mask} for each row
     * ({@code FieldMaskSetEquivalencePropertiesTest}).
     */
    public List<Map<String, Object>> maskAll(Connection c, SecurityIdentity who, String resourceType, String scopeKey,
                                             List<Map<String, Object>> rows, String idField) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        if (rows.isEmpty()) {
            return out;
        }
        List<FieldRule> rules = rules(c, who.tenantId(), resourceType, scopeKey);
        if (rules.isEmpty()) {
            rows.forEach(r -> out.add(new LinkedHashMap<>(r)));
            return out;
        }
        List<String> ids = new ArrayList<>(rows.size());
        rows.forEach(r -> ids.add(String.valueOf(r.get(idField))));
        // the permissions some row actually needs (mask() checks a permission only for a field the row has)
        Map<Permission, List<String>> needed = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            for (FieldRule r : rules) {
                if (rows.get(i).containsKey(r.field())) {
                    needed.computeIfAbsent(r.required(), p -> new ArrayList<>()).add(ids.get(i));
                }
            }
        }
        Map<Permission, java.util.Set<String>> allowedIds = new HashMap<>();
        needed.forEach((p, forIds) -> allowedIds.put(p, authz.allowedIds(c, who, resourceType, p, forIds)));
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>(rows.get(i));
            List<String> sensitiveShown = new ArrayList<>();
            for (FieldRule r : rules) {
                if (!row.containsKey(r.field())) {
                    continue;
                }
                if (!allowedIds.get(r.required()).contains(ids.get(i))) {
                    row.put(r.field(), MASK);
                } else if (r.required() == Permission.READ_SENSITIVE) {
                    sensitiveShown.add(r.field());
                }
            }
            if (!sensitiveShown.isEmpty()) {
                audit.security(c, who, SecurityEventType.SENSITIVE_READ, Map.of("resource_type", resourceType,
                        "resource_id", ids.get(i), "fields", sensitiveShown));
            }
            out.add(row);
        }
        return out;
    }
}
