// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sangam.core.layer.SqlIdentifier;

/**
 * The table plans and the constant statement text of {@link CellTenantMover} (split from it: M-098 class size).
 * Everything here that ends up in statement text is a constant or a validated identifier.
 */
final class CellTenantPlans {

    private CellTenantPlans() {
    }

    /** The columns of a shared-profile export row, in the order of {@link #SHARED_PROFILE_SELECT}. */
    static final List<String> SHARED_PROFILE_COLUMNS = List.of("profile_id", "version", "layer_id", "status",
            "effective_from", "effective_to", "content_hash", "document_json", "updated_at");

    /** One constant statement; the profile id is bound. {@code BridgeSqlTextTest} pins it to the column list. */
    static final String SHARED_PROFILE_SELECT = "SELECT profile_id, version, layer_id, status, effective_from,"
            + " effective_to, content_hash, document_json, updated_at FROM CFG_PROFILE WHERE profile_id=? AND status='ACTIVE'";

    /**
     * One table to move: its real name, columns, tenant filter and FK/PK metadata.
     *
     * <p>The table, column and primary-key names are read from {@code DatabaseMetaData} and go into statement text
     * (SQL cannot bind identifiers). The one constructor takes each of them through {@link SqlIdentifier}, so no plan
     * exists with a name that is more than one plain identifier; a table whose metadata holds anything else is refused
     * before any statement is built from it. {@code where} is built by {@code CellTenantMover.plans} from these names,
     * from parent-table names validated the same way and from constants. {@code columns} and {@code pk} are
     * unmodifiable.
     */
    record Plan(String table, String upper, List<String> columns, Map<String, Integer> sqlTypes,
                String where, int whereParams, List<String> pk, Set<String> nullableFks,
                Set<String> requiredParents) {
        Plan {
            table = SqlIdentifier.of(table).sql();
            columns = SqlIdentifier.names(columns);
            pk = SqlIdentifier.names(pk);
        }
    }
}
