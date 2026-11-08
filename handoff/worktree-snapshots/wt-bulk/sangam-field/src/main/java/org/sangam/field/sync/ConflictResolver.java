// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.schedule.Assignment;
import org.sangam.field.support.FieldSql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline conflict resolution. A device captured against assignment version {@code baseVersion}; by the time it syncs
 * the server copy may have moved on. Field ownership decides:
 * <ul>
 *   <li><b>administrative fields</b> (assignee, due date, window, fence, template version) are server-owned: the
 *       server value always wins and is pushed back on the next pull;</li>
 *   <li><b>capture fields</b> (answers, notes, media, fix) are client-owned: the client value wins, so an admin edit of
 *       the due date never discards a completed visit — recorded as AUTO_MERGED;</li>
 *   <li>the assignment was <b>cancelled</b>, or <b>reassigned</b> to someone else → REJECTED_STALE, held for review;</li>
 *   <li>a visit was <b>already submitted</b> (from another device, or the same one under a new operationId) →
 *       DUPLICATE_SUBMISSION, held for review; nothing is overwritten silently.</li>
 * </ul>
 */
public final class ConflictResolver {

    public enum Kind { AUTO_MERGED, REJECTED_STALE, DUPLICATE_SUBMISSION }

    public enum Resolution { ACCEPT_CLIENT, KEEP_SERVER, DISCARD }

    public record Decision(boolean proceed, Kind kind, String reason) {
        static final Decision OK = new Decision(true, null, null);
    }

    public record Conflict(String id, String assignmentId, String operationId, String deviceId, Kind kind, Map<String, Object> client,
                           Map<String, Object> server, String status, String resolution, String resolvedBy, Instant resolvedAt,
                           String note, Instant createdAt) {
    }

    private final Transactions tx;
    private final FieldAudit audit;
    private final Clock clock;

    public ConflictResolver(Transactions tx, FieldAudit audit, Clock clock) {
        this.tx = tx;
        this.audit = audit;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static Decision decide(Assignment server, Integer baseVersion, String deviceId, String principalId) {
        if (server.status() == Assignment.Status.CANCELLED) {
            return new Decision(false, Kind.REJECTED_STALE, "assignment was cancelled");
        }
        if (server.assignee() != null && principalId != null && !server.assignee().equals(principalId)) {
            return new Decision(false, Kind.REJECTED_STALE, "assignment was reassigned to another inspector");
        }
        if (server.status() == Assignment.Status.SUBMITTED || server.status() == Assignment.Status.ACCEPTED) {
            String from = Objects.equals(server.submittedDeviceId(), deviceId) ? "this device" : "device " + server.submittedDeviceId();
            return new Decision(false, Kind.DUPLICATE_SUBMISSION, "a visit was already submitted from " + from);
        }
        if (baseVersion != null && baseVersion < server.version()) {
            return new Decision(true, Kind.AUTO_MERGED, "server changed administrative fields since version " + baseVersion
                    + "; capture fields kept from the device");
        }
        return Decision.OK;
    }

    public String raise(Connection c, String tenantId, Decision d, String assignmentId, String operationId, String deviceId,
                        Map<String, Object> client, Map<String, Object> server, String actor) {
        String id = FieldSql.newId();
        String status = d.proceed() ? "AUTO_RESOLVED" : "OPEN";
        Jdbc.update(c, "INSERT INTO FLD_CONFLICT (tenant_id, conflict_id, assignment_id, operation_id, device_id, kind, client_json, server_json, "
                        + "status, resolution, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, id, assignmentId, operationId, deviceId, d.kind(), FieldJson.write(client), FieldJson.write(server), status,
                d.proceed() ? "ACCEPT_CLIENT" : null, d.reason(), clock.instant());
        audit.record(c, tenantId, assignmentId == null ? "conflict:" + id : assignmentId, FieldAudit.CONFLICT_RAISED, actor,
                Map.of("conflictId", id, "kind", d.kind().name(), "status", status, "reason", d.reason()));
        return id;
    }

    public void markResolved(Connection c, String tenantId, String conflictId, Resolution r, String actor, String note) {
        int n = Jdbc.update(c, "UPDATE FLD_CONFLICT SET status = 'RESOLVED', resolution = ?, resolved_by = ?, resolved_at = ?, note = ? "
                + "WHERE tenant_id = ? AND conflict_id = ? AND status = 'OPEN'", r, actor, clock.instant(), note, tenantId, conflictId);
        if (n == 0) {
            throw new FieldException("CONFLICT", "conflict " + conflictId + " is not open");
        }
        Conflict cf = require(c, tenantId, conflictId);
        audit.record(c, tenantId, cf.assignmentId() == null ? "conflict:" + conflictId : cf.assignmentId(), FieldAudit.CONFLICT_RESOLVED,
                actor, Map.of("conflictId", conflictId, "resolution", r.name()));
    }

    public List<Conflict> list(String tenantId, String status, int limit) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_CONFLICT WHERE tenant_id = ?" + (status == null ? "" : " AND status = ?")
                        + " ORDER BY created_at DESC", ConflictResolver::map, status == null ? new Object[]{tenantId} : new Object[]{tenantId, status}))
                .stream().limit(Math.max(1, Math.min(500, limit <= 0 ? 100 : limit))).toList();
    }

    public Conflict get(String tenantId, String id) {
        return tx.inTransaction(c -> require(c, tenantId, id));
    }

    public Conflict require(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_CONFLICT WHERE tenant_id = ? AND conflict_id = ?", ConflictResolver::map, tenantId, id)
                .orElseThrow(() -> FieldException.notFound("conflict", id));
    }

    private static Conflict map(ResultSet rs) throws SQLException {
        return new Conflict(rs.getString("conflict_id"), rs.getString("assignment_id"), rs.getString("operation_id"), rs.getString("device_id"),
                Kind.valueOf(rs.getString("kind")), FieldJson.map(rs.getString("client_json")), FieldJson.map(rs.getString("server_json")),
                rs.getString("status"), rs.getString("resolution"), rs.getString("resolved_by"), Jdbc.instant(rs, "resolved_at"),
                rs.getString("note"), Jdbc.instant(rs, "created_at"));
    }
}
