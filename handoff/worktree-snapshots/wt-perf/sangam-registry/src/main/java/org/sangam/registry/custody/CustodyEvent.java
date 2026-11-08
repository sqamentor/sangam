// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.custody;

import java.time.Instant;
import java.util.Map;

/**
 * One custody fact of an item, hash-chained per item ({@code event_hash = SHA-256(prev_hash || JCS(envelope))}) and
 * mirrored in the core audit chain ({@code auditEventId}).
 */
public record CustodyEvent(String id, String itemId, long seq, String eventType, String fromCustodian,
                           String toCustodian, String locationRef, String conditionText, String sealNo,
                           Boolean sealIntact, String fromSignatureRef, String toSignatureRef, String witnessRef,
                           String receiptNumber, String transferId, Map<String, Object> details, Instant occurredAt,
                           String recordedBy, String prevHash, String eventHash, String auditEventId) {
}
