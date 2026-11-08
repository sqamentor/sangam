// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One logical API call. Build with {@link #get(String)}, {@link #post(String)} etc. */
public final class ApiRequest {

    final String method;
    final String path;
    final Map<String, Object> query = new LinkedHashMap<>();
    final Map<String, String> headers = new LinkedHashMap<>();
    Object body;
    byte[] rawBody;
    String rawContentType;
    String idempotencyKey;
    Duration timeout;
    boolean retry = true;

    private ApiRequest(String method, String path) {
        this.method = method;
        this.path = Objects.requireNonNull(path);
    }

    public static ApiRequest get(String path) { return new ApiRequest("GET", path); }
    public static ApiRequest post(String path) { return new ApiRequest("POST", path); }
    public static ApiRequest put(String path) { return new ApiRequest("PUT", path); }
    public static ApiRequest patch(String path) { return new ApiRequest("PATCH", path); }
    public static ApiRequest delete(String path) { return new ApiRequest("DELETE", path); }

    public ApiRequest query(String name, Object value) {
        if (value != null) query.put(name, value);
        return this;
    }

    public ApiRequest query(Map<String, ?> values) {
        if (values != null) values.forEach(this::query);
        return this;
    }

    public ApiRequest header(String name, String value) {
        if (value != null) headers.put(name, value);
        return this;
    }

    public ApiRequest body(Object body) {
        this.body = body;
        return this;
    }

    public ApiRequest rawBody(byte[] body, String contentType) {
        this.rawBody = body;
        this.rawContentType = contentType;
        return this;
    }

    /** Idempotency-Key header (R41). When null on a mutation the transport generates one per logical call. */
    public ApiRequest idempotencyKey(String key) {
        this.idempotencyKey = key;
        return this;
    }

    public ApiRequest timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    /** Disable transport-level retries (used by long-poll fetch, which has its own loop). */
    public ApiRequest noRetry() {
        this.retry = false;
        return this;
    }

    boolean isMutation() {
        return !method.equals("GET") && !method.equals("HEAD");
    }
}
