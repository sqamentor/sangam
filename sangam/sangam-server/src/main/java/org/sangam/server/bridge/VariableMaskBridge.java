// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.VariableMaskPort;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;

/**
 * Decisions 87 and 98: sangam-rest's variable masks on sangam-security's {@code FieldMaskService}. The field rules of
 * {@code (resourceType, definitionKey)} are read once; each map is checked against its owning instance with the same
 * object predicate as every other permission ({@code READ_VARIABLE} / {@code READ_SENSITIVE}), one map with
 * {@code mask}, several set-based with {@code maskAll} (M-086). Unmasked sensitive reads are audited by the service.
 */
public final class VariableMaskBridge implements VariableMaskPort {

    /** The row id {@code maskAll} needs; not a legal variable name (NUL), removed from every result. */
    static final String ID_FIELD = "\u0000sangam.instance";

    private final SangamSecurity security;
    private final TxSupport tx;

    public VariableMaskBridge(SangamSecurity security, TxSupport tx) {
        this.security = security;
        this.tx = tx;
    }

    @Override
    public List<Map<String, Object>> mask(RequestContext ctx, String resourceType, String definitionKey,
                                          List<String> instanceIds, List<Map<String, Object>> variables) {
        if (instanceIds.size() != variables.size()) {
            throw new IllegalArgumentException("one instance id per variable map");
        }
        if (variables.isEmpty()) {
            return List.of();
        }
        SecurityIdentity who = SecurityIdentities.of(ctx);
        return tx.withConnection(c -> {
            if (variables.size() == 1) {
                List<Map<String, Object>> one = new ArrayList<>(1);
                one.add(security.masks().mask(c, who, resourceType, instanceIds.get(0), definitionKey, variables.get(0)));
                return one;
            }
            List<Map<String, Object>> rows = new ArrayList<>(variables.size());
            for (int i = 0; i < variables.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>(variables.get(i));
                row.put(ID_FIELD, instanceIds.get(i));
                rows.add(row);
            }
            List<Map<String, Object>> out = security.masks().maskAll(c, who, resourceType, definitionKey, rows, ID_FIELD);
            out.forEach(m -> m.remove(ID_FIELD));
            return out;
        });
    }
}
