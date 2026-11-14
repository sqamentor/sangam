// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

/**
 * A replay that cannot be trusted stops with one of these (decision 151: fail clearly, never substitute current time
 * or live data).
 */
public final class ReplayException extends RuntimeException {

    /** Why the replay stopped. */
    public enum Code {
        /** The fixture schema, engine or evaluator version is not the one this replay accepts. */
        INCOMPATIBLE_VERSION,
        /** A required part of the fixture is missing or malformed. */
        INCOMPLETE_FIXTURE,
        /** The capture marked the scenario unsupported (uncontrolled nondeterminism). */
        UNSUPPORTED_SCENARIO,
        /** The fixture holds what it must not: a credential-like configuration key. */
        FORBIDDEN_CONTENT,
        /** An inline model does not match its SHA-256. */
        HASH_MISMATCH,
        /** The replay asked for an input the fixture does not hold. */
        MISSING_EVENT,
        /** The replay tried a live external call (connector, payment, message, production write). */
        UNEXPECTED_EXTERNAL_CALL
    }

    private final Code code;

    public ReplayException(Code code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
