// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Audit timeline and verification (R42 / X.2.3). Class-B events are evidence (hash-chained per root); class-A history
 * entries can be merged into the timeline for context and are marked {@code evidence=false} (rule D-0).
 * Every audit read is itself audited ({@code AUDIT_VIEWED}); export writes {@code AUDIT_EXPORTED}.
 */
public interface AuditPort {

    /**
     * eventClass: B (mandatory audit) | A (operational history). For class B, {@code prevHash} and {@code entryHash}
     * (hex) let a client re-check the linkage: {@code entryHash_n = SHA-256(prevHash_n || envelope_n)} and
     * {@code prevHash_n == entryHash_(n-1)}, with {@code prevHash_1 = SHA-256("sangam-audit-v1:" + tenant + ":" + chain)}
     * (X.2.3.4). {@code envelope} is the canonical envelope as stored. {@code hash} equals {@code entryHash}. Class A
     * entries carry no hashes.
     */
    record AuditEntry(String eventId, String eventClass, String chainId, Long seq, String type, Instant occurredAt,
                      String actor, String seatId, String actedForSeatId, String processInstanceId,
                      String activityId, String taskId, Map<String, Object> payload, boolean erased, String hash,
                      boolean evidence, String prevHash, String entryHash, String envelope) {
    }

    record AuditFilter(String chainId, String type, String actor, Instant from, Instant to) {
    }

    record ChainVerification(String chainId, boolean ok, long eventsChecked, Long firstBadSeq, String reason) {
    }

    record VerifyResult(String tenantId, boolean ok, Instant verifiedAt, List<ChainVerification> chains) {
    }

    Page<AuditEntry> timeline(RequestContext ctx, String businessKey, boolean includeHistory, Page.PageRequest page);

    Page<AuditEntry> search(RequestContext ctx, AuditFilter filter, Page.PageRequest page);

    /** Verifies one chain, the chains of one business key, or (both null) every chain of the tenant. */
    VerifyResult verify(RequestContext ctx, String chainId, String businessKey);

    /** format: json | csv. */
    Download export(RequestContext ctx, String businessKey, String format);

    /** Appends a class-B event to {@code chainId} in the caller's (command) transaction. */
    String record(RequestContext ctx, String chainId, String eventType, Map<String, ?> payload);
}
