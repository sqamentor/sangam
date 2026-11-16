// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.core.history.HistoryArchiveGate;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.CanonicalJson;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.core.ports.DocumentStore;

/**
 * RM-52 (decision 386): the history archives of process trees, and the {@link HistoryArchiveGate} every purge asks.
 *
 * <p>{@link #archive}: the tree is exported ({@link HistoryArchiveExporter}), its manifest signed with the archive key
 * (Ed25519, a key of its own: decision 37), the ZIP ({@code manifest.sig} included) stored in the document store under
 * the records-retention class ({@code <tenant>/records/<class>/history/<root>/<zip sha-256>.zip}; decision 386(1)), and
 * a {@code HISTORY_ARCHIVED} event with the digests, the key id and the signature appended to the root's audit chain,
 * in the caller's transaction.
 *
 * <p>{@link #refusal} (the gate): a tree may be purged only when its latest {@code HISTORY_ARCHIVED} event exists, the
 * root's audit chain verifies and the event's payload still matches its digest; the stored ZIP is there and matches the
 * recorded digest; the manifest matches its digest and its signature verifies against a trusted archive key; every file
 * matches the manifest; and no history table of the tree holds more rows now than the archive does. Anything else is a
 * refusal, so history without a verified archive is kept (decision 386(2): every tree).
 */
public final class HistoryArchives implements HistoryArchiveGate {

    public static final String ARCHIVED = "HISTORY_ARCHIVED";
    public static final String MANIFEST = "manifest.json";
    public static final String SIGNATURE = "manifest.sig";

    /**
     * @param retentionClass the document-store retention class of the archives (decision 386(1))
     * @param retentionDays  how long that class keeps an archive; longer than every engine history retention
     */
    public record Settings(String retentionClass, int retentionDays, String sangamVersion, String tablePrefix) {
        public Settings {
            if (retentionClass == null || !retentionClass.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("archive retention class must be an upper-case code: " + retentionClass);
            }
            if (retentionDays <= 0) {
                throw new IllegalArgumentException("archive retention must be positive");
            }
        }
    }

    /** One stored archive. */
    public record Stored(String tenantId, String rootId, String key, String zipSha256, String manifestSha256,
                         String keyId, boolean reused) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentStore store;
    private final AuditChain audit;
    private final AuditCheckpoints.Signer signer;
    private final AuditCheckpoints.KeyLookup trusted;
    private final Settings settings;
    private final HistoryArchiveExporter exporter;

    public HistoryArchives(DocumentStore store, AuditChain audit, AuditCheckpoints.Signer signer,
                           AuditCheckpoints.KeyLookup trusted, Settings settings) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.trusted = Objects.requireNonNull(trusted, "trusted");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.exporter = new HistoryArchiveExporter(settings.tablePrefix(), settings.sangamVersion());
    }

    /**
     * Refuses archive settings whose retention is not longer than the longest engine history retention: an archive
     * must outlive the history it replaces (decision 386(1)).
     */
    public static void requireLongerThanEngine(Settings s, int longestEngineRetentionDays) {
        if (s.retentionDays() <= longestEngineRetentionDays) {
            throw new IllegalStateException("history archive retention " + s.retentionDays() + " days is not longer than"
                    + " the longest engine history retention (" + longestEngineRetentionDays + " days)");
        }
    }

    public Settings settings() {
        return settings;
    }

    /** The audit tenant of a tree's tenant (shared definitions audit under {@link TenantKey#SHARED}). */
    static String auditTenant(String tenantId) {
        return tenantId == null ? TenantKey.SHARED : tenantId;
    }

    /**
     * Exports, signs, stores and audits the archive of an ended tree, in the caller's transaction. A second archive of
     * an unchanged tree finds the same ZIP and reuses it; the audit event is still written, so the latest event always
     * names the archive that was checked last.
     */
    public Stored archive(Connection c, String tenantId, String rootId, Map<String, String> historyLevels) {
        HistoryArchiveExporter.Archive a = exporter.export(c, tenantId, rootId, historyLevels);
        long ttl = exporter.longestHistoryTimeToLiveDays(c, rootId);
        if (ttl >= settings.retentionDays()) {
            throw new HistoryArchiveExporter.ExportRefusedException("HISTORY_ARCHIVE_RETENTION_TOO_SHORT", "tree "
                    + rootId + " keeps engine history " + ttl + " days, the archive class " + settings.retentionClass()
                    + " only " + settings.retentionDays() + " (decision 386(1))");
        }
        byte[] sig = signer.sign(a.manifest());
        Map<String, Object> sigDoc = new LinkedHashMap<>();
        sigDoc.put("algorithm", "Ed25519");
        sigDoc.put("keyId", signer.keyId());
        sigDoc.put("manifestSha256", a.manifestSha256());
        sigDoc.put("signature", Base64.getEncoder().encodeToString(sig));
        Map<String, byte[]> files = new HashMap<>(a.files());
        files.put(SIGNATURE, CanonicalJson.write(sigDoc).getBytes(StandardCharsets.UTF_8));
        byte[] zip;
        try {
            zip = HistoryArchiveExporter.zip(files);
        } catch (IOException e) {
            throw new IllegalStateException("history archive of tree " + rootId + " could not be written", e);
        }
        String zipSha = Hashes.hex(Hashes.sha256(zip));
        String key = key(tenantId, rootId, zipSha);
        boolean reused;
        try {
            reused = store.exists(key);
            if (reused) {
                byte[] existing = read(key);
                if (existing == null || !Arrays.equals(existing, zip)) {
                    throw new IllegalStateException("HISTORY_ARCHIVE_STORE_CONFLICT: " + key + " holds other bytes");
                }
            } else {
                DocumentStore.StoredObject o = store.put(key, new ByteArrayInputStream(zip), "application/zip");
                if (!zipSha.equals(o.sha256Hex())) {
                    throw new IllegalStateException("HISTORY_ARCHIVE_STORE_CONFLICT: " + key + " stored with digest "
                            + o.sha256Hex());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("history archive of tree " + rootId + " could not be stored", e);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("zipSha256", zipSha);
        p.put("manifestSha256", a.manifestSha256());
        p.put("keyId", signer.keyId());
        p.put("signature", Base64.getEncoder().encodeToString(sig));
        p.put("retentionClass", settings.retentionClass());
        p.put("retentionDays", settings.retentionDays());
        p.put("rows", new java.util.TreeMap<>(a.rowCounts()));
        p.put("lastEnd", a.lastEnd().toString());
        p.put("exportedAt", Instant.now().toString());
        audit.append(c, auditTenant(tenantId), rootId, ARCHIVED, p);
        return new Stored(tenantId, rootId, key, zipSha, a.manifestSha256(), signer.keyId(), reused);
    }

    String key(String tenantId, String rootId, String zipSha) {
        return safe(auditTenant(tenantId)) + "/records/" + settings.retentionClass() + "/history/" + safe(rootId) + "/"
                + zipSha.substring(0, 32) + ".zip";
    }

    private static String safe(String s) {
        if (s.isEmpty() || !s.matches("[A-Za-z0-9._:-]+") || s.contains("..")) {
            throw new IllegalArgumentException("not a safe archive key part: " + s);
        }
        return s;
    }

    private byte[] read(String key) throws IOException {
        Optional<InputStream> in = store.get(key);
        if (in.isEmpty()) {
            return null;
        }
        try (InputStream s = in.get()) {
            return s.readAllBytes();
        }
    }

    @Override
    public String refusal(Connection c, String tenantId, String rootId) {
        try {
            return check(c, tenantId, rootId);
        } catch (RuntimeException e) {
            return "HISTORY_ARCHIVE_UNVERIFIABLE: " + e;
        }
    }

    private String check(Connection c, String tenantId, String rootId) {
        String auditTenant = auditTenant(tenantId);
        record Event(byte[] digest, byte[] salt, String payload) {
        }
        List<Event> events = Jdbc.query(c, "SELECT e.payload_digest, p.salt, p.payload FROM AUDIT_EVENT e JOIN"
                        + " AUDIT_PAYLOAD p ON p.event_id=e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=?"
                        + " ORDER BY e.seq DESC", rs -> new Event(rs.getBytes(1), rs.getBytes(2), rs.getString(3)),
                auditTenant, rootId, ARCHIVED);
        if (events.isEmpty()) {
            return "HISTORY_ARCHIVE_MISSING: tree " + rootId + " has no archive";
        }
        AuditChain.VerifyResult chain = audit.verify(c, auditTenant, rootId);
        if (!chain.ok()) {
            return "HISTORY_ARCHIVE_AUDIT_INVALID: the audit chain of " + rootId + " does not verify: " + chain.reason();
        }
        Event e = events.get(0);
        if (e.payload() == null || e.salt() == null || !Arrays.equals(e.digest(),
                Hashes.sha256(e.salt(), e.payload().getBytes(StandardCharsets.UTF_8)))) {
            return "HISTORY_ARCHIVE_AUDIT_INVALID: the archive event of " + rootId + " is erased or altered";
        }
        Map<String, Object> p;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(e.payload(), Map.class);
            p = parsed;
        } catch (IOException ex) {
            return "HISTORY_ARCHIVE_AUDIT_INVALID: the archive event of " + rootId + " is not JSON";
        }
        String key = String.valueOf(p.get("key"));
        byte[] zip;
        try {
            zip = read(key);
        } catch (IOException ex) {
            return "HISTORY_ARCHIVE_NOT_STORED: " + key + " cannot be read: " + ex.getMessage();
        }
        if (zip == null) {
            return "HISTORY_ARCHIVE_NOT_STORED: " + key + " is not in the archive store";
        }
        if (!Hashes.hex(Hashes.sha256(zip)).equals(p.get("zipSha256"))) {
            return "HISTORY_ARCHIVE_DIGEST_MISMATCH: " + key + " is not the archive the audit event recorded";
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                if (files.put(en.getName(), z.readAllBytes()) != null) {
                    return "HISTORY_ARCHIVE_INVALID: " + key + " holds " + en.getName() + " twice";
                }
            }
        } catch (IOException ex) {
            return "HISTORY_ARCHIVE_INVALID: " + key + " is not a readable ZIP";
        }
        byte[] manifest = files.get(MANIFEST);
        if (manifest == null || !Hashes.hex(Hashes.sha256(manifest)).equals(p.get("manifestSha256"))) {
            return "HISTORY_ARCHIVE_DIGEST_MISMATCH: the manifest of " + key + " is not the one the audit event recorded";
        }
        String keyId = String.valueOf(p.get("keyId"));
        Optional<PublicKey> pub = trusted.publicKey(keyId);
        if (pub.isEmpty()) {
            return "HISTORY_ARCHIVE_SIGNATURE_INVALID: archive key " + keyId + " is not trusted";
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(String.valueOf(p.get("signature")));
        } catch (IllegalArgumentException ex) {
            return "HISTORY_ARCHIVE_SIGNATURE_INVALID: the signature of " + key + " is not base64";
        }
        if (!verify(pub.get(), manifest, sig)) {
            return "HISTORY_ARCHIVE_SIGNATURE_INVALID: the manifest of " + key + " does not verify against key " + keyId;
        }
        Map<String, Object> m;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(manifest, Map.class);
            m = parsed;
        } catch (IOException ex) {
            return "HISTORY_ARCHIVE_INVALID: the manifest of " + key + " is not JSON";
        }
        if (!rootId.equals(m.get("root")) || !Objects.equals(tenantId, m.get("tenant"))) {
            return "HISTORY_ARCHIVE_INVALID: " + key + " is the archive of another tree";
        }
        java.util.Set<String> listed = new java.util.HashSet<>(List.of(MANIFEST, SIGNATURE));
        Map<String, Long> archivedRows = new HashMap<>();
        for (Object o : (List<?>) m.get("files")) {
            Map<?, ?> f = (Map<?, ?>) o;
            String path = String.valueOf(f.get("path"));
            byte[] bytes = files.get(path);
            if (bytes == null || !Hashes.hex(Hashes.sha256(bytes)).equals(f.get("sha256"))) {
                return "HISTORY_ARCHIVE_INVALID: " + path + " of " + key + " is missing or altered";
            }
            listed.add(path);
            if (path.startsWith("tables/") && f.get("rows") != null) {
                archivedRows.put(path.substring(7, path.length() - 6), ((Number) f.get("rows")).longValue());
            }
        }
        for (String name : files.keySet()) {
            if (!listed.contains(name)) {
                return "HISTORY_ARCHIVE_INVALID: " + key + " holds " + name + ", which its manifest does not list";
            }
        }
        for (Map.Entry<String, Long> now : exporter.currentCounts(c, rootId).entrySet()) {
            long archived = archivedRows.getOrDefault(now.getKey(), 0L);
            if (now.getValue() > archived) {
                return "HISTORY_ARCHIVE_STALE: " + now.getKey() + " holds " + now.getValue() + " rows of tree " + rootId
                        + ", the archive " + archived;
            }
        }
        return null;
    }

    static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
