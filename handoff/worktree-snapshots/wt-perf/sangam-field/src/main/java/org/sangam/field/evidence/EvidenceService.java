// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.evidence;

import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.evidence.CapturePacket.GpsFix;
import org.sangam.field.evidence.CapturePacket.MediaRef;
import org.sangam.field.schedule.Assignment;
import org.sangam.field.schedule.AssignmentService;
import org.sangam.field.support.FieldSql;
import org.sangam.field.template.FieldTemplate;
import org.sangam.field.template.TemplateRegistry;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Geo-tagged evidence (N05): content-addressed blob storage, SHA-256 of the bytes, EXIF extraction, the photo
 * anti-spoofing checks and per-device hash-chain verification. Evidence declared in a packet ({@link MediaRef}) is
 * registered as PENDING_UPLOAD and completed when the bytes arrive; the declared hash must match the bytes.
 */
public final class EvidenceService {

    public record Upload(String evidenceId, String assignmentId, String itemId, String packetId, String mediaType,
                         String declaredSha256, Instant capturedAt, GpsFix fix) {
    }

    public record EvidenceRecord(String id, String packetId, String assignmentId, String itemId, String mediaType, String sha256,
                                 long sizeBytes, String storageRef, Map<String, Object> exif, Instant capturedAt, Double lat,
                                 Double lon, Long accuracyM, String integrityStatus, Map<String, Object> integrity,
                                 String uploadedBy, Instant uploadedAt) {
    }

    /** One break in a device's packet chain. */
    public record ChainBreak(String packetId, long deviceSeq, String kind, String detail) {
    }

    public static final String PENDING = "PENDING_UPLOAD";

    private final Transactions tx;
    private final EvidenceBlobStore blobs;
    private final AssignmentService assignments;
    private final TemplateRegistry templates;
    private final FieldAudit audit;
    private final FieldConfig config;
    private final Clock clock;

    public EvidenceService(Transactions tx, EvidenceBlobStore blobs, AssignmentService assignments, TemplateRegistry templates,
                           FieldAudit audit, FieldConfig config, Clock clock) {
        this.tx = tx;
        this.blobs = blobs;
        this.assignments = assignments;
        this.templates = templates;
        this.audit = audit;
        this.config = config == null ? FieldConfig.defaults() : config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Uploads evidence bytes (idempotent per evidence id + hash). */
    public EvidenceRecord upload(String tenantId, String actor, Upload u, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new FieldException("INVALID_REQUEST", "evidence bytes are empty");
        }
        String sha = Hashes.hex(Hashes.sha256(bytes));
        boolean jpeg = bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
        ExifReader.ExifData exif = jpeg ? ExifReader.read(bytes) : ExifReader.ExifData.NONE;
        String ref = blobs.put(tenantId, sha, u.mediaType(), bytes);
        return tx.inTransaction(c -> {
            String id = u.evidenceId() == null || u.evidenceId().isBlank() ? FieldSql.newId() : u.evidenceId();
            Optional<EvidenceRecord> existing = find(c, tenantId, id);
            if (existing.isPresent() && !PENDING.equals(existing.get().integrityStatus())) {
                if (existing.get().sha256().equals(sha)) {
                    return existing.get();
                }
                throw new FieldException("IDEMPOTENCY_CONFLICT", "evidence " + id + " was already uploaded with different bytes");
            }
            String declared = u.declaredSha256() != null ? u.declaredSha256() : existing.map(EvidenceRecord::sha256).orElse(null);
            String assignmentId = u.assignmentId() != null ? u.assignmentId() : existing.map(EvidenceRecord::assignmentId).orElse(null);
            String itemId = u.itemId() != null ? u.itemId() : existing.map(EvidenceRecord::itemId).orElse(null);
            String packetId = u.packetId() != null ? u.packetId() : existing.map(EvidenceRecord::packetId).orElse(null);
            Instant capturedAt = u.capturedAt() != null ? u.capturedAt() : existing.map(EvidenceRecord::capturedAt).orElse(null);
            GpsFix fix = u.fix();
            if (fix == null && existing.isPresent() && existing.get().lat() != null) {
                EvidenceRecord e = existing.get();
                fix = new GpsFix(e.lat(), e.lon(), e.accuracyM() == null ? null : e.accuracyM().doubleValue(), null, null, false, null);
            }
            FieldTemplate.EvidenceRules rules = null;
            Instant packetTime = clock.instant();
            if (assignmentId != null) {
                Assignment a = assignments.find(c, tenantId, assignmentId).orElse(null);
                if (a != null) {
                    rules = templates.find(c, tenantId, a.templateKey(), a.templateVersion()).map(t -> t.template().evidence()).orElse(null);
                }
            }
            boolean duplicate = Jdbc.one(c, "SELECT COUNT(*) FROM FLD_EVIDENCE WHERE tenant_id = ? AND sha256 = ? AND evidence_id <> ? "
                    + "AND integrity_status <> ?", rs -> rs.getInt(1), tenantId, sha, id, PENDING).orElse(0) > 0;
            IntegrityReport.Builder b = new IntegrityReport.Builder();
            IntegrityChecks.media(b, declared, sha, exif, capturedAt, fix, packetTime, rules, duplicate, config.maxClockSkewSeconds());
            if (fix != null) {
                IntegrityChecks.fix(b, fix, rules, false);
            }
            IntegrityReport report = b.build();
            Instant now = clock.instant();
            Jdbc.update(c, "DELETE FROM FLD_EVIDENCE WHERE tenant_id = ? AND evidence_id = ?", tenantId, id);
            Jdbc.update(c, "INSERT INTO FLD_EVIDENCE (tenant_id, evidence_id, packet_id, assignment_id, item_id, media_type, sha256, size_bytes, "
                            + "storage_ref, exif_json, captured_at, lat_e7, lon_e7, accuracy_m, integrity_status, integrity_json, uploaded_by, "
                            + "uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, id, packetId, assignmentId, itemId, u.mediaType(), sha, (long) bytes.length, ref, FieldJson.write(exif.toMap()),
                    capturedAt, fix == null ? null : FieldSql.e7(fix.lat()), fix == null ? null : FieldSql.e7(fix.lon()),
                    fix == null ? null : FieldSql.round(fix.accuracyM()), report.status().name(), FieldJson.write(report.checks()), actor, now);
            String root = assignmentId != null ? assignmentId : "evidence:" + id;
            audit.record(c, tenantId, root, FieldAudit.EVIDENCE_STORED, actor, Map.of("evidenceId", id, "sha256", sha,
                    "status", report.status().name()));
            if (report.status() != IntegrityReport.Status.VERIFIED) {
                audit.record(c, tenantId, root, FieldAudit.INTEGRITY_FLAGGED, actor, Map.of("evidenceId", id,
                        "problems", report.problems().stream().map(IntegrityReport.Check::code).toList()));
            }
            return find(c, tenantId, id).orElseThrow();
        });
    }

    /** Registers the media declared in a packet as PENDING_UPLOAD rows (bytes follow separately). */
    public void registerDeclared(Connection c, String tenantId, String packetId, String assignmentId, List<MediaRef> media, String actor) {
        Instant now = clock.instant();
        for (MediaRef m : media) {
            if (m.evidenceId() == null || m.sha256() == null) {
                throw new FieldException("INVALID_PACKET", "declared media needs evidenceId and sha256");
            }
            if (find(c, tenantId, m.evidenceId()).isPresent()) {
                continue;
            }
            GpsFix f = m.fix();
            Jdbc.update(c, "INSERT INTO FLD_EVIDENCE (tenant_id, evidence_id, packet_id, assignment_id, item_id, media_type, sha256, size_bytes, "
                            + "storage_ref, captured_at, lat_e7, lon_e7, accuracy_m, integrity_status, uploaded_by, uploaded_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, m.evidenceId(), packetId, assignmentId, m.itemId(), m.mediaType(), m.sha256().toLowerCase(java.util.Locale.ROOT),
                    m.sizeBytes() == null ? 0L : m.sizeBytes(), "pending:" + m.sha256(), m.capturedAt(), f == null ? null : FieldSql.e7(f.lat()),
                    f == null ? null : FieldSql.e7(f.lon()), f == null ? null : FieldSql.round(f.accuracyM()), PENDING, actor, now);
        }
    }

    /** Evidence count per template item for an assignment (checklist minPhotos validation). */
    public Map<String, Integer> countsByItem(Connection c, String tenantId, String assignmentId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Jdbc.query(c, "SELECT item_id, COUNT(*) AS n FROM FLD_EVIDENCE WHERE tenant_id = ? AND assignment_id = ? AND item_id IS NOT NULL "
                + "AND integrity_status <> 'REJECTED' GROUP BY item_id", rs -> out.put(rs.getString(1), rs.getInt(2)), tenantId, assignmentId);
        return out;
    }

    public List<EvidenceRecord> listForAssignment(String tenantId, String assignmentId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_EVIDENCE WHERE tenant_id = ? AND assignment_id = ? ORDER BY uploaded_at",
                EvidenceService::map, tenantId, assignmentId));
    }

    public EvidenceRecord get(String tenantId, String id) {
        return tx.inTransaction(c -> find(c, tenantId, id)).orElseThrow(() -> FieldException.notFound("evidence", id));
    }

    public Optional<byte[]> content(String tenantId, String id) {
        EvidenceRecord e = get(tenantId, id);
        return PENDING.equals(e.integrityStatus()) ? Optional.empty() : blobs.get(tenantId, e.storageRef());
    }

    /** Re-hashes stored bytes against the evidence row (tamper evidence at rest). */
    public boolean reverify(String tenantId, String id) {
        EvidenceRecord e = get(tenantId, id);
        return blobs.get(tenantId, e.storageRef()).map(b -> Hashes.hex(Hashes.sha256(b)).equals(e.sha256())).orElse(false);
    }

    /**
     * Verifies a device's packet chain: every stored packet must re-hash to its packet_hash, and prev_hash must equal
     * the previous packet's hash in device_seq order. Gaps in device_seq are reported as GAP (lost or withheld packets).
     */
    public List<ChainBreak> verifyChain(String tenantId, String deviceId) {
        return tx.inTransaction(c -> {
            record Row(String id, long seq, String hash, String prev, String doc) {
            }
            List<Row> rows = Jdbc.query(c, "SELECT packet_id, device_seq, packet_hash, prev_hash, doc_json FROM FLD_PACKET "
                    + "WHERE tenant_id = ? AND device_id = ? ORDER BY device_seq", rs -> new Row(rs.getString(1), rs.getLong(2),
                    rs.getString(3), rs.getString(4), rs.getString(5)), tenantId, deviceId);
            List<ChainBreak> out = new ArrayList<>();
            Row prev = null;
            for (Row r : rows) {
                CapturePacket p = FieldJson.read(r.doc(), CapturePacket.class);
                if (!PacketHasher.hash(p).equals(r.hash())) {
                    out.add(new ChainBreak(r.id(), r.seq(), "HASH", "stored packet does not re-hash to its packet hash"));
                }
                if (prev != null) {
                    if (r.seq() != prev.seq() + 1) {
                        out.add(new ChainBreak(r.id(), r.seq(), "GAP", "device_seq jumps from " + prev.seq() + " to " + r.seq()));
                    }
                    if (r.prev() == null || !r.prev().equals(prev.hash())) {
                        out.add(new ChainBreak(r.id(), r.seq(), "LINK", "prev_hash does not match the previous packet"));
                    }
                }
                prev = r;
            }
            return out;
        });
    }

    Optional<EvidenceRecord> find(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_EVIDENCE WHERE tenant_id = ? AND evidence_id = ?", EvidenceService::map, tenantId, id);
    }

    private static EvidenceRecord map(ResultSet rs) throws SQLException {
        Long la = FieldSql.lng(rs, "lat_e7"), lo = FieldSql.lng(rs, "lon_e7");
        String integ = rs.getString("integrity_json");
        return new EvidenceRecord(rs.getString("evidence_id"), rs.getString("packet_id"), rs.getString("assignment_id"),
                rs.getString("item_id"), rs.getString("media_type"), rs.getString("sha256"), rs.getLong("size_bytes"),
                rs.getString("storage_ref"), FieldJson.map(rs.getString("exif_json")), Jdbc.instant(rs, "captured_at"),
                la == null ? null : la / 1e7, lo == null ? null : lo / 1e7, FieldSql.lng(rs, "accuracy_m"), rs.getString("integrity_status"),
                integ == null ? Map.of() : Map.of("checks", FieldJson.read(integ, Object.class)), rs.getString("uploaded_by"),
                Jdbc.instant(rs, "uploaded_at"));
    }
}
