// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldAudit;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.ports.FieldPorts.FieldEvents;
import org.sangam.field.schedule.Assignment.Status;
import org.sangam.field.support.FieldSql;
import org.sangam.field.template.FieldTemplate;
import org.sangam.field.template.TemplateRegistry;
import org.sangam.geo.model.GeoPoint;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Assignment lifecycle (N05): create, assign/reassign, start, submit, accept/reject, cancel, overdue sweep. */
public final class AssignmentService {

    /** Input for a new assignment. {@code templateVersion} null = latest active. */
    public record NewAssignment(String templateKey, Integer templateVersion, String targetRef, String targetLabel, String address,
                                Double lat, Double lon, String assignee, String seatId, Instant dueAt, Instant windowStart,
                                Instant windowEnd, String taskId, String businessKey, Map<String, Object> attributes) {
    }

    private final Transactions tx;
    private final Dialect dialect;
    private final TemplateRegistry templates;
    private final FieldAudit audit;
    private final FieldEvents events;
    private final Clock clock;

    public AssignmentService(Transactions tx, Dialect dialect, TemplateRegistry templates, FieldAudit audit, FieldEvents events,
                             Clock clock) {
        this.tx = tx;
        this.dialect = dialect;
        this.templates = templates;
        this.audit = audit;
        this.events = events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Assignment create(String tenantId, NewAssignment in, String actor) {
        return tx.inTransaction(c -> create(c, tenantId, in, null, null, actor));
    }

    Assignment create(Connection c, String tenantId, NewAssignment in, String scheduleId, String selectionId, String actor) {
        if (in.targetRef() == null || in.targetRef().isBlank()) {
            throw new FieldException("INVALID_REQUEST", "targetRef is required");
        }
        TemplateRegistry.TemplateRow tpl = templates.find(c, tenantId, in.templateKey(), in.templateVersion())
                .orElseThrow(() -> FieldException.notFound("template", in.templateKey()));
        GeoPoint loc = in.lat() == null || in.lon() == null ? null : new GeoPoint(in.lat(), in.lon());
        FieldTemplate t = tpl.template();
        FenceSpec fence = FenceSpec.from(t.geofence(), loc);
        if (t.geofence() != null && t.geofence().required() && fence == null) {
            throw new FieldException("INVALID_REQUEST", "template requires a geofence: give the target location or a featureRef");
        }
        Instant now = clock.instant();
        String id = FieldSql.newId();
        Status status = in.assignee() == null || in.assignee().isBlank() ? Status.PLANNED : Status.ASSIGNED;
        Jdbc.update(c, "INSERT INTO FLD_ASSIGNMENT (tenant_id, assignment_id, template_key, template_version, target_ref, target_label, "
                        + "address, lat_e7, lon_e7, fence_json, assignee, seat_id, status, due_at, window_start, window_end, schedule_id, "
                        + "selection_id, task_id, business_key, attributes_json, version, created_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
                tenantId, id, tpl.key(), tpl.version(), in.targetRef(), in.targetLabel(), in.address(), FieldSql.e7lat(loc),
                FieldSql.e7lon(loc), FieldJson.write(fence), in.assignee(), in.seatId(), status, in.dueAt(), in.windowStart(),
                in.windowEnd(), scheduleId, selectionId, in.taskId(), in.businessKey(),
                in.attributes() == null ? null : FieldJson.write(in.attributes()), actor, now, now);
        FieldSql.recordChange(c, dialect, tenantId, "ASSIGNMENT", id, in.assignee(), "UPSERT", now);
        audit.record(c, tenantId, id, FieldAudit.ASSIGNMENT_CREATED, actor, Map.of("template", tpl.key() + ":" + tpl.version(),
                "targetRef", in.targetRef(), "status", status.name()));
        events.publish(c, tenantId, "field.assignment.created", id, Map.of("targetRef", in.targetRef(), "status", status.name()));
        return get(c, tenantId, id);
    }

    public Assignment get(String tenantId, String id) {
        return tx.inTransaction(c -> get(c, tenantId, id));
    }

    public Assignment get(Connection c, String tenantId, String id) {
        return find(c, tenantId, id).orElseThrow(() -> FieldException.notFound("assignment", id));
    }

    public Optional<Assignment> find(Connection c, String tenantId, String id) {
        return Jdbc.one(c, "SELECT * FROM FLD_ASSIGNMENT WHERE tenant_id = ? AND assignment_id = ?", AssignmentService::map, tenantId, id);
    }

    /** Lists by assignee and/or status; {@code limit} capped at 500. */
    public List<Assignment> list(String tenantId, String assignee, Status status, String targetRef, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM FLD_ASSIGNMENT WHERE tenant_id = ?");
        List<Object> p = new ArrayList<>(List.of(tenantId));
        if (assignee != null) {
            sql.append(" AND assignee = ?");
            p.add(assignee);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            p.add(status.name());
        }
        if (targetRef != null) {
            sql.append(" AND target_ref = ?");
            p.add(targetRef);
        }
        sql.append(" ORDER BY due_at, assignment_id");
        int lim = Math.max(1, Math.min(500, limit <= 0 ? 50 : limit));
        int off = Math.max(0, offset);
        return tx.inTransaction(c -> {
            List<Assignment> all = Jdbc.query(c, sql.toString(), AssignmentService::map, p.toArray());
            return all.subList(Math.min(off, all.size()), Math.min(off + lim, all.size()));
        });
    }

    /** Status change with optimistic concurrency ({@code expectedVersion} null = no check). */
    public Assignment transition(String tenantId, String id, Integer expectedVersion, Status to, String actor, String reason) {
        return tx.inTransaction(c -> transition(c, tenantId, id, expectedVersion, to, actor, reason));
    }

    public Assignment transition(Connection c, String tenantId, String id, Integer expectedVersion, Status to, String actor,
                                 String reason) {
        Assignment a = get(c, tenantId, id);
        a.status().check(to);
        update(c, a, expectedVersion, "status = ?", to.name());
        Instant now = clock.instant();
        FieldSql.recordChange(c, dialect, tenantId, "ASSIGNMENT", id, a.assignee(), "UPSERT", now);
        audit.record(c, tenantId, id, FieldAudit.ASSIGNMENT_STATUS, actor,
                Map.of("from", a.status().name(), "to", to.name(), "reason", reason == null ? "" : reason));
        events.publish(c, tenantId, "field.assignment." + to.name().toLowerCase(java.util.Locale.ROOT), id, Map.of("from", a.status().name()));
        return get(c, tenantId, id);
    }

    public Assignment reassign(String tenantId, String id, Integer expectedVersion, String assignee, String seatId, String actor) {
        return tx.inTransaction(c -> {
            Assignment a = get(c, tenantId, id);
            Status to = a.status() == Status.PLANNED ? Status.ASSIGNED : a.status();
            if (!a.status().open() && a.status() != Status.PLANNED) {
                throw new FieldException("INVALID_TRANSITION", "assignment in " + a.status() + " cannot be reassigned");
            }
            update(c, a, expectedVersion, "assignee = ?, seat_id = ?, status = ?", assignee, seatId, to.name());
            Instant now = clock.instant();
            // both the old and the new assignee's devices must see the change (the old one drops the assignment)
            FieldSql.recordChange(c, dialect, tenantId, "ASSIGNMENT", id, a.assignee(), "REVOKE", now);
            FieldSql.recordChange(c, dialect, tenantId, "ASSIGNMENT", id, assignee, "UPSERT", now);
            audit.record(c, tenantId, id, FieldAudit.ASSIGNMENT_STATUS, actor,
                    Map.of("reassignedFrom", a.assignee() == null ? "" : a.assignee(), "reassignedTo", assignee));
            return get(c, tenantId, id);
        });
    }

    /** Records the accepted submission (called by the visit service in the sync transaction). */
    public void markSubmitted(Connection c, Assignment a, String packetId, String deviceId, Map<String, Object> score) {
        a.status().check(Status.SUBMITTED);
        update(c, a, null, "status = ?, submitted_packet_id = ?, submitted_device_id = ?, score_json = ?", Status.SUBMITTED.name(),
                packetId, deviceId, FieldJson.write(score));
        FieldSql.recordChange(c, dialect, a.tenantId(), "ASSIGNMENT", a.id(), a.assignee(), "UPSERT", clock.instant());
    }

    /** Marks open assignments past due (+grace) as OVERDUE; returns the count. */
    public int sweepOverdue(String tenantId, long graceSeconds) {
        Instant cutoff = clock.instant().minusSeconds(graceSeconds);
        return tx.inTransaction(c -> {
            List<Assignment> due = Jdbc.query(c, "SELECT * FROM FLD_ASSIGNMENT WHERE tenant_id = ? AND status IN ('ASSIGNED', 'IN_PROGRESS') "
                    + "AND due_at IS NOT NULL AND due_at < ?", AssignmentService::map, tenantId, cutoff);
            for (Assignment a : due) {
                transition(c, tenantId, a.id(), null, Status.OVERDUE, "system", "past due");
            }
            return due.size();
        });
    }

    /** Recent distinct assignees of a target, newest first (inspector rotation input). */
    public List<String> recentAssignees(Connection c, String tenantId, String targetRef, int n) {
        List<String> all = Jdbc.query(c, "SELECT assignee FROM FLD_ASSIGNMENT WHERE tenant_id = ? AND target_ref = ? AND assignee IS NOT NULL "
                + "AND status <> 'CANCELLED' ORDER BY created_at DESC", rs -> rs.getString(1), tenantId, targetRef);
        List<String> out = new ArrayList<>();
        for (String s : all) {
            if (!out.contains(s)) {
                out.add(s);
            }
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    /** Open assignment count per assignee (load balancing). */
    public int openLoad(Connection c, String tenantId, String assignee) {
        return Jdbc.one(c, "SELECT COUNT(*) FROM FLD_ASSIGNMENT WHERE tenant_id = ? AND assignee = ? AND status IN "
                + "('ASSIGNED', 'IN_PROGRESS', 'OVERDUE')", rs -> rs.getInt(1), tenantId, assignee).orElse(0);
    }

    private void update(Connection c, Assignment a, Integer expectedVersion, String set, Object... params) {
        int expected = expectedVersion == null ? a.version() : expectedVersion;
        Object[] all = new Object[params.length + 4];
        System.arraycopy(params, 0, all, 0, params.length);
        all[params.length] = clock.instant();
        all[params.length + 1] = a.tenantId();
        all[params.length + 2] = a.id();
        all[params.length + 3] = expected;
        int n = Jdbc.update(c, "UPDATE FLD_ASSIGNMENT SET " + set + ", version = version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND assignment_id = ? AND version = ?", all);
        if (n == 0) {
            throw new FieldException("VERSION_CONFLICT", "assignment " + a.id() + " changed (expected version " + expected + ")");
        }
    }

    @SuppressWarnings("unchecked")
    public static Assignment map(ResultSet rs) throws SQLException {
        String score = rs.getString("score_json");
        String attrs = rs.getString("attributes_json");
        String fence = rs.getString("fence_json");
        return new Assignment(rs.getString("tenant_id"), rs.getString("assignment_id"), rs.getString("template_key"),
                rs.getInt("template_version"), rs.getString("target_ref"), rs.getString("target_label"), rs.getString("address"),
                FieldSql.point(rs, "lat_e7", "lon_e7"), fence == null ? null : FieldJson.read(fence, FenceSpec.class),
                rs.getString("assignee"), rs.getString("seat_id"), Status.valueOf(rs.getString("status")), Jdbc.instant(rs, "due_at"),
                Jdbc.instant(rs, "window_start"), Jdbc.instant(rs, "window_end"), rs.getString("schedule_id"),
                rs.getString("selection_id"), rs.getString("task_id"), rs.getString("business_key"),
                rs.getString("submitted_packet_id"), rs.getString("submitted_device_id"),
                score == null ? null : FieldJson.map(score), attrs == null ? Map.of() : FieldJson.map(attrs), rs.getInt("version"),
                rs.getString("created_by"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
    }
}
