// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** WorkFailRequest: {@code retries} left after this failure; 0 raises an incident. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FailWorkRequest(
        String workerId, long lockVersion, String attemptId, String errorMessage, String errorDetails, int retries, long retryTimeoutMs) {
}
