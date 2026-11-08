// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** UNKNOWN_OUTCOME / LATE_RESULT_AFTER_DELETE workbench item (VI.3 rule 4, VI.5). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconciliationItem(
        String id, String kind, String operationId, String rootId, String taskId, String state, Object details,
        String action, String resolvedBy, Object resolution, Instant createdAt, Instant resolvedAt) {
}
