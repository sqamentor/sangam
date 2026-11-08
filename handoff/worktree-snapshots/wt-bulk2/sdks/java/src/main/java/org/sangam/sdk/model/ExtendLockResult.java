// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** LockState: heartbeat answer with new expiry and {@code cancelRequested} (VI.3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtendLockResult(
        String id, Long lockVersion, Instant lockExpiresAt, boolean cancelRequested) {
}
