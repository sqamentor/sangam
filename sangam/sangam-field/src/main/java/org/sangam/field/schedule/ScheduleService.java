// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.field.FieldException;
import org.sangam.field.FieldJson;
import org.sangam.field.support.FieldSql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recurring inspection schedules (N05 scheduling): every {@code cadenceDays} the schedule takes its target list,
 * optionally draws a risk-weighted random sample of {@code sampleSize} targets with an audited seed
 * ({@code scheduleId|runInstant}), assigns each target to an inspector from the pool under the rotation rule, and
 * creates assignments due {@code dueDays} later. Runs are idempotent per schedule and run instant (next_run_at moves
 * forward in the same transaction).
 */
public final class ScheduleService {

    /** A target of a schedule: any registry object (premises, beneficiary, asset, site) referenced by id. */
    public record Target(String ref, String label, String address, Double lat, Double lon, Double weight,
                         Map<String, Object> attributes) {
    }

    public record Schedule(String id, String templateKey, int cadenceDays, int dueDays, List<Target> targets,
                           List<String> assigneePool, int rotationExcludeLast, int sampleSize, String riskDecisionKey,
                           String rotationDecisionKey, boolean active, Instant nextRunAt, Instant lastRunAt) {
    }

    public record RunResult(String scheduleId, Instant runAt, String selectionId, List<String> assignmentIds) {
    }

    private final Transactions tx;
    private final AssignmentService assignments;
    private final RandomSelector selector;
    private final RotationRule rotation;
    private final Clock clock;

    public ScheduleService(Transactions tx, AssignmentService assignments, RandomSelector selector, RotationRule rotation, Clock clock) {
        this.tx = tx;
        this.assignments = assignments;
        this.selector = selector;
        this.rotation = rotation;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Schedule define(String tenantId, Schedule s, String actor) {
        if (s.templateKey() == null || s.cadenceDays() <= 0 || s.targets() == null || s.targets().isEmpty()) {
            throw new FieldException("INVALID_REQUEST", "schedule needs templateKey, cadenceDays > 0 and targets");
        }
        String id = s.id() == null || s.id().isBlank() ? FieldSql.newId() : s.id();
        Instant next = s.nextRunAt() == null ? clock.instant() : s.nextRunAt();
        Map<String, Object> extra = s.rotationDecisionKey() == null ? Map.of() : Map.of("rotationDecisionKey", s.rotationDecisionKey());
        tx.inTransaction(c -> {
            Jdbc.update(c, "DELETE FROM FLD_SCHEDULE WHERE tenant_id = ? AND schedule_id = ?", tenantId, id);
            Jdbc.update(c, "INSERT INTO FLD_SCHEDULE (tenant_id, schedule_id, template_key, cadence_days, due_days, targets_json, "
                            + "assignee_pool_json, rotation_exclude_last, sample_size, risk_decision_key, active, next_run_at, last_run_at, "
                            + "created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, id, s.templateKey(), s.cadenceDays(), Math.max(1, s.dueDays()),
                    FieldJson.write(Map.of("targets", s.targets(), "options", extra)),
                    FieldJson.write(s.assigneePool() == null ? List.of() : s.assigneePool()), Math.max(0, s.rotationExcludeLast()),
                    Math.max(0, s.sampleSize()), s.riskDecisionKey(), s.active(), next, null, actor, clock.instant());
            return null;
        });
        return get(tenantId, id);
    }

    public Schedule get(String tenantId, String id) {
        return tx.inTransaction(c -> Jdbc.one(c, "SELECT * FROM FLD_SCHEDULE WHERE tenant_id = ? AND schedule_id = ?",
                ScheduleService::map, tenantId, id)).orElseThrow(() -> FieldException.notFound("schedule", id));
    }

    public List<Schedule> list(String tenantId) {
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT * FROM FLD_SCHEDULE WHERE tenant_id = ? ORDER BY schedule_id",
                ScheduleService::map, tenantId));
    }

    /** Runs every active schedule whose next run is due. */
    public List<RunResult> runDue(String tenantId) {
        Instant now = clock.instant();
        List<String> due = tx.inTransaction(c -> Jdbc.query(c, "SELECT schedule_id FROM FLD_SCHEDULE WHERE tenant_id = ? AND active = 1 "
                + "AND next_run_at <= ?", rs -> rs.getString(1), tenantId, now));
        List<RunResult> out = new ArrayList<>();
        for (String id : due) {
            out.add(run(tenantId, id, "scheduler"));
        }
        return out;
    }

    public RunResult run(String tenantId, String scheduleId, String actor) {
        return tx.inTransaction(c -> {
            Schedule s = Jdbc.one(c, "SELECT * FROM FLD_SCHEDULE WHERE tenant_id = ? AND schedule_id = ?", ScheduleService::map,
                    tenantId, scheduleId).orElseThrow(() -> FieldException.notFound("schedule", scheduleId));
            Instant runAt = s.nextRunAt();
            // claim the run: only one caller moves next_run_at forward
            int claimed = Jdbc.update(c, "UPDATE FLD_SCHEDULE SET next_run_at = ?, last_run_at = ? WHERE tenant_id = ? AND schedule_id = ? "
                    + "AND next_run_at = ?", runAt.plus(Duration.ofDays(s.cadenceDays())), clock.instant(), tenantId, scheduleId, runAt);
            if (claimed == 0) {
                throw new FieldException("CONFLICT", "schedule run already claimed: " + scheduleId);
            }
            List<Target> chosen = s.targets();
            String selectionId = null;
            if (s.sampleSize() > 0 && s.sampleSize() < s.targets().size()) {
                List<RandomSelector.Candidate> pop = s.targets().stream()
                        .map(t -> new RandomSelector.Candidate(t.ref(), t.weight() == null ? 1 : t.weight(), t.attributes())).toList();
                RandomSelector.Selection sel = selector.select(c, tenantId, scheduleId + "|" + runAt, pop, s.sampleSize(),
                        s.riskDecisionKey(), actor);
                selectionId = sel.selectionId();
                Set<String> picked = Set.copyOf(sel.selected());
                chosen = s.targets().stream().filter(t -> picked.contains(t.ref())).toList();
            }
            List<String> ids = new ArrayList<>();
            Instant due = runAt.plus(Duration.ofDays(s.dueDays()));
            for (Target t : chosen) {
                String assignee = rotation.pick(c, tenantId, t.ref(), s.assigneePool(), s.rotationExcludeLast(), s.rotationDecisionKey())
                        .orElse(null);
                AssignmentService.NewAssignment na = new AssignmentService.NewAssignment(s.templateKey(), null, t.ref(), t.label(),
                        t.address(), t.lat(), t.lon(), assignee, null, due, runAt, due, null, null, t.attributes());
                ids.add(assignments.create(c, tenantId, na, scheduleId, selectionId, actor).id());
            }
            return new RunResult(scheduleId, runAt, selectionId, ids);
        });
    }

    private static Schedule map(ResultSet rs) throws SQLException {
        Map<String, Object> doc = FieldJson.map(rs.getString("targets_json"));
        List<Target> targets = FieldJson.MAPPER.convertValue(doc.getOrDefault("targets", List.of()), new TypeReference<List<Target>>() { });
        Object opts = doc.get("options");
        String rotKey = opts instanceof Map<?, ?> m && m.get("rotationDecisionKey") != null ? m.get("rotationDecisionKey").toString() : null;
        return new Schedule(rs.getString("schedule_id"), rs.getString("template_key"), rs.getInt("cadence_days"), rs.getInt("due_days"),
                targets, FieldJson.strings(rs.getString("assignee_pool_json")), rs.getInt("rotation_exclude_last"),
                rs.getInt("sample_size"), rs.getString("risk_decision_key"), rotKey, rs.getInt("active") != 0,
                Jdbc.instant(rs, "next_run_at"), Jdbc.instant(rs, "last_run_at"));
    }
}
