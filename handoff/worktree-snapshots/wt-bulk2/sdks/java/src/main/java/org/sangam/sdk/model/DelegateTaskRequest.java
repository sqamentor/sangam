// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code POST /tasks/{id}/delegate} to a user or a seat; {@code reason} is required. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelegateTaskRequest(
        String toUserId, String toSeatId, String reason) {
}
