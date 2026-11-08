// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * ReconciliationResolveRequest (VI.3.2 rule 4): {@code CONFIRM_SUCCESS} (with result variables),
 * {@code RETRY_SAME_OPERATION} or {@code MARK_FAILED}; {@code evidence} is required.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolveReconciliationRequest(
        String action, String evidence, Map<String, Object> result, String reason) {
}
