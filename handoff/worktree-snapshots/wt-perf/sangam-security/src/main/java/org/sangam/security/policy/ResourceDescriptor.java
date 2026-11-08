// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.policy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How a protected resource type maps onto a table, so the VI.4 predicate can be compiled for it (VI.4 rule 1: only
 * attributes stored as columns can be used by list-visible policies; rule 8: history uses the same resource types
 * through denormalised columns).
 *
 * @param resourceType resource type name stored in {@code AUTH_EFFECTIVE.resource_type}
 * @param table        table holding the resources, or {@code null} for a virtual (admin) resource type that has no
 *                     table; virtual types are object-checked from grant rows only
 * @param tenantColumn tenant column of {@code table}
 * @param idColumn     id column of {@code table}
 * @param baseFilter   optional extra SQL on alias {@code r} that defines the set (e.g. root executions only); may be null
 * @param scopes       broader scopes a grant or deny can name (A-13: any matching deny wins at any scope); a grant row
 *                     with {@code resource_id = prefix || column} covers the resource
 * @param attributes   condition attribute name to column name
 */
public record ResourceDescriptor(String resourceType, String table, String tenantColumn, String idColumn,
                                 String baseFilter, List<Scope> scopes, Map<String, String> attributes) {

    /** A broader scope, e.g. {@code ("procinst:", "PROC_INST_ID_")}: a grant on a process instance covers its tasks. */
    public record Scope(String prefix, String column) {
    }

    public ResourceDescriptor {
        Objects.requireNonNull(resourceType);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ResourceDescriptor virtual(String resourceType) {
        return new ResourceDescriptor(resourceType, null, null, null, null, List.of(), Map.of());
    }

    public boolean hasTable() {
        return table != null;
    }

    public Optional<String> column(String attribute) {
        return Optional.ofNullable(attributes.get(attribute));
    }
}
