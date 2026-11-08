// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import java.time.Instant;
import java.util.Objects;

/**
 * Normalised bank / treasury / gateway acknowledgement of a payment (R58, IX.4 P1 bill/payment and P3 disbursement):
 * the asynchronous final status that arrives after the payment instruction was accepted (PFMS status file, IFMS
 * failure feed, payment gateway callback). The integration layer routes it into the inbox, where {@link #messageId()}
 * is the de-duplication key: the same acknowledgement delivered twice (re-polled feed, repeated callback) carries the
 * same messageId and is applied once.
 *
 * @param source      {@code pfms}, {@code ifms} or {@code pg}
 * @param messageId   stable de-duplication key (for PFMS: {@code <statusFileName>#<operationId>})
 * @param tenantId    tenant of the connector instance that received it (VI.4 rule 6: never taken from the payload)
 * @param operationId operationId of the payment (R36), as the downstream echoed it
 * @param providerRef the downstream reference (PFMS ref, IFMS payment ref, gateway ref); may be null
 * @param status      final or interim status
 * @param reason      failure reason code; null on success
 * @param amount      amount as a decimal string (R33); null when the source does not carry it
 * @param receivedAt  when Sangam received it
 */
public record PaymentAcknowledgement(String source, String messageId, String tenantId, String operationId,
                                     String providerRef, Status status, String reason, String amount,
                                     Instant receivedAt) {

    public static final String SOURCE_PFMS = "pfms";
    public static final String SOURCE_IFMS = "ifms";
    public static final String SOURCE_PG = "pg";

    /** Payment status as acknowledged. */
    public enum Status {
        /** Money moved / collected. */
        SUCCESS,
        /** Rejected or returned; no money moved (or it was returned). */
        FAILED,
        /** Not final yet (accepted for processing, awaiting the bank). */
        PENDING;

        /** Maps a downstream status word; anything unknown is {@link #PENDING}. */
        public static Status from(String downstream) {
            if (downstream == null) {
                return PENDING;
            }
            return switch (downstream.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "SUCCESS", "PAID", "SETTLED", "CREDITED" -> SUCCESS;
                case "FAILED", "FAILURE", "REJECTED", "RETURNED", "DECLINED" -> FAILED;
                default -> PENDING;
            };
        }
    }

    public PaymentAcknowledgement {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
    }
}
