// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.dsc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner decision 165: the server's record of one prepared DSC signature, bound to tenant, task, document version,
 * signer principal, signer certificate and the server-issued {@code prepareId}. An entry expires, is used at most once
 * ({@link #consume} is atomic) and keeps its outcome for the audit and for the task's completion check.
 */
public interface DscPreparedStore {

    /**
     * One prepared signature.
     *
     * @param documentVersion        the document version that was prepared; the signed PDF becomes the next one
     * @param documentSha256         SHA-256 of that version's content (lower-case hex)
     * @param principalId            the signer: the authenticated principal who prepared it
     * @param signerCertificateSha256 the certificate chosen on the agent (lower-case hex)
     * @param enrolmentId            the signer's APPROVED enrolment of that certificate, checked at prepare
     * @param enrolmentApprovalSeq   that enrolment's {@code approvalSeq} at prepare; complete and the DSC gate require
     *                               the same enrolment still APPROVED with exactly this value (decision 241)
     * @param intendedSignerName     the name the signer certificate shows (common name, else subject); shown in the
     *                               record only, never trusted (owner decision 227: the binding is
     *                               {@code signerCertificateSha256} of an approved enrolment)
     * @param preparedPdf            the prepared PDF; dropped once the entry has an outcome
     */
    record Entry(String tenantId, String prepareId, String taskId, String documentId, int documentVersion,
                 String documentSha256, String principalId, String signerCertificateSha256, String enrolmentId,
                 long enrolmentApprovalSeq, String intendedSignerName, String algorithm, String preparedDigestSha256,
                 byte[] signedAttributes, Instant signingTime, byte[] preparedPdf, Instant createdAt,
                 Instant expiresAt) {

        public Entry {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(prepareId, "prepareId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(signerCertificateSha256, "signerCertificateSha256");
            Objects.requireNonNull(enrolmentId, "enrolmentId");
            Objects.requireNonNull(intendedSignerName, "intendedSignerName");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(preparedDigestSha256, "preparedDigestSha256");
            signedAttributes = Objects.requireNonNull(signedAttributes, "signedAttributes").clone();
            Objects.requireNonNull(signingTime, "signingTime");
            preparedPdf = preparedPdf == null ? null : preparedPdf.clone();
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public byte[] signedAttributes() {
            return signedAttributes.clone();
        }

        @Override
        public byte[] preparedPdf() {
            return preparedPdf == null ? null : preparedPdf.clone();
        }
    }

    /** Stores a new entry (the prepareId is new). */
    void save(Entry entry);

    /**
     * Marks the entry used and returns it, only when it exists for this tenant, task and principal, is unused and has
     * not expired at {@code now}. Two concurrent calls never both succeed. Empty otherwise (unknown, expired, used or
     * bound to someone else are not told apart).
     */
    Optional<Entry> consume(String tenantId, String prepareId, String taskId, String principalId, Instant now);

    /**
     * Records the verification outcome of a consumed entry and drops its prepared PDF.
     *
     * @param signedDocumentVersion the stored version of the signed PDF (VERIFIED_VALID only), or null
     */
    void recordOutcome(String tenantId, String prepareId, String outcome, String reasonCode,
                       Integer signedDocumentVersion, Instant at);

    /**
     * A recorded VERIFIED_VALID signature whose signed PDF was stored: the evidence the DSC gate checks, with the
     * enrolment approval it was bound to at prepare.
     */
    record VerifiedSignature(String prepareId, String signerCertificateSha256, String enrolmentId,
                             long enrolmentApprovalSeq) {
        public VerifiedSignature {
            Objects.requireNonNull(prepareId, "prepareId");
            Objects.requireNonNull(signerCertificateSha256, "signerCertificateSha256");
            Objects.requireNonNull(enrolmentId, "enrolmentId");
        }
    }

    /**
     * Owner decisions 236 and 237: the VERIFIED_VALID signatures that {@code principalId} made of exactly this document
     * in this task, whose signed PDF was stored as a new version. Another principal's, another document's or another
     * task's are not returned; any null argument gives an empty list. Whether each still counts (decision 241) is
     * decided by {@link DscSigningFlow#hasVerifiedSignature}; this evidence is never changed.
     */
    List<VerifiedSignature> verifiedSignatures(String tenantId, String taskId, String documentId, String principalId);

    /** Deletes unused entries that expired before {@code before}; returns how many. */
    int purgeExpired(Instant before);
}
