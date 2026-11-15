// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.integration.connector.Json;

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

    /** The problem document, written by the JSON serialiser so that every value is escaped (control characters too). */
    public static IdempotentResponse problem(int status, String code, String detail, Map<String, String> headers) {
        ObjectNode json = Json.MAPPER.createObjectNode();
        json.put("type", "urn:sangam:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        json.put("title", code);
        json.put("status", status);
        json.put("code", code);
        if (detail != null) {
            json.put("detail", detail);
        }
        return new IdempotentResponse(status, Json.write(json).getBytes(StandardCharsets.UTF_8), "application/problem+json",
                headers);
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
