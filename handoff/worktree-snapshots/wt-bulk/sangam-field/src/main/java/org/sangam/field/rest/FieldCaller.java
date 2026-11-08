// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.rest;

import java.util.Set;

/**
 * Authenticated caller of the field REST port. Tenant and principal come from the verified token (VI.4 rule 6),
 * never from the request body; {@code lang} selects template labels.
 */
public record FieldCaller(String tenantId, String principalId, String seatId, Set<String> groups, String lang) {

    public FieldCaller {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("tenant and principal are required");
        }
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
