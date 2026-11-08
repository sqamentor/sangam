// SPDX-License-Identifier: Apache-2.0
package org.sangam.resources.support;

/**
 * Errors of the resources module, with a stable machine code (mapped to RFC 9457 problem types by the REST layer:
 * {@code NOT_FOUND} 404, {@code CONFLICT}/{@code UNAVAILABLE}/{@code STALE} 409, {@code NOT_ENTITLED}/{@code RULE}/
 * {@code CONSTRAINT} 422, {@code INVALID} 400). Messages never contain SQL or stack traces (R28).
 */
public class ResourcesException extends RuntimeException {

    public enum Code { NOT_FOUND, CONFLICT, UNAVAILABLE, STALE, NOT_ENTITLED, RULE, INVALID, CONSTRAINT }

    private final Code code;
    private final String reason;

    public ResourcesException(Code code, String reason, String message) {
        super(message);
        this.code = code;
        this.reason = reason;
    }

    public Code code() {
        return code;
    }

    /** Machine-readable reason, e.g. {@code SLOT_TAKEN}, {@code HOLD_EXPIRED}, {@code REST_HOURS}. */
    public String reason() {
        return reason;
    }

    public int httpStatus() {
        return switch (code) {
            case NOT_FOUND -> 404;
            case CONFLICT, UNAVAILABLE, STALE -> 409;
            case NOT_ENTITLED, RULE, CONSTRAINT -> 422;
            case INVALID -> 400;
        };
    }

    public static ResourcesException notFound(String what, String id) {
        return new ResourcesException(Code.NOT_FOUND, "NOT_FOUND", what + " not found: " + id);
    }

    public static ResourcesException invalid(String reason, String message) {
        return new ResourcesException(Code.INVALID, reason, message);
    }

    public static ResourcesException conflict(String reason, String message) {
        return new ResourcesException(Code.CONFLICT, reason, message);
    }

    public static ResourcesException unavailable(String reason, String message) {
        return new ResourcesException(Code.UNAVAILABLE, reason, message);
    }

    public static ResourcesException stale(String message) {
        return new ResourcesException(Code.STALE, "STALE_VERSION", message);
    }

    public static ResourcesException notEntitled(String reason, String message) {
        return new ResourcesException(Code.NOT_ENTITLED, reason, message);
    }

    public static ResourcesException rule(String reason, String message) {
        return new ResourcesException(Code.RULE, reason, message);
    }

    public static ResourcesException constraint(String reason, String message) {
        return new ResourcesException(Code.CONSTRAINT, reason, message);
    }
}
