// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry;

/** A registry rule violation, with a stable code that the REST layer maps to an RFC 9457 problem type and status. */
public class RegistryException extends RuntimeException {

    /** Error codes and the HTTP status they map to. */
    public enum Code {
        NOT_FOUND(404), INVALID(400), CONFLICT(409), STATE(409), FORBIDDEN(403), CONSENT_REQUIRED(403), CLOSED(409);

        private final int status;

        Code(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Code code;

    public RegistryException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static RegistryException notFound(String what, String id) {
        return new RegistryException(Code.NOT_FOUND, what + " not found: " + id);
    }

    public static RegistryException invalid(String message) {
        return new RegistryException(Code.INVALID, message);
    }

    public static RegistryException state(String message) {
        return new RegistryException(Code.STATE, message);
    }

    public static RegistryException conflict(String message) {
        return new RegistryException(Code.CONFLICT, message);
    }

    public static RegistryException forbidden(String message) {
        return new RegistryException(Code.FORBIDDEN, message);
    }
}
