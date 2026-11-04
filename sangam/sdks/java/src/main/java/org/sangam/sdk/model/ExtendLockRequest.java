// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Heartbeat body (VI.3.2 rule 3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtendLockRequest(
        String workerId, long lockVersion, String attemptId, long newDurationMs) {
}
