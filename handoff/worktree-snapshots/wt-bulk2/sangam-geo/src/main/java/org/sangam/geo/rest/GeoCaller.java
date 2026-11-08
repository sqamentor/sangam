// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.rest;

import java.util.Set;

/** Authenticated caller of the geo REST port; tenant and principal come from the verified token (VI.4 rule 6). */
public record GeoCaller(String tenantId, String principalId, Set<String> groups) {

    public GeoCaller {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("tenant and principal are required");
        }
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
