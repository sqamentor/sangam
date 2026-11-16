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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
import org.sangam.integration.lifecycle.LifecyclePorts;

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
 *
 * <p>The store is a {@link RetentionClassStore} of the settings' class: no archive leaves it before its retention has
 * passed, except one an erasure supersedes.
 *
 * <p>Erasure ({@link LifecyclePorts.ErasureFollower}, spec section 2 "Tenancy and erasure"): an erasure of the tree
 * after its export replaces the archive by one without the tree's {@code ACT_HI_VARINST}, {@code ACT_HI_DETAIL} and
 * {@code ACT_HI_COMMENT} rows and the byte arrays only they reference. The new manifest lists the erasure (the
 * {@code DATA_ERASED} event and the previous digests), the new {@code HISTORY_ARCHIVED} event names the archives it
 * supersedes, and after the commit those leave the store. The scrub works on the stored archive, so it also reaches a
 * tree whose history was purged after its export.
 */
public final class HistoryArchives implements HistoryArchiveGate, LifecyclePorts.ErasureFollower {

    public static final String ARCHIVED = "HISTORY_ARCHIVED";
    public static final String MANIFEST = "manifest.json";
    public static final String SIGNATURE = "manifest.sig";

    /** The history tables whose rows an erasure removes from the archive (spec section 2). */
    public static final List<String> ERASED_TABLES = List.of("ACT_HI_VARINST", "ACT_HI_DETAIL", "ACT_HI_COMMENT");

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

    private final RetentionClassStore store;
    private final AuditChain audit;
    private final AuditCheckpoints.Signer signer;
    private final AuditCheckpoints.KeyLookup trusted;
    private final Settings settings;
    private final HistoryArchiveExporter exporter;

    /**
     * @param store the archive document store; wrapped in a {@link RetentionClassStore} of the settings' class unless it
     *              already is one (of that class and retention)
     */
    public HistoryArchives(DocumentStore store, AuditChain audit, AuditCheckpoints.Signer signer,
                           AuditCheckpoints.KeyLookup trusted, Settings settings) {
        Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (store instanceof RetentionClassStore r) {
            if (!r.retentionClass().equals(settings.retentionClass()) || r.retentionDays() != settings.retentionDays()) {
                throw new IllegalArgumentException("the archive store enforces class " + r.retentionClass() + " ("
                        + r.retentionDays() + " days), the settings name " + settings.retentionClass() + " ("
                        + settings.retentionDays() + " days)");
            }
            this.store = r;
        } else {
            this.store = new RetentionClassStore(store, settings.retentionClass(), settings.retentionDays(),
                    Clock.systemUTC());
        }
        this.audit = Objects.requireNonNull(audit, "audit");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.trusted = Objects.requireNonNull(trusted, "trusted");
        this.exporter = new HistoryArchiveExporter(settings.tablePrefix(), settings.sangamVersion());
    }

    /** The archive store, which enforces the retention class. */
    public RetentionClassStore store() {
        return store;
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
        return signStoreAndAudit(c, tenantId, rootId, a.files(), a.manifest(), a.manifestSha256(), a.rowCounts(),
                a.lastEnd().toString(), Map.of());
    }

    /** Signs the manifest, stores the ZIP (or finds the same one) and appends the {@code HISTORY_ARCHIVED} event. */
    private Stored signStoreAndAudit(Connection c, String tenantId, String rootId, Map<String, byte[]> archiveFiles,
                                     byte[] manifest, String manifestSha256, Map<String, Long> rowCounts, String lastEnd,
                                     Map<String, Object> extra) {
        byte[] sig = signer.sign(manifest);
        Map<String, Object> sigDoc = new LinkedHashMap<>();
        sigDoc.put("algorithm", "Ed25519");
        sigDoc.put("keyId", signer.keyId());
        sigDoc.put("manifestSha256", manifestSha256);
        sigDoc.put("signature", Base64.getEncoder().encodeToString(sig));
        Map<String, byte[]> files = new HashMap<>(archiveFiles);
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
        p.put("manifestSha256", manifestSha256);
        p.put("keyId", signer.keyId());
        p.put("signature", Base64.getEncoder().encodeToString(sig));
        p.put("retentionClass", settings.retentionClass());
        p.put("retentionDays", settings.retentionDays());
        p.put("rows", new TreeMap<>(rowCounts));
        p.put("lastEnd", lastEnd);
        p.put("exportedAt", Instant.now().toString());
        p.putAll(extra);
        audit.append(c, auditTenant(tenantId), rootId, ARCHIVED, p);
        return new Stored(tenantId, rootId, key, zipSha, manifestSha256, signer.keyId(), reused);
    }

    // ---- erasure (spec section 2 "Tenancy and erasure") ----

    /**
     * Reads, before the erasure touches the audit payloads, the latest archive of the tree (which must be the one its
     * audit event recorded) and every archive object the tree's archive events name. {@code null} when the tree has
     * no archive or none of its archives is stored any more. An archive that is not the one recorded refuses the
     * erasure ({@code HISTORY_ARCHIVE_ERASURE_REFUSED}): it is resolved first, so no copy of erased data stays behind.
     */
    @Override
    public FollowUp before(Connection c, String tenantId, String rootId) {
        record Event(byte[] digest, byte[] salt, String payload) {
        }
        List<Event> events = Jdbc.query(c, "SELECT e.payload_digest, p.salt, p.payload FROM AUDIT_EVENT e JOIN"
                        + " AUDIT_PAYLOAD p ON p.event_id=e.event_id WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=?"
                        + " AND p.erased_at IS NULL AND p.payload IS NOT NULL ORDER BY e.seq DESC",
                rs -> new Event(rs.getBytes(1), rs.getBytes(2), rs.getString(3)), auditTenant(tenantId), rootId, ARCHIVED);
        if (events.isEmpty()) {
            return null;
        }
        Set<String> keys = new LinkedHashSet<>();
        Map<String, Object> latest = null;
        for (Event e : events) {
            if (e.salt() == null || !Arrays.equals(e.digest(), Hashes.sha256(e.salt(),
                    e.payload().getBytes(StandardCharsets.UTF_8)))) {
                throw refused(rootId, "an archive event of the tree is altered");
            }
            Map<String, Object> p = json(e.payload().getBytes(StandardCharsets.UTF_8), rootId);
            if (latest == null) {
                latest = p;
            }
            keys.add(String.valueOf(p.get("key")));
            if (p.get("supersedes") instanceof List<?> earlier) {
                for (Object k : earlier) {
                    keys.add(String.valueOf(k));
                }
            }
        }
        String key = String.valueOf(latest.get("key"));
        try {
            byte[] zip = read(key);
            if (zip == null) {
                for (String k : keys) {
                    if (store.exists(k)) {
                        throw refused(rootId, "the latest archive " + key + " is not stored, an earlier one " + k + " is");
                    }
                }
                return null;   // no stored copy of the tree remains
            }
            if (!Hashes.hex(Hashes.sha256(zip)).equals(latest.get("zipSha256"))) {
                throw refused(rootId, key + " is not the archive its audit event recorded");
            }
            return new ErasureFollowUp(tenantId, rootId, key, String.valueOf(latest.get("zipSha256")),
                    String.valueOf(latest.get("manifestSha256")), keys, unzip(zip, key, rootId));
        } catch (IOException e) {
            throw new IllegalStateException("HISTORY_ARCHIVE_ERASURE_REFUSED: the archive of tree " + rootId
                    + " cannot be read: " + e.getMessage(), e);
        }
    }

    private static IllegalStateException refused(String rootId, String why) {
        return new IllegalStateException("HISTORY_ARCHIVE_ERASURE_REFUSED: tree " + rootId + ": " + why
                + "; the erasure is refused until the archive is resolved");
    }

    private static Map<String, Object> json(byte[] bytes, String rootId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(bytes, LinkedHashMap.class);
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("HISTORY_ARCHIVE_INVALID: a JSON document of the archive of tree " + rootId
                    + " cannot be read", e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip, String key, String rootId) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry en; (en = z.getNextEntry()) != null; ) {
                if (files.put(en.getName(), z.readAllBytes()) != null) {
                    throw refused(rootId, key + " holds " + en.getName() + " twice");
                }
            }
        }
        if (!files.containsKey(MANIFEST)) {
            throw refused(rootId, key + " has no manifest");
        }
        return files;
    }

    /** One erasure of one tree: the stored archive it scrubs and the archive objects it supersedes. */
    private final class ErasureFollowUp implements FollowUp {
        private final String tenantId;
        private final String rootId;
        private final String previousKey;
        private final String previousZipSha256;
        private final String previousManifestSha256;
        private final Set<String> superseded;
        private final Map<String, byte[]> previous;
        private String replacement;

        ErasureFollowUp(String tenantId, String rootId, String previousKey, String previousZipSha256,
                        String previousManifestSha256, Set<String> superseded, Map<String, byte[]> previous) {
            this.tenantId = tenantId;
            this.rootId = rootId;
            this.previousKey = previousKey;
            this.previousZipSha256 = previousZipSha256;
            this.previousManifestSha256 = previousManifestSha256;
            this.superseded = superseded;
            this.previous = previous;
        }

        @Override
        public int erased(Connection c, String dataErasedEventId, String reason) {
            Map<String, byte[]> files = new TreeMap<>(previous);
            Map<String, Object> manifest = json(files.remove(MANIFEST), rootId);
            files.remove(SIGNATURE);
            Map<String, Long> removedRows = new TreeMap<>();
            Set<String> droppedRefs = new TreeSet<>();
            for (String table : ERASED_TABLES) {
                String path = "tables/" + table + ".jsonl";
                byte[] rows = files.get(path);
                if (rows == null) {
                    continue;
                }
                long n = 0;
                for (String line : lines(rows)) {
                    n++;
                    refs(json(line.getBytes(StandardCharsets.UTF_8), rootId), droppedRefs);
                }
                files.put(path, new byte[0]);
                removedRows.put(table, n);
            }
            // a byte array leaves with the rows that referenced it, unless a kept row references it too
            Set<String> keptRefs = new TreeSet<>();
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                if (f.getKey().startsWith("tables/") && !f.getKey().equals("tables/ACT_GE_BYTEARRAY.jsonl")) {
                    for (String line : lines(f.getValue())) {
                        refs(json(line.getBytes(StandardCharsets.UTF_8), rootId), keptRefs);
                    }
                }
            }
            droppedRefs.removeAll(keptRefs);
            long removedBytes = 0;
            byte[] ge = files.get("tables/ACT_GE_BYTEARRAY.jsonl");
            if (ge != null) {
                StringBuilder kept = new StringBuilder();
                for (String line : lines(ge)) {
                    if (droppedRefs.contains(String.valueOf(json(line.getBytes(StandardCharsets.UTF_8), rootId).get("ID_")))) {
                        removedBytes++;
                    } else {
                        kept.append(line).append('\n');
                    }
                }
                files.put("tables/ACT_GE_BYTEARRAY.jsonl", kept.toString().getBytes(StandardCharsets.UTF_8));
            }
            Map<String, Long> rowCounts = new TreeMap<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("path", f.getKey());
                e.put("sha256", Hashes.hex(Hashes.sha256(f.getValue())));
                e.put("bytes", (long) f.getValue().length);
                if (f.getKey().startsWith("tables/")) {
                    long rows = lines(f.getValue()).size();
                    e.put("rows", rows);
                    rowCounts.put(f.getKey().substring(7, f.getKey().length() - 6), rows);
                }
                entries.add(e);
            }
            List<Object> erasures = new ArrayList<>();
            if (manifest.get("erasures") instanceof List<?> earlier) {
                erasures.addAll(earlier);
            }
            Map<String, Object> erasure = new LinkedHashMap<>();
            erasure.put("dataErased", dataErasedEventId);
            erasure.put("previousZipSha256", previousZipSha256);
            erasure.put("previousManifestSha256", previousManifestSha256);
            erasure.put("removedRows", removedRows);
            erasure.put("removedByteArrays", removedBytes);
            erasures.add(erasure);
            manifest.put("files", entries);
            manifest.put("erasures", erasures);
            byte[] manifestBytes = CanonicalJson.write(manifest).getBytes(StandardCharsets.UTF_8);
            files.put(MANIFEST, manifestBytes);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("dataErased", dataErasedEventId);
            extra.put("supersedes", new ArrayList<>(superseded));
            Stored s = signStoreAndAudit(c, tenantId, rootId, files, manifestBytes,
                    Hashes.hex(Hashes.sha256(manifestBytes)), rowCounts, String.valueOf(manifest.get("lastEnd")), extra);
            if (superseded.contains(s.key())) {
                throw refused(rootId, "the scrubbed archive " + s.key() + " is one it supersedes");
            }
            replacement = s.key();
            return 1;
        }

        @Override
        public int committed() {
            if (replacement == null) {
                return 0;
            }
            int removed = 0;
            try {
                for (String k : superseded) {
                    if (store.supersede(k, replacement)) {
                        removed++;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("HISTORY_ARCHIVE_SUPERSEDE_FAILED: the archives of tree " + rootId
                        + " that hold erased data could not all be removed (" + previousKey + " and earlier); a repeated"
                        + " erasure removes them", e);
            }
            return removed;
        }
    }

    private static List<String> lines(byte[] jsonl) {
        return new String(jsonl, StandardCharsets.UTF_8).lines().filter(l -> !l.isEmpty()).toList();
    }

    private static void refs(Map<String, Object> row, Set<String> into) {
        for (String ref : HistoryArchiveExporter.BYTE_REFS) {
            if (row.get(ref) instanceof String s && !s.isEmpty()) {
                into.add(s);
            }
        }
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
