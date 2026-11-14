// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.jdbc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.sangam.rest.support.DocumentStore;

/**
 * A persistent, tenant-scoped map of one document kind in {@code REST_DOCUMENT}: the JDBC replacement for the
 * {@code tenant → id → value} maps of the former in-memory adapters. Values are serialised as JSON; the
 * {@code index} function supplies the indexed columns {@code {doc_key, owner_id, ref_id, state}} (nulls allowed).
 * Every call joins the caller's transaction.
 */
final class DocMap<V> {

    private final DocumentStore store;
    private final String kind;
    private final Function<Map<String, Object>, V> reader;
    private final Function<V, String[]> index;

    DocMap(DocumentStore store, String kind, Class<V> type, Function<V, String[]> index) {
        this(store, kind, body -> DocumentStore.convert(body, type), index);
    }

    DocMap(DocumentStore store, String kind, Function<Map<String, Object>, V> reader, Function<V, String[]> index) {
        this.store = store;
        this.kind = kind;
        this.reader = reader;
        this.index = index;
    }

    View in(String tenant) {
        return new View(tenant);
    }

    String kind() {
        return kind;
    }

    final class View {
        private final String tenant;

        private View(String tenant) {
            this.tenant = tenant;
        }

        V get(String id) {
            if (id == null) {
                return null;
            }
            return store.get(tenant, kind, id).map(d -> reader.apply(d.body())).orElse(null);
        }

        boolean containsKey(String id) {
            return id != null && store.get(tenant, kind, id).isPresent();
        }

        V getOrDefault(String id, V fallback) {
            V v = get(id);
            return v == null ? fallback : v;
        }

        void put(String id, V value) {
            String[] ix = index.apply(value);
            store.upsert(tenant, kind, id, ix[0], ix[3], ix[1], ix[2], value, null);
        }

        /** Inserts only when absent; returns the stored value when present. */
        V putIfAbsent(String id, V value) {
            V cur = get(id);
            if (cur != null) {
                return cur;
            }
            put(id, value);
            return null;
        }

        void remove(String id) {
            store.delete(tenant, kind, id);
        }

        List<V> values() {
            return store.all(tenant, kind, DocumentStore.Filter.ALL).stream().map(d -> reader.apply(d.body())).toList();
        }

        List<V> where(DocumentStore.Filter filter) {
            return store.all(tenant, kind, filter).stream().map(d -> reader.apply(d.body())).toList();
        }

        /** Document ids (in creation order) matching the filter. */
        List<String> ids(DocumentStore.Filter filter) {
            return store.all(tenant, kind, filter).stream().map(DocumentStore.Doc::id).toList();
        }
    }

    static String[] ix(String key, String owner, String ref, String state) {
        return new String[]{key, owner, ref, state};
    }
}
