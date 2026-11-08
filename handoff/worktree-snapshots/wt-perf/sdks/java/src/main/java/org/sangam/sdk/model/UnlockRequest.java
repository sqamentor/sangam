// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** WorkUnlockRequest / CancelAckRequest of {@code /unlock} and {@code /cancel-ack}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnlockRequest(
        String workerId, long lockVersion, String attemptId) {
}
