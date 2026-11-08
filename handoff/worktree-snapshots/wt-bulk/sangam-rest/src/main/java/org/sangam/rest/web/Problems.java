// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sangam.rest.port.ApiProblem;

/** RFC 9457 problem documents, shared by the controller advice and the servlet filters. */
public final class Problems {

    public static final String MEDIA_TYPE = "application/problem+json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Problems() {
    }

    public static Map<String, Object> body(ApiProblem p, String instance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", p.type());
        m.put("title", p.title() != null ? p.title() : defaultTitle(p.status()));
        m.put("status", p.status());
        m.put("detail", p.detail());
        if (instance != null) {
            m.put("instance", instance);
        }
        m.put("code", p.code());
        String trace = RequestContexts.traceId();
        if (trace != null) {
            m.put("traceId", trace);
        }
        p.extensions().forEach(m::putIfAbsent);
        return m;
    }

    public static byte[] bytes(ApiProblem p, String instance) {
        try {
            return JSON.writeValueAsBytes(body(p, instance));
        } catch (IOException e) {
            return ("{\"status\":" + p.status() + ",\"code\":\"" + p.code() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Writes a problem straight to the servlet response (filters, before the dispatcher). */
    public static void write(HttpServletRequest req, HttpServletResponse res, ApiProblem p) throws IOException {
        res.setStatus(p.status());
        res.setContentType(MEDIA_TYPE);
        if (p.retryAfterSeconds() != null) {
            res.setHeader("Retry-After", Integer.toString(p.retryAfterSeconds()));
        }
        res.getOutputStream().write(bytes(p, req.getRequestURI()));
    }

    /** Reads the {@code code} field of a problem body, or null. */
    public static String codeOf(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            var node = JSON.readTree(body);
            return node.hasNonNull("code") ? node.get("code").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String defaultTitle(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Unauthenticated";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 412 -> "Precondition failed";
            case 413 -> "Payload too large";
            case 415 -> "Unsupported media type";
            case 422 -> "Unprocessable content";
            case 428 -> "Precondition required";
            case 429 -> "Too many requests";
            case 501 -> "Not implemented";
            case 503 -> "Service unavailable";
            default -> status >= 500 ? "Internal error" : "Error";
        };
    }
}
