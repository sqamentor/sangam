// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.port;

/**
 * Whether a verified DSC signature exists for a task (decisions 140, 165, 227 and 237). {@code sangam-server} installs
 * an adapter over the server's DSC signing flow, which answers true only for a signature the server verified as
 * {@code VERIFIED_VALID}, made by {@code principal} with their approved enrolled certificate, over exactly
 * {@code documentId}, at task {@code taskId}. A signing outcome reported by a client, a variable or a connector answer
 * never stands in for it.
 */
@FunctionalInterface
public interface SignatureVerifications {

    /** The default when no adapter is installed: nothing is verified, so a step that needs a DSC signature is refused. */
    SignatureVerifications DENY = (tenantId, taskId, documentId, principal) -> false;

    /**
     * @param principal the person completing the task (decision 237: the signer must be the completer)
     * @return true only when the server holds a {@code VERIFIED_VALID} DSC signature of {@code documentId} made by
     *         {@code principal} at task {@code taskId} of tenant {@code tenantId}
     */
    boolean hasVerifiedSignature(String tenantId, String taskId, String documentId, String principal);

    /**
     * The state of the DSC signature of {@code documentId} by {@code principal} at task {@code taskId} (decision 241).
     * Only {@link #VERIFIED} allows a signed transition.
     */
    enum Status {
        /** The server verified the signature as {@code VERIFIED_VALID} with the signer's approved enrolment. */
        VERIFIED,
        /** The document was signed, but the enrolment that signed it was revoked after signing. */
        ENROLMENT_REVOKED,
        /** No verified signature (the default answer for anything else). */
        NOT_SIGNED
    }

    /**
     * The signature's status (decision 241). The default answers from {@link #hasVerifiedSignature}: {@link
     * Status#VERIFIED} or {@link Status#NOT_SIGNED}; an adapter that can tell a revoked enrolment overrides it. It must
     * answer {@link Status#VERIFIED} exactly when {@link #hasVerifiedSignature} answers true.
     */
    default Status status(String tenantId, String taskId, String documentId, String principal) {
        return hasVerifiedSignature(tenantId, taskId, documentId, principal) ? Status.VERIFIED : Status.NOT_SIGNED;
    }
}
