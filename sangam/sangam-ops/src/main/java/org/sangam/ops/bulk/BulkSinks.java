// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.bulk;

import org.sangam.ops.vars.ScopedVariables;

import java.sql.Connection;
import java.util.Map;

/** Built-in bulk sinks. Modules register their own {@link BulkIngest.Sink} beans for their object types. */
public final class BulkSinks {

    public static final String VARIABLE = "ops.variable";

    private BulkSinks() {
    }

    /**
     * {@value #VARIABLE}: tenant variables. External id = variable name; data {@code {"value": any, "reason": text?}}.
     * Needs {@code ops.tenant.admin}, the permission of a single variable write.
     */
    public static BulkIngest.Sink variables(ScopedVariables vars, String permission) {
        return new BulkIngest.Sink() {
            @Override
            public String objectType() {
                return VARIABLE;
            }

            @Override
            public String permission() {
                return permission;
            }

            @Override
            public String apply(Connection c, String tenant, String actor, String externalId, Map<String, Object> data, String existingRef) {
                if (!data.containsKey("value") || data.get("value") == null) {
                    throw new BulkIngest.RowRejectedException("VALUE_REQUIRED", "data.value is required");
                }
                Object reason = data.get("reason");
                try {
                    vars.set(c, ScopedVariables.tenantScope(tenant), externalId, data.get("value"), actor == null ? "bulk" : actor,
                            reason == null ? "bulk ingest" : String.valueOf(reason));
                } catch (IllegalArgumentException e) {
                    throw new BulkIngest.RowRejectedException("INVALID_VARIABLE", e.getMessage());
                }
                return "variable:" + externalId;
            }

            @Override
            public void committed(String tenant, String externalId) {
                vars.evict(ScopedVariables.tenantScope(tenant));
            }
        };
    }
}
