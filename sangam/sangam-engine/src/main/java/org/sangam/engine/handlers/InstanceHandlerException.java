// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.handlers;

/**
 * M-059: a refused handler declaration (deploy, {@code L6-*} codes) or a refused or failed handler call (run time:
 * {@code VALIDATION_FAILED}, {@code HANDLER_EVALUATION_FAILED}, {@code RESULT_TOO_LARGE}). The code is stable; the
 * REST layer maps it to its problem response.
 */
public class InstanceHandlerException extends RuntimeException {

    private final String code;

    public InstanceHandlerException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public InstanceHandlerException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The message without the leading code. */
    public String detail() {
        String m = getMessage();
        return m.startsWith(code + ": ") ? m.substring(code.length() + 2) : m;
    }
}
