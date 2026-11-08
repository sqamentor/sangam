// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile;

/**
 * Case-file, meeting and reference refusals with a stable code and an HTTP status, for RFC 9457 problem+json mapping by
 * WS-REST. Messages never carry personal data, SQL or stack traces (R28).
 */
public class CaseFileException extends RuntimeException {

    public enum Code {
        NOT_FOUND(404),
        INVALID_INPUT(400),
        INVALID_CONFIG(422),
        INVALID_STATE(409),
        /** The row changed since it was read (optimistic {@code row_version}). */
        CONCURRENT_UPDATE(409),
        /** A party already appears on the case, or in a role the case type declares conflicting. */
        PARTY_CONFLICT(409),
        /** Maker-checker: the approver is the person who drafted (K5). */
        FOUR_EYES_REQUIRED(422),
        /** The meeting cannot proceed or decide without quorum. */
        QUORUM_NOT_MET(422),
        /** Notice period shorter than the committee's terms and no urgency reason. */
        NOTICE_PERIOD(422),
        /** The case is merged, closed or frozen. */
        CASE_CLOSED(409),
        ACCESS_DENIED(403);

        private final int status;

        Code(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Code code;

    public CaseFileException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public int status() {
        return code.status();
    }

    public static CaseFileException notFound(String what, String id) {
        return new CaseFileException(Code.NOT_FOUND, what + " not found: " + id);
    }

    public static CaseFileException invalid(String message) {
        return new CaseFileException(Code.INVALID_INPUT, message);
    }

    public static CaseFileException state(String message) {
        return new CaseFileException(Code.INVALID_STATE, message);
    }
}
