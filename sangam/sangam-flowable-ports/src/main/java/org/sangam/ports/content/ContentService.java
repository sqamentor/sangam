// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.core.ports.DocumentStore;
import org.sangam.core.quota.QuotaLimit;
import org.sangam.core.quota.TenantQuotas;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Content / document service (FLOWABLE-INTO-OPERATON FC-40 Flowable {@code ContentService}; EC-18; R74): versioned documents attached to a
 * process instance, case instance, task or any product scope, stored through a {@link DocumentStore}
 * (file system or S3-compatible), with SHA-256 checksums verified on read and an optional malware-scan hook.
 *
 * <p>Write order: the object is stored first under a fresh immutable key, then the metadata rows are written in one
 * transaction. A failed metadata write leaves an unreferenced object that {@link #orphanCandidates} lists for the
 * sweeper; a reader can never see metadata for a missing object. Deletes are logical ({@code deleted_flag}); physical
 * erasure is the VI.5 erase flow (WS-INTEGRATION) calling {@link #erase}. Authorization: callers apply VI.4 on the scope.
 */
public class ContentService {

    public enum ScanState { NOT_SCANNED, CLEAN, INFECTED, PENDING }

    /** Malware scan hook (ClamAV/ICAP adapters live with the deployment). */
    @FunctionalInterface
    public interface ContentScanner {
        ScanState scan(String tenant, String name, String mimeType, InputStream content) throws IOException;
    }

    public record ContentItem(String id, String tenantId, String name, String mimeType, String scopeType, String scopeId,
                              String taskId, int currentVersion, boolean deleted, String createdBy, Instant createdAt) {
    }

    public record ContentVersion(String itemId, int version, String storeKey, long sizeBytes, String sha256, ScanState scanState,
                                 String createdBy, Instant createdAt) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final DocumentStore store;
    private final ContentScanner scanner;
    private final boolean requireClean;
    private final long maxBytes;

    /** Default upload limit per version (FW-39 robustness guard); deployments raise it explicitly. */
    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    public ContentService(Dialect dialect, Transactions tx, DocumentStore store, ContentScanner scannerOrNull, boolean requireClean) {
        this(dialect, tx, store, scannerOrNull, requireClean, DEFAULT_MAX_BYTES);
    }

    public ContentService(Dialect dialect, Transactions tx, DocumentStore store, ContentScanner scannerOrNull, boolean requireClean,
                          long maxBytes) {
        this.dialect = dialect;
        this.tx = tx;
        this.store = store;
        this.scanner = scannerOrNull;
        this.requireClean = requireClean;
        this.maxBytes = maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    /**
     * Tenant-checked open (FW-40: Flowable's content service resolves items by id alone). Use this from request paths;
     * an item of another tenant is reported as not found, so ids cannot be probed across tenants.
     */
    public InputStream open(String tenant, String itemId, int version) {
        ContentItem item = item(itemId).filter(i -> !i.deleted())
                .orElseThrow(() -> new IllegalArgumentException("content item " + itemId + " not found"));
        if (!java.util.Objects.equals(blankToNull(tenant), blankToNull(item.tenantId()))) {
            throw new IllegalArgumentException("content item " + itemId + " not found");
        }
        return open(itemId, version);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public ContentItem create(String tenant, String name, String mimeType, String scopeType, String scopeId, String taskId,
                              InputStream content, String user) {
        String id = UUID.randomUUID().toString();
        DocumentStore.StoredObject obj = store(tenant, id, 1, content, mimeType);
        ScanState scan = scan(tenant, name, mimeType, obj.key());
        tx.inTransaction(c -> {
            Jdbc.update(c, "INSERT INTO CONTENT_ITEM(id, tenant_id, name, mime_type, scope_type, scope_id, task_id, current_version,"
                            + " deleted_flag, created_by, created_at) VALUES (?,?,?,?,?,?,?,1,0,?," + dialect.now() + ")",
                    id, tenant, name, mimeType, scopeType, scopeId, taskId, user);
            insertVersion(c, id, 1, obj, scan, user);
            return null;
        });
        return item(id).orElseThrow();
    }

    public ContentVersion addVersion(String itemId, InputStream content, String user) {
        ContentItem item = item(itemId).filter(i -> !i.deleted())
                .orElseThrow(() -> new IllegalArgumentException("content item " + itemId + " not found"));
        int next = item.currentVersion() + 1;
        DocumentStore.StoredObject obj = store(item.tenantId(), itemId, next, content, item.mimeType());
        ScanState scan = scan(item.tenantId(), item.name(), item.mimeType(), obj.key());
        tx.inTransaction(c -> {
            int n = Jdbc.update(c, "UPDATE CONTENT_ITEM SET current_version = ? WHERE id = ? AND current_version = ?", next, itemId,
                    item.currentVersion());
            if (n == 0) {
                throw new IllegalStateException("concurrent new version of content item " + itemId);
            }
            insertVersion(c, itemId, next, obj, scan, user);
            return null;
        });
        return version(itemId, next).orElseThrow();
    }

    /** Opens a version (0 = current); the stream verifies size and SHA-256 at end of stream. */
    public InputStream open(String itemId, int version) {
        ContentItem item = item(itemId).filter(i -> !i.deleted())
                .orElseThrow(() -> new IllegalArgumentException("content item " + itemId + " not found"));
        ContentVersion v = version(itemId, version <= 0 ? item.currentVersion() : version)
                .orElseThrow(() -> new IllegalArgumentException("version " + version + " of " + itemId + " not found"));
        if (requireClean && v.scanState() != ScanState.CLEAN) {
            throw new SecurityException("content " + itemId + " v" + v.version() + " is " + v.scanState() + "; download blocked");
        }
        try {
            InputStream raw = store.get(v.storeKey()).orElseThrow(() -> new IllegalStateException("object " + v.storeKey() + " missing"));
            return new VerifyingInputStream(raw, v.sha256(), v.sizeBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<ContentItem> item(String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT id, tenant_id, name, mime_type, scope_type, scope_id, task_id, current_version,"
                + " deleted_flag, created_by, created_at FROM CONTENT_ITEM WHERE id = ?", ContentService::mapItem, id));
    }

    public List<ContentItem> byScope(String tenant, String scopeType, String scopeId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT id, tenant_id, name, mime_type, scope_type, scope_id, task_id, current_version,"
                + " deleted_flag, created_by, created_at FROM CONTENT_ITEM WHERE tenant_id = ? AND scope_type = ? AND scope_id = ?"
                + " AND deleted_flag = 0 ORDER BY created_at", ContentService::mapItem, tenant, scopeType, scopeId));
    }

    public List<ContentVersion> versions(String itemId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT item_id, version, store_key, size_bytes, sha256, scan_state, created_by, created_at"
                + " FROM CONTENT_VERSION WHERE item_id = ? ORDER BY version", ContentService::mapVersion, itemId));
    }

    public Optional<ContentVersion> version(String itemId, int version) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT item_id, version, store_key, size_bytes, sha256, scan_state, created_by, created_at"
                + " FROM CONTENT_VERSION WHERE item_id = ? AND version = ?", ContentService::mapVersion, itemId, version));
    }

    /** Logical delete. */
    public void delete(String itemId) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE CONTENT_ITEM SET deleted_flag = 1 WHERE id = ?", itemId));
    }

    /** Physical erasure of all versions (VI.5 erase flow / retention end). */
    public void erase(String itemId) {
        for (ContentVersion v : versions(itemId)) {
            try {
                store.delete(v.storeKey());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        tx.inTransaction(c -> {
            Jdbc.update(c, "DELETE FROM CONTENT_VERSION WHERE item_id = ?", itemId);
            Jdbc.update(c, "DELETE FROM CONTENT_ITEM WHERE id = ?", itemId);
            return null;
        });
    }

    /** Store keys referenced by metadata, for the orphan sweeper to diff against the store listing. */
    public List<String> orphanCandidates(String tenant) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT v.store_key FROM CONTENT_VERSION v JOIN CONTENT_ITEM i ON i.id = v.item_id"
                + " WHERE i.tenant_id = ?", rs -> rs.getString(1), tenant));
    }

    public void updateScan(String itemId, int version, ScanState state) {
        tx.inTransaction(c -> Jdbc.update(c, "UPDATE CONTENT_VERSION SET scan_state = ? WHERE item_id = ? AND version = ?",
                state.name(), itemId, version));
    }

    private DocumentStore.StoredObject store(String tenant, String id, int version, InputStream content, String mime) {
        var now = Instant.now().atZone(ZoneOffset.UTC);
        String key = safe(tenant) + "/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue()) + "/" + id + "/v" + version;
        if (content == null) {
            throw new IllegalArgumentException("content stream is required");
        }
        // SL-43: the tenant's maxDocumentBytes quota, when lower than the deployment-wide guard
        TenantQuotas quotas = TenantQuotas.current();
        long tenantMax = quotas.limit(tenant, QuotaLimit.DOCUMENT_BYTES);
        boolean quotaBound = tenantMax >= 0 && tenantMax < maxBytes;
        long limit = quotaBound ? tenantMax : maxBytes;
        DocumentStore.StoredObject obj;
        try {
            obj = store.put(key, new LimitedInputStream(content, limit), mime);
        } catch (LimitExceeded e) {
            deleteQuietly(key);      // the partial object (if the store kept one) must not stay behind
            if (quotaBound) {
                quotas.checkSize(tenant, QuotaLimit.DOCUMENT_BYTES, e.read);   // throws TENANT_QUOTA_EXCEEDED, audited
            }
            throw org.sangam.ports.support.PortsProblem.tooLarge("PAYLOAD_TOO_LARGE", "document", e.read, limit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // SL-43: the tenant's total stored bytes (maxStorageBytes); checked after the write because the size of a
        // stream is known only then. Concurrent uploads of one tenant can each pass the check (bounded overshoot).
        long storageMax = quotas.limit(tenant, QuotaLimit.STORAGE_BYTES);
        if (storageMax >= 0 && tenant != null && !tenant.isBlank()) {
            long used = tx.inTransaction(c -> Jdbc.one(c, "SELECT COALESCE(SUM(v.size_bytes), 0) FROM CONTENT_VERSION v"
                    + " JOIN CONTENT_ITEM i ON i.id = v.item_id WHERE i.tenant_id = ?", rs -> rs.getLong(1), tenant).orElse(0L));
            if (!TenantQuotas.within(storageMax, used + obj.sizeBytes())) {
                deleteQuietly(key);
                quotas.checkSize(tenant, QuotaLimit.STORAGE_BYTES, used + obj.sizeBytes());
            }
        }
        return obj;
    }

    private void deleteQuietly(String key) {
        try {
            store.delete(key);
        } catch (IOException ignored) {
            // the orphan sweeper removes it
        }
    }

    private ScanState scan(String tenant, String name, String mime, String key) {
        if (scanner == null) {
            return ScanState.NOT_SCANNED;
        }
        try (InputStream in = store.get(key).orElseThrow()) {
            return scanner.scan(tenant, name, mime, in);
        } catch (IOException e) {
            return ScanState.PENDING;
        }
    }

    private void insertVersion(java.sql.Connection c, String id, int version, DocumentStore.StoredObject obj, ScanState scan, String user) {
        Jdbc.update(c, "INSERT INTO CONTENT_VERSION(item_id, version, store_key, size_bytes, sha256, scan_state, created_by, created_at)"
                + " VALUES (?,?,?,?,?,?,?," + dialect.now() + ")", id, version, obj.key(), obj.sizeBytes(), obj.sha256Hex(), scan.name(), user);
    }

    private static String safe(String tenant) {
        return tenant == null || tenant.isBlank() ? "_shared" : tenant.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static ContentItem mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContentItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getInt(8), rs.getInt(9) == 1, rs.getString(10), Jdbc.instant(rs, "created_at"));
    }

    private static ContentVersion mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContentVersion(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                ScanState.valueOf(rs.getString(6)), rs.getString(7), Jdbc.instant(rs, "created_at"));
    }

    private static final class LimitExceeded extends IOException {
        final long read;

        LimitExceeded(long read) {
            super("content exceeds the size limit");
            this.read = read;
        }
    }

    /** Fails with {@link LimitExceeded} as soon as more than {@code max} bytes were read. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > max) {
                throw new LimitExceeded(count);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                count += n;
                if (count > max) {
                    throw new LimitExceeded(count);
                }
            }
            return n;
        }
    }

    /** Verifies size and checksum when the reader reaches end of stream. */
    static final class VerifyingInputStream extends DigestInputStream {
        private final String expected;
        private final long expectedSize;
        private long count;
        private boolean checked;

        VerifyingInputStream(InputStream in, String expectedSha256, long expectedSize) {
            super(in, FileSystemDocumentStore.sha256());
            this.expected = expectedSha256;
            this.expectedSize = expectedSize;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                check();
            } else {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
                check();
            } else {
                count += n;
            }
            return n;
        }

        private void check() throws IOException {
            if (checked) {
                return;
            }
            checked = true;
            MessageDigest md = getMessageDigest();
            String got = HexFormat.of().formatHex(md.digest());
            if (count != expectedSize || !got.equals(expected)) {
                throw new IOException("content checksum mismatch (stored object corrupted or replaced)");
            }
        }
    }
}
