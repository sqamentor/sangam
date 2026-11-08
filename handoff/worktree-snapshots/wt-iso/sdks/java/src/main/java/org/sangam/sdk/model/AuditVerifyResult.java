// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** {@code GET /audit/verify}: whether the hash chains are intact. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditVerifyResult(
        String tenantId, boolean ok, Instant verifiedAt, List<Object> chains) {
}
