// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP answer produced by {@link IdempotencyService}: either the command's own response or a replay / refusal with the
 * headers of X.2.2.4 ({@code Idempotent-Replayed}, {@code Idempotency-Key-Expires}, {@code Retry-After},
 * {@code Location}). Error bodies are RFC 9457 {@code application/problem+json}.
 */
public record IdempotentResponse(int status, byte[] body, String contentType, Map<String, String> headers) {

    public IdempotentResponse {
        body = body == null ? new byte[0] : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static IdempotentResponse problem(int status, String code, String detail, Map<String, String> headers) {
        String json = "{\"type\":\"urn:sangam:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + "\",\"title\":\"" + code + "\",\"status\":" + status + ",\"code\":\"" + code + "\""
                + (detail == null ? "" : ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"") + "}";
        return new IdempotentResponse(status, json.getBytes(StandardCharsets.UTF_8), "application/problem+json", headers);
    }

    public IdempotentResponse withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new IdempotentResponse(status, body, contentType, h);
    }

    public boolean replayed() {
        return headers.containsKey("Idempotent-Replayed");
    }
}
