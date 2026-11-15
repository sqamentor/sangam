// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

/**
 * A SCIM protocol error (RFC 7644 section 3.12): the HTTP status, the optional {@code scimType} and a detail text. The
 * web adapter writes it as a SCIM error body ({@code urn:ietf:params:scim:api:messages:2.0:Error}). Authorization
 * failures are not SCIM errors here: the delegated services throw {@code SangamSecurityException}, which the adapter
 * maps to 403 (and {@code AdminConflict} to 409).
 */
public final class ScimException extends RuntimeException {

    private final int status;
    private final String scimType;

    public ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public int status() {
        return status;
    }

    /** RFC 7644 table 9 keyword, or null when the status alone says it (404, 412). */
    public String scimType() {
        return scimType;
    }

    public String detail() {
        return getMessage();
    }

    static ScimException invalidFilter(String detail) {
        return new ScimException(400, "invalidFilter", detail);
    }

    static ScimException invalidValue(String detail) {
        return new ScimException(400, "invalidValue", detail);
    }

    static ScimException invalidSyntax(String detail) {
        return new ScimException(400, "invalidSyntax", detail);
    }

    static ScimException invalidPath(String detail) {
        return new ScimException(400, "invalidPath", detail);
    }

    static ScimException noTarget(String detail) {
        return new ScimException(400, "noTarget", detail);
    }

    /** Owner decision 154: postings and seats are read-only in SCIM. */
    static ScimException mutability(String detail) {
        return new ScimException(400, "mutability", detail);
    }

    static ScimException uniqueness(String detail) {
        return new ScimException(409, "uniqueness", detail);
    }

    static ScimException notFound(String type, String id) {
        return new ScimException(404, null, type + " " + id + " not found");
    }

    static ScimException versionMismatch(String id) {
        return new ScimException(412, null, "resource " + id + " has changed (If-Match does not match meta.version)");
    }
}
