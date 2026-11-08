// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.sdk.error.SangamException;
import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.ApiResponse;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.Page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared plumbing for the typed API groups. */
abstract class ApiSupport {

    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    protected final HttpTransport http;

    ApiSupport(HttpTransport http) {
        this.http = http;
    }

    protected <T> T call(ApiRequest req, Class<T> type) {
        return http.execute(req, type).body();
    }

    protected <T> T call(ApiRequest req, TypeReference<T> type) {
        ApiResponse<T> r = http.execute(req, http.json().getTypeFactory().constructType(type));
        return r.body();
    }

    protected <T> List<T> callList(ApiRequest req, Class<T> item) {
        return readPage(req, item).items();
    }

    /** Reads a list endpoint. Accepts a bare JSON array or an object with {@code items}; reads the A-16 count headers. */
    protected <T> Page<T> readPage(ApiRequest req, Class<T> item) {
        ApiResponse<JsonNode> r = http.execute(req, JsonNode.class);
        JsonNode body = r.body();
        JsonNode arr = body == null ? null : body.isArray() ? body : body.get("items");
        JavaType t = http.json().getTypeFactory().constructType(item);
        List<T> items = new ArrayList<>();
        if (arr != null) {
            try {
                for (JsonNode n : arr) items.add(http.json().readerFor(t).readValue(n));
            } catch (IOException e) {
                throw new SangamException("cannot decode list item", e);
            }
        }
        Long total = parseLong(r.header("X-Total-Count"));
        if (total == null && body != null && body.has("totalCount")) total = body.get("totalCount").asLong();
        boolean capped = "true".equalsIgnoreCase(r.header("X-Total-Count-Capped"))
                || (body != null && body.path("totalCountCapped").asBoolean(false));
        int page = intOr(req, "page", body, 0);
        int size = intOr(req, "size", body, items.size());
        return new Page<>(items, page, size, total, capped);
    }

    private static int intOr(ApiRequest req, String name, JsonNode body, int dflt) {
        if (body != null && body.has(name)) return body.get(name).asInt(dflt);
        return dflt;
    }

    private static Long parseLong(String v) {
        if (v == null) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static String seg(String s) {
        return HttpTransport.seg(s);
    }
}
