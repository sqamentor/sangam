// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sangam.rest.support.DocumentStore;

/**
 * Per-tenant cell state for tenant moves (IR-4), persisted in REST_DOCUMENT (tenant {@code *}, kind
 * {@value #KIND}, doc id = tenant): ACTIVE (no document), IMPORTING (target before activation), FROZEN (source:
 * 503 for mutations and inbound events), MOVED (source: 421 with {@code targetCell}), PURGED. Reads are cached for
 * 2 s per tenant; writes evict the cache entry on this node, other nodes converge within 2 s.
 */
public final class CellTenantStateStore {

    public static final String KIND = "cell:tenant-state";

    public enum State { ACTIVE, IMPORTING, FROZEN, MOVED, PURGED }

    public record TenantState(String tenant, State state, String moveId, String targetCell, Instant since) {
    }

    private record Cached(TenantState value, long at) {
    }

    private static final long TTL_MS = 2000;

    private final DocumentStore documents;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CellTenantStateStore(DocumentStore documents) {
        this.documents = documents;
    }

    public TenantState get(String tenant) {
        Cached c = cache.get(tenant);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MS) {
            return c.value();
        }
        TenantState s = read(tenant);
        cache.put(tenant, new Cached(s, now));
        return s;
    }

    /** Uncached read (admin commands). */
    public TenantState read(String tenant) {
        Optional<DocumentStore.Doc> d = documents.get(DocumentStore.GLOBAL, KIND, tenant);
        if (d.isEmpty()) {
            return new TenantState(tenant, State.ACTIVE, null, null, null);
        }
        Map<String, Object> b = d.get().body();
        return new TenantState(tenant, State.valueOf(String.valueOf(b.getOrDefault("state", "ACTIVE"))),
                (String) b.get("moveId"), (String) b.get("targetCell"), d.get().updatedAt());
    }

    public TenantState set(String tenant, State state, String moveId, String targetCell, String actor) {
        cache.remove(tenant);
        if (state == State.ACTIVE) {
            documents.delete(DocumentStore.GLOBAL, KIND, tenant);
            return new TenantState(tenant, State.ACTIVE, moveId, null, Instant.now());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state.name());
        body.put("moveId", moveId);
        body.put("targetCell", targetCell);
        documents.upsert(DocumentStore.GLOBAL, KIND, tenant, tenant, state.name(), actor, moveId, body, actor);
        return new TenantState(tenant, state, moveId, targetCell, Instant.now());
    }
}
