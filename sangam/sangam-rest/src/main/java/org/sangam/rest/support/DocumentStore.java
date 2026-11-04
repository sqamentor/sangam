// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;

/**
 * Typed, tenant-scoped JSON documents in the product table {@code REST_DOCUMENT} (sangam-persistence changeset
 * 0016-01). The persistence for the sangam-rest default adapters whose state has no module-owned table, so that
 * nothing is lost on restart.
 *
 * <ul>
 *   <li>{@code kind} names the document type (e.g. {@code registry:services}, {@code notification}); {@code docKey},
 *       {@code ownerId}, {@code refId} and {@code state} are indexed lookup columns.</li>
 *   <li>{@code revision} starts at 1 and is incremented by every update; an update with a stale expected revision is
 *       {@code 409 REVISION_CONFLICT} and changes nothing.</li>
 *   <li>Every call joins the caller's Spring transaction (or opens one), so a document write commits with the engine
 *       state and the class-B audit event of the same command (P-1).</li>
 *   <li>Global (cross-tenant) documents use the tenant id {@link #GLOBAL}.</li>
 * </ul>
 */
public final class DocumentStore {

    public static final String GLOBAL = "*";
    public static final long ANY_REVISION = -1;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final String COLS = "tenant_id, kind, doc_id, doc_key, revision, state, owner_id, ref_id, body,"
            + " created_by, created_at, updated_at";

    /** One stored document. {@code body} is the parsed JSON object. */
    public record Doc(String tenantId, String kind, String id, String key, long revision, String state,
                      String ownerId, String refId, Map<String, Object> body, String createdBy, Instant createdAt,
                      Instant updatedAt) {

        public <T> T as(Class<T> type) {
            return JSON.convertValue(body, type);
        }
    }

    /** Lookup filter; null fields are ignored. */
    public record Filter(String key, String ownerId, String refId, String state) {
        public static final Filter ALL = new Filter(null, null, null, null);

        public static Filter key(String key) {
            return new Filter(key, null, null, null);
        }

        public static Filter owner(String owner) {
            return new Filter(null, owner, null, null);
        }

        public static Filter ref(String ref) {
            return new Filter(null, null, ref, null);
        }

        public static Filter state(String state) {
            return new Filter(null, null, null, state);
        }
    }

    private final TxSupport tx;
    private volatile org.sangam.core.security.FieldEncryptor encryptor;
    private volatile java.util.Set<String> encryptedKinds = java.util.Set.of();

    public DocumentStore(TxSupport tx) {
        this.tx = tx;
    }

    /**
     * Encrypts the {@code body} of the given kinds at rest with the tenant's data key (sangam-security
     * {@code FieldEncryptor}, AES-GCM, context = kind + id so a ciphertext cannot be moved to another document). The
     * lookup columns (key, owner, ref, state) stay clear so they remain indexed; never put personal data in them.
     */
    public void setEncryption(org.sangam.core.security.FieldEncryptor encryptor, java.util.Set<String> kinds) {
        this.encryptor = encryptor;
        this.encryptedKinds = kinds == null ? java.util.Set.of() : java.util.Set.copyOf(kinds);
    }

    private boolean encrypted(String kind) {
        return encryptor != null && encryptedKinds.contains(kind);
    }

    private String stored(String tenant, String kind, String id, Object body) {
        if (!encrypted(kind)) {
            return write(body);
        }
        return write(Map.of(ENC, encryptor.encryptJson(tenant, context(kind, id), toMap(body))));
    }

    private Map<String, Object> opened(String tenant, String kind, String id, Map<String, Object> body) {
        if (body.size() == 1 && body.get(ENC) instanceof String envelope) {
            if (encryptor == null) {
                throw ApiProblem.unavailable("ENCRYPTION_UNAVAILABLE", kind + " " + id
                        + " is encrypted at rest and no FieldEncryptor is configured", 60);
            }
            return new LinkedHashMap<>(encryptor.decryptJson(tenant, context(kind, id), envelope));
        }
        return body;
    }

    private static String context(String kind, String id) {
        return "rest-document:" + kind + ":" + id;
    }

    private static final String ENC = "_enc";

    public TxSupport tx() {
        return tx;
    }

    /** A new UUIDv7 document id. */
    public static String newId() {
        return Hashes.uuidV7().toString();
    }

    /** Converts any record / bean / map to the JSON object form stored in {@code body}. */
    public static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        return JSON.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    public static <T> T convert(Object value, Class<T> type) {
        return JSON.convertValue(value, type);
    }

    public static <T> T convert(Object value, TypeReference<T> type) {
        return JSON.convertValue(value, type);
    }

    public Optional<Doc> get(String tenant, String kind, String id) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + COLS + " FROM REST_DOCUMENT"
                + " WHERE tenant_id=? AND kind=? AND doc_id=?", DocumentStore::row, tenant, kind, id)).map(this::open);
    }

    /** {@link #get} or {@code 404 NOT_FOUND} for {@code what}. */
    public Doc require(String tenant, String kind, String id, String what) {
        return get(tenant, kind, id).orElseThrow(() -> ApiProblem.notFound(what, id));
    }

    public Optional<Doc> findByKey(String tenant, String kind, String key) {
        List<Doc> l = list(tenant, kind, Filter.key(key), 0, 1);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Inserts revision 1; a duplicate id is {@code 409 DUPLICATE}. */
    public Doc insert(String tenant, String kind, String id, String key, String state, String ownerId, String refId,
                      Object body, String createdBy) {
        String json = stored(tenant, kind, id, body);
        String plain = write(body);
        return tx.withConnection(c -> {
            Instant now = Jdbc.dbNow(c, tx.dialect());
            try {
                Jdbc.update(c, "INSERT INTO REST_DOCUMENT(" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        tenant, kind, id, key, 1L, state, ownerId, refId, json, createdBy, now, now);
            } catch (Jdbc.LayerSqlException e) {
                if (tx.dialect().isUniqueViolation(e.sql())) {
                    throw ApiProblem.conflict("DUPLICATE", kind + " " + id + " already exists");
                }
                throw e;
            }
            return new Doc(tenant, kind, id, key, 1, state, ownerId, refId, toMap(read(plain)), createdBy, now, now);
        });
    }

    /**
     * Replaces the document with revision+1. {@code expectedRevision} {@link #ANY_REVISION} skips the check; otherwise
     * a different stored revision is {@code 409 REVISION_CONFLICT}. A missing document is {@code 404}.
     */
    public Doc update(String tenant, String kind, String id, long expectedRevision, String key, String state,
                      String ownerId, String refId, Object body) {
        String json = stored(tenant, kind, id, body);
        String plain = write(body);
        return tx.withConnection(c -> {
            Doc current = lock(c, tenant, kind, id).orElseThrow(() -> ApiProblem.notFound(kind, id));
            if (expectedRevision != ANY_REVISION && expectedRevision != current.revision()) {
                throw ApiProblem.conflict("REVISION_CONFLICT", kind + " " + id + " is at revision "
                        + current.revision() + ", not " + expectedRevision).with("currentRevision", current.revision());
            }
            Instant now = Jdbc.dbNow(c, tx.dialect());
            long next = current.revision() + 1;
            Jdbc.update(c, "UPDATE REST_DOCUMENT SET doc_key=?, revision=?, state=?, owner_id=?, ref_id=?, body=?,"
                            + " updated_at=? WHERE tenant_id=? AND kind=? AND doc_id=? AND revision=?",
                    key, next, state, ownerId, refId, json, now, tenant, kind, id, current.revision());
            return new Doc(tenant, kind, id, key, next, state, ownerId, refId, toMap(read(plain)), current.createdBy(),
                    current.createdAt(), now);
        });
    }

    /** Insert or update (no revision check). */
    public Doc upsert(String tenant, String kind, String id, String key, String state, String ownerId, String refId,
                      Object body, String actor) {
        return tx.withConnection(c -> lock(c, tenant, kind, id).isPresent()
                ? update(tenant, kind, id, ANY_REVISION, key, state, ownerId, refId, body)
                : insert(tenant, kind, id, key, state, ownerId, refId, body, actor));
    }

    public boolean delete(String tenant, String kind, String id) {
        return tx.withConnection(c -> Jdbc.update(c,
                "DELETE FROM REST_DOCUMENT WHERE tenant_id=? AND kind=? AND doc_id=?", tenant, kind, id) > 0);
    }

    /** Documents of one kind, oldest first, after the filter; {@code limit <= 0} means all (bounded to 100 000). */
    public List<Doc> list(String tenant, String kind, Filter filter, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String where = where(tenant, kind, filter, params);
        int max = limit <= 0 ? 100_000 : Math.addExact(offset, limit);
        return tx.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM REST_DOCUMENT WHERE " + where
                    + " ORDER BY created_at, doc_id")) {
                ps.setMaxRows(max);
                Jdbc.bind(ps, 1, params.toArray());
                List<Doc> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    int i = 0;
                    while (rs.next()) {
                        if (i++ >= offset) {
                            out.add(open(row(rs)));
                        }
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new Jdbc.LayerSqlException("REST_DOCUMENT list", e);
            }
        });
    }

    public List<Doc> all(String tenant, String kind, Filter filter) {
        return list(tenant, kind, filter, 0, 0);
    }

    /** Count bounded by {@code cap} (A-16): returns cap+1 when there are more. */
    public long count(String tenant, String kind, Filter filter, long cap) {
        List<Object> params = new ArrayList<>();
        String where = where(tenant, kind, filter, params);
        return tx.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT doc_id FROM REST_DOCUMENT WHERE " + where)) {
                ps.setMaxRows((int) Math.min(Integer.MAX_VALUE, cap + 1));
                Jdbc.bind(ps, 1, params.toArray());
                long n = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        n++;
                    }
                }
                return n;
            } catch (SQLException e) {
                throw new Jdbc.LayerSqlException("REST_DOCUMENT count", e);
            }
        });
    }

    /** One capped page of documents mapped with {@code mapper} (A-16). */
    public <T> Page<T> page(String tenant, String kind, Filter filter, Page.PageRequest page, long cap,
                            java.util.function.Function<Doc, T> mapper) {
        List<T> items = list(tenant, kind, filter, page.firstResult(), page.size()).stream().map(mapper).toList();
        long n = count(tenant, kind, filter, cap);
        return Page.of(items, Math.min(n, cap), n > cap);
    }

    /** Decrypts an encrypted body (no-op for clear kinds). */
    private Doc open(Doc d) {
        Map<String, Object> body = opened(d.tenantId(), d.kind(), d.id(), d.body());
        return body == d.body() ? d : new Doc(d.tenantId(), d.kind(), d.id(), d.key(), d.revision(), d.state(),
                d.ownerId(), d.refId(), body, d.createdBy(), d.createdAt(), d.updatedAt());
    }

    private Optional<Doc> lock(Connection c, String tenant, String kind, String id) {
        return Jdbc.one(c, tx.dialect().lockingSelect(COLS, "REST_DOCUMENT", "tenant_id=? AND kind=? AND doc_id=?"),
                DocumentStore::row, tenant, kind, id);
    }

    private static String where(String tenant, String kind, Filter f, List<Object> params) {
        StringBuilder w = new StringBuilder("tenant_id=? AND kind=?");
        params.add(tenant);
        params.add(kind);
        if (f != null) {
            if (f.key() != null) {
                w.append(" AND doc_key=?");
                params.add(f.key());
            }
            if (f.ownerId() != null) {
                w.append(" AND owner_id=?");
                params.add(f.ownerId());
            }
            if (f.refId() != null) {
                w.append(" AND ref_id=?");
                params.add(f.refId());
            }
            if (f.state() != null) {
                w.append(" AND state=?");
                params.add(f.state());
            }
        }
        return w.toString();
    }

    private static Doc row(ResultSet rs) throws SQLException {
        return new Doc(rs.getString("tenant_id"), rs.getString("kind"), rs.getString("doc_id"), rs.getString("doc_key"),
                rs.getLong("revision"), rs.getString("state"), rs.getString("owner_id"), rs.getString("ref_id"),
                toMap(read(rs.getString("body"))), rs.getString("created_by"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "updated_at"));
    }

    static String write(Object body) {
        try {
            return JSON.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("document is not serialisable as JSON: " + e.getOriginalMessage(), e);
        }
    }

    static Object read(String json) {
        try {
            return json == null ? Map.of() : JSON.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored document is not valid JSON", e);
        }
    }
}
