// SPDX-License-Identifier: Apache-2.0
package org.sangam.backup;

/** A refused or failed console action. Adapters map {@link Kind} to their protocol (400, 404, 409, 502). */
public final class BackupException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT, UPSTREAM }

    private final Kind kind;
    private final String code;

    public BackupException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public BackupException(Kind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
