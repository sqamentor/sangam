// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * WorkFailRequest: {@code retries} left after this failure; 0 raises an incident. errorMessage, retries and
 * retryTimeoutMs are optional in the contract (null = not sent, the server default applies).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FailWorkRequest(
        String workerId, long lockVersion, String attemptId, String errorMessage, String errorDetails, Integer retries,
        Long retryTimeoutMs) {
}
