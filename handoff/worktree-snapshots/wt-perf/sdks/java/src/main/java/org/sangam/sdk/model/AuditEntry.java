// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** One audit-chain entry: seat, person and acted-for seat (AC-01), hash chain fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditEntry(
        String eventId, String eventClass, String chainId, Long seq, String type, Instant occurredAt, String actor,
        String seatId, String actedForSeatId, String processInstanceId, String activityId, String taskId,
        Object payload, Boolean erased, String hash, Object evidence) {
}
