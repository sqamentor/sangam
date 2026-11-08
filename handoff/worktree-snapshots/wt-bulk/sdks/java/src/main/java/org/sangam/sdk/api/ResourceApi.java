// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.api;

import org.sangam.sdk.http.ApiRequest;
import org.sangam.sdk.http.HttpTransport;
import org.sangam.sdk.model.Page;

import java.util.Map;

/**
 * Generic CRUD over one admin/ops collection (for example {@code /admin/seats}). Bodies are JSON objects
 * ({@code Map<String, Object>}) so the SDK stays compatible while the admin schemas evolve.
 */
public final class ResourceApi extends ApiSupport {

    private final String path;

    ResourceApi(HttpTransport http, String path) {
        super(http);
        this.path = path;
    }

    public String path() {
        return path;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Page<Map<String, Object>> list(Map<String, ?> filters, Integer page, Integer size) {
        Page raw = readPage(ApiRequest.get(path).query(filters).query("page", page).query("size", size), Map.class);
        return (Page<Map<String, Object>>) raw;
    }

    public Page<Map<String, Object>> list() {
        return list(null, null, null);
    }

    public Map<String, Object> get(String id) {
        return call(ApiRequest.get(path + "/" + seg(id)), MAP);
    }

    public Map<String, Object> create(Map<String, ?> body, String idempotencyKey) {
        return call(ApiRequest.post(path).body(body).idempotencyKey(idempotencyKey), MAP);
    }

    public Map<String, Object> update(String id, Map<String, ?> body, String idempotencyKey) {
        return call(ApiRequest.put(path + "/" + seg(id)).body(body).idempotencyKey(idempotencyKey), MAP);
    }

    public void delete(String id, String idempotencyKey) {
        call(ApiRequest.delete(path + "/" + seg(id)).idempotencyKey(idempotencyKey), Void.class);
    }

    /** {@code POST <path>/<id>/<action>}, for example {@code /ops/jobs/{id}/retry}. */
    public Map<String, Object> action(String id, String action, Map<String, ?> body, String idempotencyKey) {
        return call(ApiRequest.post(path + "/" + seg(id) + "/" + seg(action)).body(body).idempotencyKey(idempotencyKey), MAP);
    }
}
