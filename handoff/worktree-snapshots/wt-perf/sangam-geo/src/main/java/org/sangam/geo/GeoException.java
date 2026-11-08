// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo;

/** Geo error with a stable code (mapped to problem+json by the REST layer). */
public class GeoException extends RuntimeException {

    private final String code;

    public GeoException(String code, String message) {
        super(message);
        this.code = code;
    }

    public GeoException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** HTTP status hint for the REST layer. */
    public int status() {
        return switch (code) {
            case "NOT_FOUND" -> 404;
            case "FORBIDDEN" -> 403;
            case "GIS_UNAVAILABLE", "NO_CONNECTOR" -> 503;
            case "CONFLICT", "VERSION_CONFLICT" -> 409;
            default -> 400;
        };
    }
}
