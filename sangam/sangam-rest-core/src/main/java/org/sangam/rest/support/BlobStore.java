// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;

/**
 * Binary contents in the product table {@code REST_BLOB} (sangam-persistence changeset 0016-02): audit export files,
 * reconciliation evidence. Content is immutable; its SHA-256 is stored and returned (audit evidence, OJ-02).
 */
public final class BlobStore {

    public record BlobRef(String id, String kind, String ownerRef, String fileName, String contentType, long size,
                          String sha256, String createdBy, Instant createdAt) {
    }

    public record Blob(BlobRef ref, byte[] content) {
    }

    /** Upper bound per blob (50 MiB). */
    public static final long MAX_BYTES = 50L * 1024 * 1024;

    private static final String META = "blob_id, kind, owner_ref, file_name, content_type, size_bytes, sha256,"
            + " created_by, created_at";

    private final TxSupport tx;

    public BlobStore(TxSupport tx) {
        this.tx = tx;
    }

    public BlobRef put(String tenant, String kind, String ownerRef, String fileName, String contentType, byte[] content,
                       String createdBy) {
        if (content == null) {
            content = new byte[0];
        }
        if (content.length > MAX_BYTES) {
            throw new org.sangam.rest.port.ApiProblem(413, "PAYLOAD_TOO_LARGE", "content above " + MAX_BYTES + " bytes");
        }
        byte[] bytes = content;
        String id = DocumentStore.newId();
        String sha = Hashes.hex(Hashes.sha256(bytes));
        return tx.withConnection(c -> {
            Instant now = Jdbc.dbNow(c, tx.dialect());
            Jdbc.update(c, "INSERT INTO REST_BLOB(tenant_id, " + META + ", content) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    tenant, id, kind, ownerRef, fileName, contentType, (long) bytes.length, sha, createdBy, now, bytes);
            return new BlobRef(id, kind, ownerRef, fileName, contentType, bytes.length, sha, createdBy, now);
        });
    }

    public Optional<Blob> get(String tenant, String id) {
        return tx.withConnection(c -> Jdbc.one(c, "SELECT " + META + ", content FROM REST_BLOB WHERE tenant_id=? AND blob_id=?",
                rs -> new Blob(ref(rs), rs.getBytes("content")), tenant, id));
    }

    public List<BlobRef> list(String tenant, String kind, String ownerRef) {
        return tx.withConnection(c -> Jdbc.query(c, "SELECT " + META + " FROM REST_BLOB WHERE tenant_id=? AND kind=?"
                + " AND owner_ref=? ORDER BY created_at", BlobStore::ref, tenant, kind, ownerRef));
    }

    public boolean delete(String tenant, String id) {
        return tx.withConnection(c -> Jdbc.update(c, "DELETE FROM REST_BLOB WHERE tenant_id=? AND blob_id=?", tenant, id) > 0);
    }

    private static BlobRef ref(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BlobRef(rs.getString("blob_id"), rs.getString("kind"), rs.getString("owner_ref"),
                rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("created_by"), Jdbc.instant(rs, "created_at"));
    }
}
