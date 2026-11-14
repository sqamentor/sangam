// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owner decision 227: the enrolled DSC certificates. One row per tenant and certificate (SHA-256 of the DER); a
 * certificate has at most one live enrolment (PENDING or APPROVED). A REJECTED or REVOKED certificate can be enrolled
 * again, which starts a new enrolment id and a new approval. The history of every change is in the audit trail.
 */
public interface DscCertificateEnrolments {

    enum Status { PENDING, APPROVED, REJECTED, REVOKED }

    /** The statuses that hold the certificate: it cannot be enrolled again while in one of them. */
    Set<Status> LIVE = Set.of(Status.PENDING, Status.APPROVED);

    /**
     * One enrolment. {@code issuer}, {@code serialHex} and {@code subject} are derived by the server from the DER and
     * shown to the approver; the binding is {@code certificateSha256}. {@code subject} is shown, never trusted.
     *
     * <p>{@code approvalSeq} (integrator ruling on decision 241): the certificate row's approval counter. Every move to
     * APPROVED raises it by one and nothing else changes it, also not a new enrolment of the same certificate, so
     * ({@code enrolmentId}, {@code approvalSeq}) names exactly one approval. A prepared signature stores that pair and
     * counts only while the row still carries it with status APPROVED.
     */
    record Enrolment(String tenantId, String enrolmentId, String principalId, String certificateSha256, String issuer,
                     String serialHex, String subject, Instant notBefore, Instant notAfter, byte[] certificate,
                     Status status, Instant enrolledAt, String decidedBy, Instant decidedAt, String decisionReason,
                     long approvalSeq) {

        public Enrolment {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(enrolmentId, "enrolmentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(certificateSha256, "certificateSha256");
            Objects.requireNonNull(status, "status");
            if (approvalSeq < 0) {
                throw new IllegalArgumentException("approvalSeq is never negative");
            }
            certificate = certificate == null ? null : certificate.clone();
        }

        /** A new enrolment that has never been approved ({@code approvalSeq} 0; a stored row keeps its own counter). */
        public Enrolment(String tenantId, String enrolmentId, String principalId, String certificateSha256, String issuer,
                         String serialHex, String subject, Instant notBefore, Instant notAfter, byte[] certificate,
                         Status status, Instant enrolledAt, String decidedBy, Instant decidedAt, String decisionReason) {
            this(tenantId, enrolmentId, principalId, certificateSha256, issuer, serialHex, subject, notBefore, notAfter,
                    certificate, status, enrolledAt, decidedBy, decidedAt, decisionReason, 0L);
        }

        @Override
        public byte[] certificate() {
            return certificate == null ? null : certificate.clone();
        }

        /** The same enrolment after a decision; a move to APPROVED raises {@code approvalSeq} by one. */
        public Enrolment decided(Status to, String by, Instant at, String reason) {
            return new Enrolment(tenantId, enrolmentId, principalId, certificateSha256, issuer, serialHex, subject,
                    notBefore, notAfter, certificate, to, enrolledAt, by, at, reason,
                    to == Status.APPROVED ? approvalSeq + 1 : approvalSeq);
        }

        /** The same certificate row enrolled again under {@code next}: the approval counter carries on. */
        public Enrolment reenrolled(Enrolment next) {
            return new Enrolment(next.tenantId, next.enrolmentId, next.principalId, next.certificateSha256, next.issuer,
                    next.serialHex, next.subject, next.notBefore, next.notAfter, next.certificate, next.status,
                    next.enrolledAt, null, null, null, approvalSeq);
        }
    }

    /**
     * Stores a PENDING enrolment: a new row, or a new enrolment of a certificate whose row is REJECTED or REVOKED (the
     * row keeps its {@code approvalSeq}).
     *
     * @return false when the certificate already has a live (PENDING or APPROVED) enrolment in the tenant
     */
    boolean enrol(Enrolment pending);

    Optional<Enrolment> byId(String tenantId, String enrolmentId);

    Optional<Enrolment> byCertificate(String tenantId, String certificateSha256);

    /** The principal's enrolments, newest first. */
    List<Enrolment> ofPrincipal(String tenantId, String principalId);

    /** The tenant's enrolments in one status, oldest first, at most {@code limit}. */
    List<Enrolment> withStatus(String tenantId, Status status, int limit);

    /**
     * Moves an enrolment from one of {@code from} to {@code to}, atomically; a move to APPROVED also raises the row's
     * {@code approvalSeq} by one in the same statement.
     *
     * @return false when the enrolment is unknown or not in one of {@code from} (someone decided first)
     */
    boolean decide(String tenantId, String enrolmentId, Set<Status> from, Status to, String decidedBy, Instant at,
                   String reason);
}
