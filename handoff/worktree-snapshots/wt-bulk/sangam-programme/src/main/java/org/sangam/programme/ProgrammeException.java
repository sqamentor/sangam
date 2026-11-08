// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme;

/**
 * Programme-module refusals with a stable code and an HTTP status, for RFC 9457 problem+json mapping by WS-REST.
 * Messages never carry personal data, SQL or stack traces (R28).
 */
public class ProgrammeException extends RuntimeException {

    public enum Code {
        NOT_FOUND(404),
        INVALID_INPUT(400),
        INVALID_CONFIG(422),
        INVALID_STATE(409),
        /** Optimistic row_version mismatch (concurrent edit). */
        CONFLICT(409),
        /** Maker-checker: the approver is the person who proposed or submitted. */
        FOUR_EYES_REQUIRED(422),
        /** Allocation of a target to child nodes exceeds the parent target. */
        ALLOCATION_EXCEEDS_PARENT(422),
        /** The return has blocking validation findings. */
        VALIDATION_FAILED(422),
        /** The period is frozen or closed for the requested change. */
        PERIOD_LOCKED(409),
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

    public ProgrammeException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public int status() {
        return code.status();
    }

    public static ProgrammeException notFound(String what, String id) {
        return new ProgrammeException(Code.NOT_FOUND, what + " not found: " + id);
    }

    public static ProgrammeException invalid(String message) {
        return new ProgrammeException(Code.INVALID_INPUT, message);
    }

    public static ProgrammeException state(String message) {
        return new ProgrammeException(Code.INVALID_STATE, message);
    }
}
