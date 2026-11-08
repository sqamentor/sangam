// SPDX-License-Identifier: Apache-2.0
package org.sangam.field;

/** Field-module error with a stable code; {@link #status()} is the HTTP hint used by the REST layer (RFC 9457). */
public class FieldException extends RuntimeException {

    private final String code;

    public FieldException(String code, String message) {
        super(message);
        this.code = code;
    }

    public FieldException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int status() {
        return switch (code) {
            case "NOT_FOUND" -> 404;
            case "FORBIDDEN", "DEVICE_REVOKED", "DEVICE_UNKNOWN" -> 403;
            case "CONFLICT", "VERSION_CONFLICT", "IDEMPOTENCY_CONFLICT", "INVALID_TRANSITION", "STALE" -> 409;
            default -> 400;
        };
    }

    public static FieldException notFound(String what, String id) {
        return new FieldException("NOT_FOUND", what + " not found: " + id);
    }
}
