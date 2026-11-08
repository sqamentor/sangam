// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.support;

/**
 * A rejected input with a stable problem code (FLOWABLE-WEAKNESS-AUDIT, robustness guards). It is an
 * {@link IllegalArgumentException}, so existing callers (sangam-server {@code PortsErrors}: 422 {@code VALIDATION_FAILED})
 * keep working; a caller that knows this type maps {@link #code()} and {@link #status()} directly.
 *
 * <p>Codes used by the ports: {@code PAYLOAD_TOO_LARGE} (413), {@code MODEL_TOO_LARGE} (413), {@code INVALID_INPUT} (422),
 * {@code FENCING_TOKEN_REQUIRED} (409), {@code TENANT_MISMATCH} (403), {@code RESERVED_VARIABLE} (422),
 * {@code EXPRESSION_NOT_ALLOWED} (422), {@code LIMIT_EXCEEDED} (422), {@code PAGE_TOO_DEEP} (422),
 * {@code NOT_SANDBOX} (403), {@code FRAGMENT_INVALID} (422).
 */
public class PortsProblem extends IllegalArgumentException {

    private final String code;
    private final int status;

    public PortsProblem(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public PortsProblem(String code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static PortsProblem invalid(String message) {
        return new PortsProblem("INVALID_INPUT", 422, message);
    }

    public static PortsProblem tooLarge(String code, String what, long size, long max) {
        return new PortsProblem(code, 413, what + " is " + size + " bytes; the limit is " + max);
    }

    /** Throws {@code INVALID_INPUT} when {@code s} is null/blank or longer than {@code max}. */
    public static String requireText(String s, String field, int max) {
        if (s == null || s.isBlank()) {
            throw invalid(field + " is required");
        }
        if (s.length() > max) {
            throw invalid(field + " is longer than " + max + " characters");
        }
        return s;
    }

    /** Variable names the engine or the ports interpret as switches; external input must never set them. */
    public static boolean isReservedVariable(String name) {
        return name != null && (name.startsWith("_SANGAM_") || name.startsWith("_FLOWABLE_") || name.startsWith("sangam.")
                || name.equals("_cmmnTerminate"));
    }
}
