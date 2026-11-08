// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A business or protocol error that becomes an RFC 9457 {@code application/problem+json} response.
 * {@code code} is the stable machine code (e.g. {@code STALE_LEASE}); the problem {@code type} URI is derived from it
 * ({@code https://sangam.dev/problems/stale-lease}). Adapters throw this; controllers never build error bodies.
 */
public class ApiProblem extends RuntimeException {

    public static final String TYPE_BASE = "https://sangam.dev/problems/";

    private final int status;
    private final String code;
    private final String title;
    private final Map<String, Object> extensions;
    private final Integer retryAfterSeconds;

    public ApiProblem(int status, String code, String title, String detail, Map<String, Object> extensions,
                      Integer retryAfterSeconds) {
        super(detail == null ? code : detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ApiProblem(int status, String code, String detail) {
        this(status, code, null, detail, null, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return getMessage();
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Problem type URI derived from the code: {@code STALE_LEASE} becomes {@code .../stale-lease}. */
    public String type() {
        return typeOf(code);
    }

    public static String typeOf(String code) {
        return TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public ApiProblem with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(extensions);
        if (value != null) {
            m.put(key, value);
        }
        return new ApiProblem(status, code, title, getMessage(), m, retryAfterSeconds);
    }

    public static ApiProblem badRequest(String code, String detail) {
        return new ApiProblem(400, code, detail);
    }

    public static ApiProblem forbidden(String code, String detail) {
        return new ApiProblem(403, code, detail);
    }

    /** 403 ACTION_NOT_PERMITTED (X.4.0.4, VI.4 rule 5). */
    public static ApiProblem notPermitted(String action) {
        return new ApiProblem(403, "ACTION_NOT_PERMITTED", "You do not hold the permission for '" + action + "'");
    }

    public static ApiProblem notFound(String what, String id) {
        return new ApiProblem(404, "NOT_FOUND", what + " " + id + " was not found or is not visible to you")
                .with("resource", what);
    }

    public static ApiProblem conflict(String code, String detail) {
        return new ApiProblem(409, code, detail);
    }

    public static ApiProblem gone(String code, String detail) {
        return new ApiProblem(410, code, detail);
    }

    public static ApiProblem unprocessable(String code, String detail) {
        return new ApiProblem(422, code, detail);
    }

    public static ApiProblem unavailable(String code, String detail, int retryAfterSeconds) {
        return new ApiProblem(503, code, null, detail, null, retryAfterSeconds);
    }

    /** 501: the capability is provided by a module that is not installed in this server. */
    public static ApiProblem notInstalled(String capability, String module) {
        return new ApiProblem(501, "CAPABILITY_NOT_INSTALLED", null,
                capability + " is provided by " + module + ", which is not installed in this server",
                Map.of("capability", capability, "module", module), null);
    }

    /** VI.3 rule 2: a stale (taskId, lockVersion, attemptId) changes nothing. */
    public static ApiProblem staleLease(String workId, String reason, String detail) {
        return new ApiProblem(409, "STALE_LEASE", "Stale lease", detail, Map.of("workId", workId, "reason", reason), null);
    }
}
