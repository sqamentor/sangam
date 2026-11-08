// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldConfig;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.dispatch.DispatchModel;
import org.sangam.field.dispatch.DispatchService;
import org.sangam.field.evidence.CapturePacket;
import org.sangam.field.evidence.EvidenceService;
import org.sangam.field.evidence.IntegrityReport;
import org.sangam.field.evidence.PacketHasher;
import org.sangam.field.schedule.Assignment;
import org.sangam.field.schedule.AssignmentService;
import org.sangam.field.support.FieldSql;
import org.sangam.field.template.TemplateRegistry;
import org.sangam.field.visit.VisitService;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline sync protocol, server side (N05; R41 idempotency keyed by operationId; R38 inbox semantics).
 *
 * <p><b>Upload.</b> Each packet is processed in its own transaction, so one bad packet never blocks the queue:
 * <ol>
 *   <li>the device must be ACTIVE and belong to the caller;</li>
 *   <li>(tenant, operationId) is claimed in FLD_SYNC_OP with the packet hash: a replay with the same hash returns the
 *       stored result (REPLAY); the same operationId with a different hash is refused (CONFLICT, R41);</li>
 *   <li>the packet must re-hash to its declared {@code packetHash} (else REJECTED: HASH_MISMATCH);</li>
 *   <li>signature, device chain (prevHash / deviceSeq) and late-sync checks become integrity checks on the packet;</li>
 *   <li>the packet is routed by type; a business rejection rolls back to a savepoint and is recorded as REJECTED (the
 *       device keeps the entry and shows the reason), so the answer is stable for replays;</li>
 *   <li>the packet itself is stored in FLD_PACKET (tamper-evident chain) and the device chain head advances.</li>
 * </ol>
 * <p><b>Pull.</b> The device sends its last change cursor and gets every assignment / template / job / offer change
 * visible to its principal or unit since then, plus revocations (reassigned away) and its own status and policy.
 */
public final class SyncService {

    public enum Status { NEW, REPLAY, CONFLICT, REJECTED }

    public record SyncOutcome(String operationId, String packetId, Status status, String code, Map<String, Object> result) {
    }

    public record PullResult(long cursor, boolean hasMore, String deviceStatus, QueuePolicy policy, List<Map<String, Object>> assignments,
                             List<String> revokedAssignmentIds, List<Map<String, Object>> templates, List<Map<String, Object>> jobs,
                             List<Map<String, Object>> offers) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final DeviceRegistry devices;
    private final VisitService visits;
    private final AssignmentService assignments;
    private final TemplateRegistry templates;
    private final EvidenceService evidence;
    private final DispatchService dispatch;
    private final FieldAudit audit;
    private final FieldConfig config;
    private final Clock clock;

    public SyncService(Transactions tx, Dialect dialect, DeviceRegistry devices, VisitService visits, AssignmentService assignments,
                       TemplateRegistry templates, EvidenceService evidence, DispatchService dispatch, FieldAudit audit, FieldConfig config,
                       Clock clock) {
        this.tx = tx;
        this.dialect = dialect;
        this.devices = devices;
        this.visits = visits;
        this.assignments = assignments;
        this.templates = templates;
        this.evidence = evidence;
        this.dispatch = dispatch;
        this.audit = audit;
        this.config = config == null ? FieldConfig.defaults() : config;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<SyncOutcome> upload(String tenantId, String principalId, String deviceId, List<CapturePacket> packets) {
        List<CapturePacket> ordered = new ArrayList<>(packets == null ? List.of() : packets);
        ordered.sort(java.util.Comparator.comparingLong(CapturePacket::deviceSeq));
        List<SyncOutcome> out = new ArrayList<>(ordered.size());
        for (CapturePacket p : ordered) {
            out.add(tx.inTransaction(c -> one(c, tenantId, principalId, deviceId, p)));
        }
        return out;
    }

    private SyncOutcome one(Connection c, String tenantId, String principalId, String deviceId, CapturePacket p) {
        DeviceRegistry.Device d = devices.requireActive(c, tenantId, deviceId, principalId);
        if (p.operationId() == null || p.operationId().isBlank()) {
            return new SyncOutcome(null, p.packetId(), Status.REJECTED, "OPERATION_ID_REQUIRED", Map.of());
        }
        if (!deviceId.equals(p.deviceId())) {
            return new SyncOutcome(p.operationId(), p.packetId(), Status.REJECTED, "DEVICE_MISMATCH", Map.of());
        }
        String computed = PacketHasher.hash(p);
        Instant now = clock.instant();
        boolean claimed = Jdbc.insertIfAbsent(c, dialect, "INSERT INTO FLD_SYNC_OP (tenant_id, operation_id, device_id, packet_type, request_hash, "
                + "status, received_at) VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?)", tenantId, p.operationId(), deviceId, String.valueOf(p.type()),
                computed, now);
        if (!claimed) {
            record Prior(String hash, String status, String result) {
            }
            Prior prior = Jdbc.one(c, "SELECT request_hash, status, result_json FROM FLD_SYNC_OP WHERE tenant_id = ? AND operation_id = ?",
                    rs -> new Prior(rs.getString(1), rs.getString(2), rs.getString(3)), tenantId, p.operationId()).orElseThrow();
            if (!prior.hash().equals(computed)) {
                return new SyncOutcome(p.operationId(), p.packetId(), Status.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        Map.of("detail", "operationId reused with a different packet"));
            }
            Map<String, Object> stored = FieldJson.map(prior.result());
            Object code = stored.get("code");
            return new SyncOutcome(p.operationId(), p.packetId(), Status.REPLAY, code == null ? prior.status() : code.toString(), stored);
        }
        String packetId = p.packetId() == null || p.packetId().isBlank() ? p.operationId() : p.packetId();
        Map<String, Object> result = new LinkedHashMap<>();
        Status status;
        String code;
        if (!computed.equals(p.packetHash())) {
            status = Status.REJECTED;
            code = "HASH_MISMATCH";
            result.put("expected", computed);
        } else {
            List<IntegrityReport.Check> checks = transportChecks(d, p, now);
            Savepoint sp = savepoint(c);
            try {
                result.putAll(route(c, tenantId, principalId, d, p, packetId, checks, now));
                status = Status.NEW;
                code = String.valueOf(result.getOrDefault("code", "OK"));
                storePacket(c, tenantId, d, p, packetId, computed, now, checks, result);
                devices.advance(c, tenantId, deviceId, p.deviceSeq(), computed);
            } catch (FieldException e) {
                rollback(c, sp);
                status = Status.REJECTED;
                code = e.code();
                result.put("detail", e.getMessage());
            }
        }
        result.put("status", status.name());
        result.put("code", code);
        Jdbc.update(c, "UPDATE FLD_SYNC_OP SET status = ?, result_json = ? WHERE tenant_id = ? AND operation_id = ?", status,
                FieldJson.write(result), tenantId, p.operationId());
        audit.record(c, tenantId, p.assignmentId() != null ? p.assignmentId() : "device:" + deviceId, FieldAudit.PACKET_RECEIVED, principalId,
                Map.of("operationId", p.operationId(), "type", String.valueOf(p.type()), "status", status.name(), "code", code,
                        "packetHash", computed));
        return new SyncOutcome(p.operationId(), packetId, status, code, result);
    }

    private List<IntegrityReport.Check> transportChecks(DeviceRegistry.Device d, CapturePacket p, Instant now) {
        IntegrityReport.Builder b = new IntegrityReport.Builder();
        if (p.signature() != null && !p.signature().isBlank()) {
            if (PacketHasher.verifySignature(d.publicKey(), d.keyAlg(), p.packetHash(), p.signature())) {
                b.pass("SIGNATURE", "device signature valid");
            } else {
                b.fail("SIGNATURE_INVALID", "device signature does not verify with the registered key");
            }
        } else if (d.policy().requireSignature()) {
            b.fail("SIGNATURE_MISSING", "device policy requires signed packets");
        }
        if (p.deviceSeq() == d.lastSeq() + 1) {
            if (d.lastPacketHash() != null && !d.lastPacketHash().equals(p.prevHash())) {
                b.fail("CHAIN_BROKEN", "prevHash does not match the last packet received from this device");
            } else {
                b.pass("CHAIN", "links to the previous packet");
            }
        } else if (p.deviceSeq() <= d.lastSeq()) {
            b.warn("OUT_OF_ORDER", "deviceSeq " + p.deviceSeq() + " <= last " + d.lastSeq());
        } else {
            b.warn("CHAIN_GAP", "deviceSeq jumps from " + d.lastSeq() + " to " + p.deviceSeq() + " (missing packets)");
        }
        if (p.deviceTime() != null && Duration.between(p.deviceTime(), now).toHours() > d.policy().maxAgeHours()) {
            b.warn("LATE_SYNC", "packet queued for more than " + d.policy().maxAgeHours() + " h");
        }
        return b.build().checks();
    }

    private Map<String, Object> route(Connection c, String tenantId, String principalId, DeviceRegistry.Device d, CapturePacket p,
                                      String packetId, List<IntegrityReport.Check> checks, Instant now) {
        String type = p.type() == null ? "" : p.type();
        Map<String, Object> pl = p.payload();
        switch (type) {
            case "VISIT_SUBMIT" -> {
                require(p.assignmentId() != null, "assignmentId is required");
                VisitService.VisitResult r = visits.submit(c, tenantId, principalId, new VisitService.Submission(p.assignmentId(), packetId,
                        p.operationId(), d.deviceId(), p.baseVersion(), p.answers(), p.notes(), p.media(), p.fix(), p.deviceTime(), now,
                        checks), false);
                Map<String, Object> m = new LinkedHashMap<>(r.toMap());
                m.put("code", r.outcome().name());
                if (r.outcome() == VisitService.Outcome.INVALID) {
                    throw new FieldException("CHECKLIST_INVALID", "checklist validation failed: " + r.violations());
                }
                return m;
            }
            case "EVIDENCE_META" -> {
                require(p.assignmentId() != null, "assignmentId is required");
                evidence.registerDeclared(c, tenantId, packetId, p.assignmentId(), p.media(), principalId);
                return Map.of("registered", p.media().size());
            }
            case "ASSIGNMENT_ACK" -> {
                require(p.assignmentId() != null, "assignmentId is required");
                Assignment a = assignments.get(c, tenantId, p.assignmentId());
                if (a.assignee() != null && !a.assignee().equals(principalId)) {
                    throw new FieldException("STALE", "assignment was reassigned");
                }
                if (a.status() == Assignment.Status.ASSIGNED || a.status() == Assignment.Status.OVERDUE) {
                    a = assignments.transition(c, tenantId, a.id(), null, Assignment.Status.IN_PROGRESS, principalId, "acknowledged on device");
                }
                return Map.of("assignmentStatus", a.status().name(), "version", a.version());
            }
            case "DISPATCH_STATUS" -> {
                require(d.unitId() != null, "device is not bound to a dispatch unit");
                if (pl.get("offerId") != null) {
                    DispatchModel.Offer o = dispatch.getOfferIn(c, tenantId, pl.get("offerId").toString());
                    if (!d.unitId().equals(o.unitId())) {
                        throw new FieldException("FORBIDDEN", "offer is for another unit");
                    }
                    DispatchModel.Offer r = dispatch.respond(c, tenantId, o.id(), Boolean.TRUE.equals(pl.get("accept")),
                            pl.get("reason") == null ? null : pl.get("reason").toString(), principalId);
                    return Map.of("offerStatus", r.status().name());
                }
                String jobId = String.valueOf(pl.get("jobId"));
                DispatchModel.JobStatus to = DispatchModel.JobStatus.valueOf(String.valueOf(pl.get("status")));
                DispatchModel.Job j = dispatch.getJobIn(c, tenantId, jobId);
                if (!d.unitId().equals(j.assignedUnitId())) {
                    throw new FieldException("FORBIDDEN", "job is assigned to another unit");
                }
                if (p.fix() != null) {
                    dispatch.ping(c, tenantId, new DispatchModel.PositionFix(d.unitId(), p.deviceTime() == null ? now : p.deviceTime(),
                            p.fix().lat(), p.fix().lon(), p.fix().accuracyM(), null, null, "device"), principalId);
                    j = dispatch.getJobIn(c, tenantId, jobId);
                }
                if (to == DispatchModel.JobStatus.CLOSED) {
                    throw new FieldException("INVALID_TRANSITION", "closure is done by the dispatcher with proof of service");
                }
                DispatchModel.Job r = j.status() == to ? j : dispatch.progress(c, tenantId, jobId, to, principalId);
                return Map.of("jobStatus", r.status().name());
            }
            case "POSITION_PING" -> {
                require(d.unitId() != null, "device is not bound to a dispatch unit");
                double lat = p.fix() != null ? p.fix().lat() : num(pl.get("lat"));
                double lon = p.fix() != null ? p.fix().lon() : num(pl.get("lon"));
                Double acc = p.fix() != null ? p.fix().accuracyM() : (pl.get("accuracyM") == null ? null : num(pl.get("accuracyM")));
                DispatchModel.Unit u = dispatch.ping(c, tenantId, new DispatchModel.PositionFix(d.unitId(),
                        p.deviceTime() == null ? now : p.deviceTime(), lat, lon, acc,
                        pl.get("speedMps") == null ? null : num(pl.get("speedMps")),
                        pl.get("headingDeg") == null ? null : (int) num(pl.get("headingDeg")), "device"), principalId);
                return Map.of("unitStatus", u.status().name());
            }
            default -> throw new FieldException("UNKNOWN_PACKET_TYPE", "unknown packet type: " + type);
        }
    }

    private void storePacket(Connection c, String tenantId, DeviceRegistry.Device d, CapturePacket p, String packetId, String hash,
                             Instant now, List<IntegrityReport.Check> checks, Map<String, Object> result) {
        Object integ = result.get("integrity");
        String status = integ instanceof Map<?, ?> m && m.get("status") != null ? m.get("status").toString()
                : checks.stream().anyMatch(x -> x.outcome() != IntegrityReport.Outcome.PASS) ? "FLAGGED" : "VERIFIED";
        Boolean sigOk = p.signature() == null ? null : checks.stream().anyMatch(x -> x.code().equals("SIGNATURE"));
        Jdbc.insertIfAbsent(c, dialect, "INSERT INTO FLD_PACKET (tenant_id, packet_id, assignment_id, device_id, operation_id, packet_type, device_seq, "
                        + "packet_hash, prev_hash, signature, signature_ok, device_time, received_at, lat_e7, lon_e7, accuracy_m, mock_location, "
                        + "integrity_status, integrity_json, doc_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, packetId, p.assignmentId(), d.deviceId(), p.operationId(), p.type(), p.deviceSeq(), hash, p.prevHash(), p.signature(),
                sigOk, p.deviceTime(), now, p.fix() == null ? null : FieldSql.e7(p.fix().lat()), p.fix() == null ? null : FieldSql.e7(p.fix().lon()),
                p.fix() == null ? null : FieldSql.round(p.fix().accuracyM()), p.fix() != null && p.fix().mockLocation(), status,
                FieldJson.write(checks), FieldJson.write(p));
    }

    // ------------------------------------------------------------------ pull

    public PullResult pull(String tenantId, String principalId, String deviceId, long cursor) {
        return tx.inTransaction(c -> {
            DeviceRegistry.Device d = devices.requireActive(c, tenantId, deviceId, principalId);
            List<Object> params = new ArrayList<>(List.of(tenantId, cursor, principalId));
            String unitClause = "";
            if (d.unitId() != null) {
                unitClause = " OR principal_id = ?";
                params.add(d.unitId());
            }
            record Change(long seq, String kind, String id, String op) {
            }
            List<Change> changes = Jdbc.query(c, "SELECT change_seq, entity_kind, entity_id, op FROM FLD_CHANGE WHERE tenant_id = ? AND change_seq > ? "
                            + "AND (principal_id IS NULL OR principal_id = ?" + unitClause + ") ORDER BY change_seq",
                    rs -> new Change(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), params.toArray());
            boolean more = changes.size() > config.pullPageSize();
            if (more) {
                changes = changes.subList(0, config.pullPageSize());
            }
            Set<String> asg = new LinkedHashSet<>(), revoked = new LinkedHashSet<>(), tpl = new LinkedHashSet<>(), jobs = new LinkedHashSet<>(),
                    offers = new LinkedHashSet<>();
            long last = cursor;
            for (Change ch : changes) {
                last = ch.seq();
                switch (ch.kind()) {
                    case "ASSIGNMENT" -> {
                        if ("REVOKE".equals(ch.op())) {
                            revoked.add(ch.id());
                            asg.remove(ch.id());
                        } else {
                            asg.add(ch.id());
                            revoked.remove(ch.id());
                        }
                    }
                    case "TEMPLATE" -> tpl.add(ch.id());
                    case "JOB" -> jobs.add(ch.id());
                    case "OFFER" -> offers.add(ch.id());
                    default -> {
                    }
                }
            }
            List<Map<String, Object>> aOut = new ArrayList<>();
            for (String id : asg) {
                assignments.find(c, tenantId, id).ifPresent(a -> {
                    if (principalId.equals(a.assignee())) {
                        aOut.add(a.view());
                    } else {
                        revoked.add(a.id());
                    }
                });
            }
            List<Map<String, Object>> tOut = new ArrayList<>();
            for (String key : tpl) {
                int i = key.lastIndexOf(':');
                templates.find(c, tenantId, key.substring(0, i), Integer.parseInt(key.substring(i + 1))).ifPresent(r -> {
                    if (r.offlineCapable()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("key", r.key());
                        m.put("version", r.version());
                        m.put("status", r.status());
                        m.put("contentHash", r.contentHash());
                        m.put("template", r.template());
                        tOut.add(m);
                    }
                });
            }
            List<Map<String, Object>> jOut = new ArrayList<>();
            for (String id : jobs) {
                DispatchModel.Job j = dispatch.findJobIn(c, tenantId, id);
                if (j != null && d.unitId() != null && d.unitId().equals(j.assignedUnitId())) {
                    jOut.add(FieldJson.toMap(j));
                }
            }
            List<Map<String, Object>> oOut = new ArrayList<>();
            for (String id : offers) {
                DispatchModel.Offer o = dispatch.getOfferIn(c, tenantId, id);
                if (o.status() == DispatchModel.OfferStatus.OFFERED && o.unitId().equals(d.unitId())) {
                    oOut.add(FieldJson.toMap(o));
                }
            }
            devices.touch(c, tenantId, deviceId);
            return new PullResult(last, more, d.status().name(), d.policy(), aOut, List.copyOf(revoked), tOut, jOut, oOut);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new FieldException("INVALID_PACKET", message);
        }
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            throw new FieldException("INVALID_PACKET", "number expected");
        }
        return Double.parseDouble(o.toString());
    }

    private static Savepoint savepoint(Connection c) {
        try {
            return c.setSavepoint();
        } catch (SQLException e) {
            throw new FieldException("SYNC_FAILED", "savepoint not supported: " + e.getMessage(), e);
        }
    }

    private static void rollback(Connection c, Savepoint sp) {
        try {
            c.rollback(sp);
        } catch (SQLException e) {
            throw new FieldException("SYNC_FAILED", "savepoint rollback failed: " + e.getMessage(), e);
        }
    }
}
