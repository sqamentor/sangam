// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance;

/**
 * Compliance refusals with a stable code and an HTTP status, for RFC 9457 problem+json mapping by WS-REST. Messages
 * never carry personal data, SQL or stack traces (R28).
 */
public class ComplianceException extends RuntimeException {

    public enum Code {
        /** Processing outside the consented or registered purpose (lawful basis and purpose binding). */
        PURPOSE_NOT_PERMITTED(403),
        /** No valid consent for the purpose . */
        CONSENT_REQUIRED(403),
        /** Verifiable parental / lawful guardian consent missing . */
        GUARDIAN_CONSENT_REQUIRED(403),
        /** Tracking, behavioural monitoring or targeted advertising of a child . */
        CHILD_PROCESSING_FORBIDDEN(403),
        /** A notice is missing a Rule 3 element or contains an invalid clause. */
        NOTICE_INVALID(422),
        /** The requested notice language is not configured. */
        LANGUAGE_NOT_CONFIGURED(422),
        /** Consent artefact from an unregistered Consent Manager. */
        CONSENT_MANAGER_NOT_REGISTERED(422),
        /** The requester could not be authenticated against the published identifier. */
        PRINCIPAL_NOT_AUTHENTICATED(401),
        /** A nominee acted without an invoked nomination. */
        NOMINATION_NOT_ACTIVE(403),
        /** Configured SLA exceeds a legal maximum set by the jurisdiction pack. */
        SLA_EXCEEDS_LEGAL_MAXIMUM(422),
        /** Erasure refused while a legal hold is active (R61, VI.5.1). */
        LEGAL_HOLD(409),
        /** Erasure refused because a law requires retention. */
        RETENTION_REQUIRED_BY_LAW(409),
        /** Transfer to a destination or recipient that is not allowed. */
        TRANSFER_NOT_PERMITTED(403),
        /** Storage location outside the required jurisdiction. */
        LOCALISATION_VIOLATION(422),
        /** A breach / incident report lacks a required item. */
        REPORT_INCOMPLETE(422),
        /** Raw national identifier where only a reference key is allowed (IN: Aadhaar Act s.29, ADV circulars). */
        NATIONAL_ID_NOT_TOKENISED(422),
        /** Maker-checker: the approver is the person who made the entry. */
        FOUR_EYES_REQUIRED(422),
        /** Unknown record. */
        NOT_FOUND(404),
        /** Invalid state transition. */
        INVALID_STATE(409),
        /** Invalid input. */
        INVALID_INPUT(400);

        private final int httpStatus;

        Code(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Code code;

    public ComplianceException(Code code, String message) {
        super(code.name() + ": " + message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public int httpStatus() {
        return code.httpStatus();
    }

    public static ComplianceException notFound(String what, String id) {
        return new ComplianceException(Code.NOT_FOUND, what + " " + id + " not found");
    }

    public static ComplianceException invalid(String message) {
        return new ComplianceException(Code.INVALID_INPUT, message);
    }

    public static void require(boolean condition, Code code, String message) {
        if (!condition) {
            throw new ComplianceException(code, message);
        }
    }
}
