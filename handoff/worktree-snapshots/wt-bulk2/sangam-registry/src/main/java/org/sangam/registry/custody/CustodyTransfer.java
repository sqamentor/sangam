// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import java.time.Instant;

/** A transfer of custody: {@code PENDING} (two-step), then {@code ACCEPTED}, {@code REJECTED} or {@code CANCELLED}. */
public record CustodyTransfer(String id, String itemId, String fromCustodian, String toCustodian, String toLocation,
                              String reason, String status, Instant initiatedAt, String initiatedBy,
                              String fromSignatureRef, Instant resolvedAt, String resolvedBy, String toSignatureRef,
                              String conditionText, Boolean sealIntact, String receiptNumber) {
}
