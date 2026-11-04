// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.asset;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.kernel.RegisteredObjectService;
import org.sangam.registry.support.Db;
import org.sangam.registry.support.Json;
import org.sangam.registry.support.Maps;
import org.sangam.registry.support.RegistryAudit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintenance (N03): meters and readings, maintenance plans (time, meter or condition based), due work-order
 * generation (idempotent per plan and due point), the work-order state machine with evidence, parts, cost and
 * downtime, calibration records (work type CALIBRATION with findings) and reliability figures (MTBF, MTTR,
 * availability). Work-order steps are audited on the work order's chain; readings on the meter's chain.
 */
public final class MaintenanceService {

    public static final String WO_REF = "registry-work-order";
    static final Set<String> OPEN_STATES = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS", "ON_HOLD");
    static final Set<String> FAILURE_TYPES = Set.of("BREAKDOWN", "CORRECTIVE");

    private final Dialect dialect;
    private final AssetService assets;
    private final RegisteredObjectService objects;
    private final RegistryAudit audit;
    private final RegistryPorts ports;

    public MaintenanceService(Dialect dialect, AssetService assets, RegisteredObjectService objects, RegistryAudit audit,
                              RegistryPorts ports) {
        this.dialect = dialect;
        this.assets = assets;
        this.objects = objects;
        this.audit = audit;
        this.ports = ports;
    }

    /** A meter of an asset. */
    public record Meter(String id, String assetId, String meterType, String unit, boolean cumulative,
                        BigDecimal rolloverAt, BigDecimal total, BigDecimal lastValue, Instant lastReadAt, long version) {
        static final String COLUMNS = "id, asset_id, meter_type, unit, cumulative, rollover_at, total_value, last_no,"
                + " last_read_at, version";

        static Meter map(ResultSet rs) throws SQLException {
            return new Meter(rs.getString("id"), rs.getString("asset_id"), rs.getString("meter_type"), rs.getString("unit"),
                    rs.getInt("cumulative") == 1, Db.decimal(rs, "rollover_at"), Db.decimal(rs, "total_value"),
                    Db.decimal(rs, "last_no"), Jdbc.instant(rs, "last_read_at"), rs.getLong("version"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "assetId", assetId, "meterType", meterType, "unit", unit, "cumulative", cumulative,
                    "rolloverAt", rolloverAt, "total", total, "lastValue", lastValue, "lastReadAt", lastReadAt,
                    "version", version);
        }
    }

    /** One meter reading. */
    public record Reading(String id, String meterId, BigDecimal reading, BigDecimal delta, BigDecimal totalAfter,
                          Instant readAt, String source, String status, String note, Instant recordedAt,
                          String recordedBy) {
        static final String COLUMNS = "id, meter_id, reading, delta, total_after, read_at, source, status, note,"
                + " recorded_at, recorded_by";

        static Reading map(ResultSet rs) throws SQLException {
            return new Reading(rs.getString("id"), rs.getString("meter_id"), Db.decimal(rs, "reading"),
                    Db.decimal(rs, "delta"), Db.decimal(rs, "total_after"), Jdbc.instant(rs, "read_at"),
                    rs.getString("source"), rs.getString("status"), rs.getString("note"), Jdbc.instant(rs, "recorded_at"),
                    rs.getString("recorded_by"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "meterId", meterId, "reading", reading, "delta", delta, "totalAfter", totalAfter,
                    "readAt", readAt, "source", source, "status", status, "note", note, "recordedAt", recordedAt,
                    "recordedBy", recordedBy);
        }
    }

    /** A maintenance plan of an asset. */
    public record Plan(String id, String assetId, String planKey, String trigger, Integer intervalDays, String meterType,
                       BigDecimal meterInterval, int leadDays, String workType, Map<String, Object> tasks,
                       Instant lastDoneAt, BigDecimal lastDoneMeter, Instant nextDueAt, BigDecimal nextDueMeter,
                       String status, long version) {
        static final String COLUMNS = "id, asset_id, plan_key, trigger_type, interval_days, meter_type, meter_interval,"
                + " lead_days, work_type, task_json, last_done_at, last_done_meter, next_due_at, next_due_meter, status, version";

        static Plan map(ResultSet rs) throws SQLException {
            return new Plan(rs.getString("id"), rs.getString("asset_id"), rs.getString("plan_key"),
                    rs.getString("trigger_type"), Db.intOrNull(rs, "interval_days"), rs.getString("meter_type"),
                    Db.decimal(rs, "meter_interval"), rs.getInt("lead_days"), rs.getString("work_type"),
                    Json.map(rs.getString("task_json")), Jdbc.instant(rs, "last_done_at"), Db.decimal(rs, "last_done_meter"),
                    Jdbc.instant(rs, "next_due_at"), Db.decimal(rs, "next_due_meter"), rs.getString("status"),
                    rs.getLong("version"));
        }

        public Map<String, Object> toMap() {
            return Maps.of("id", id, "assetId", assetId, "planKey", planKey, "trigger", trigger,
                    "intervalDays", intervalDays, "meterType", meterType, "meterInterval", meterInterval,
                    "leadDays", leadDays, "workType", workType, "tasks", tasks, "lastDoneAt", lastDoneAt,
                    "lastDoneMeter", lastDoneMeter, "nextDueAt", nextDueAt, "nextDueMeter", nextDueMeter,
                    "status", status, "version", version);
        }
    }

    // ---------------------------------------------------------------------------------------------------- meters

    public Meter addMeter(Connection c, RegistryContext ctx, String assetId, Map<String, Object> spec) {
        assets.get(c, ctx, assetId);
        AssetClassDefinition.MeterSpec m = AssetClassDefinition.MeterSpec.from(spec);
        if (meterOpt(c, ctx.tenant(), assetId, m.meterType()) != null) {
            throw RegistryException.conflict("asset " + assetId + " already has meter " + m.meterType());
        }
        String id = AssetRows.insertMeter(c, ctx.tenant(), assetId, m);
        audit.record(c, ctx, assetId, "ASSET_METER_ADDED", Maps.of("meterId", id, "meterType", m.meterType()));
        return meterById(c, ctx.tenant(), id);
    }

    public List<Meter> meters(Connection c, RegistryContext ctx, String assetId) {
        assets.get(c, ctx, assetId);
        return Jdbc.query(c, "SELECT " + Meter.COLUMNS + " FROM REG_METER WHERE tenant_id=? AND asset_id=? ORDER BY meter_type",
                Meter::map, ctx.tenant(), assetId);
    }

    private Meter meterOpt(Connection c, String tenant, String assetId, String meterType) {
        return Jdbc.one(c, "SELECT " + Meter.COLUMNS + " FROM REG_METER WHERE tenant_id=? AND asset_id=? AND meter_type=?",
                Meter::map, tenant, assetId, meterType).orElse(null);
    }

    private Meter meterById(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Meter.COLUMNS + " FROM REG_METER WHERE tenant_id=? AND id=?", Meter::map, tenant, id)
                .orElseThrow(() -> RegistryException.notFound("meter", id));
    }

    private Meter lockMeter(Connection c, String tenant, String id) {
        return Jdbc.one(c, dialect.lockingSelect(Meter.COLUMNS, "REG_METER", "tenant_id=? AND id=?"), Meter::map, tenant, id)
                .orElseThrow(() -> RegistryException.notFound("meter", id));
    }

    /**
     * Records a reading. Cumulative meters must not go down, except across a rollover when the meter has
     * {@code rolloverAt} (delta = rolloverAt - last + reading); the first reading is the baseline (delta 0).
     * A non-cumulative meter's reading is itself the delta (e.g. hours run in a shift).
     */
    public Reading recordReading(Connection c, RegistryContext ctx, String assetId, String meterType, BigDecimal reading,
                                 Instant readAt, String source, String note) {
        if (reading == null || reading.signum() < 0) {
            throw RegistryException.invalid("a non-negative reading is required");
        }
        assets.get(c, ctx, assetId);
        Meter probe = meterOpt(c, ctx.tenant(), assetId, meterType);
        if (probe == null) {
            throw RegistryException.notFound("meter " + meterType + " of asset", assetId);
        }
        Meter m = lockMeter(c, ctx.tenant(), probe.id());
        Instant now = Db.now(c, dialect);
        Instant at = readAt == null ? now : readAt;
        if (at.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw RegistryException.invalid("reading time is in the future");
        }
        if (m.lastReadAt() != null && at.isBefore(m.lastReadAt())) {
            throw RegistryException.invalid("reading is older than the last reading (" + m.lastReadAt() + "); correct it instead");
        }
        BigDecimal delta;
        if (!m.cumulative()) {
            delta = reading;
        } else if (m.lastValue() == null) {
            delta = BigDecimal.ZERO;
        } else if (reading.compareTo(m.lastValue()) >= 0) {
            delta = reading.subtract(m.lastValue());
        } else if (m.rolloverAt() != null && reading.compareTo(m.rolloverAt()) < 0) {
            delta = m.rolloverAt().subtract(m.lastValue()).add(reading);
        } else {
            throw RegistryException.invalid("cumulative meter " + meterType + " cannot go down from "
                    + m.lastValue().toPlainString() + " to " + reading.toPlainString());
        }
        BigDecimal total = (m.lastValue() == null && m.cumulative()) ? reading : m.total().add(delta);
        String id = Db.newId();
        Jdbc.update(c, "INSERT INTO REG_METER_READING(id, tenant_id, meter_id, reading, delta, total_after, read_at, source,"
                        + " status, note, recorded_at, recorded_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.tenant(), m.id(), reading, delta, total, at, source, "VALID", note, now, ctx.actor());
        Jdbc.update(c, "UPDATE REG_METER SET total_value=?, last_no=?, last_read_at=?, version=version+1"
                + " WHERE id=? AND tenant_id=?", total, reading, at, m.id(), ctx.tenant());
        audit.record(c, ctx, m.id(), "METER_READING_RECORDED", Maps.of("assetId", assetId, "meterType", meterType,
                "reading", reading, "delta", delta, "total", total, "readAt", at, "source", source));
        return reading(c, ctx.tenant(), id);
    }

    /** Rejects the latest valid reading of its meter (entered in error); the meter returns to the previous reading. */
    public Reading rejectReading(Connection c, RegistryContext ctx, String readingId, String note) {
        return undoLatest(c, ctx, readingId, "REJECTED", note);
    }

    /** Corrects the latest valid reading: marks it CORRECTED and records the corrected value at the same time. */
    public Reading correctReading(Connection c, RegistryContext ctx, String readingId, BigDecimal newValue, String note) {
        Reading old = undoLatest(c, ctx, readingId, "CORRECTED", note);
        Meter m = meterById(c, ctx.tenant(), old.meterId());
        return recordReading(c, ctx, m.assetId(), m.meterType(), newValue, old.readAt(), old.source(),
                note == null ? "correction of " + readingId : note);
    }

    private Reading undoLatest(Connection c, RegistryContext ctx, String readingId, String status, String note) {
        Reading r = reading(c, ctx.tenant(), readingId);
        Meter m = lockMeter(c, ctx.tenant(), r.meterId());
        assets.get(c, ctx, m.assetId());
        List<Reading> valid = Db.page(c, dialect, "SELECT " + Reading.COLUMNS + " FROM REG_METER_READING r",
                "r.tenant_id=? AND r.meter_id=? AND r.status='VALID'", List.of(ctx.tenant(), m.id()), null,
                "r.read_at DESC, r.recorded_at DESC", 0, 2, Reading::map);
        if (valid.isEmpty() || !valid.get(0).id().equals(readingId)) {
            throw RegistryException.state("only the latest valid reading of a meter can be " + status.toLowerCase());
        }
        Reading prev = valid.size() > 1 ? valid.get(1) : null;
        Jdbc.update(c, "UPDATE REG_METER_READING SET status=?, note=? WHERE id=? AND tenant_id=?", status,
                note == null ? r.note() : note, readingId, ctx.tenant());
        Jdbc.update(c, "UPDATE REG_METER SET total_value=?, last_no=?, last_read_at=?, version=version+1 WHERE id=? AND tenant_id=?",
                prev == null ? BigDecimal.ZERO : prev.totalAfter(), prev == null ? null : prev.reading(),
                prev == null ? null : prev.readAt(), m.id(), ctx.tenant());
        audit.record(c, ctx, m.id(), "METER_READING_" + status, Maps.of("readingId", readingId, "note", note));
        return r;
    }

    private Reading reading(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Reading.COLUMNS + " FROM REG_METER_READING WHERE tenant_id=? AND id=?", Reading::map,
                tenant, id).orElseThrow(() -> RegistryException.notFound("meter reading", id));
    }

    public List<Reading> readings(Connection c, RegistryContext ctx, String assetId, String meterType, int page, int size) {
        assets.get(c, ctx, assetId);
        Meter m = meterOpt(c, ctx.tenant(), assetId, meterType);
        if (m == null) {
            throw RegistryException.notFound("meter " + meterType + " of asset", assetId);
        }
        return Db.page(c, dialect, "SELECT " + Reading.COLUMNS + " FROM REG_METER_READING r", "r.tenant_id=? AND r.meter_id=?",
                List.of(ctx.tenant(), m.id()), null, "r.read_at DESC, r.recorded_at DESC", page, size, Reading::map);
    }

    // ----------------------------------------------------------------------------------------------------- plans

    public Plan addPlan(Connection c, RegistryContext ctx, String assetId, Map<String, Object> spec) {
        Asset a = assets.get(c, ctx, assetId);
        AssetClassDefinition.PlanSpec p = AssetClassDefinition.PlanSpec.from(spec);
        if (Jdbc.one(c, "SELECT id FROM REG_MAINT_PLAN WHERE tenant_id=? AND asset_id=? AND plan_key=?", rs -> rs.getString(1),
                ctx.tenant(), assetId, p.planKey()).isPresent()) {
            throw RegistryException.conflict("asset " + assetId + " already has plan " + p.planKey());
        }
        Instant base = Maps.instant(spec, "startFrom");
        if (base == null) {
            base = a.inServiceDate() != null ? java.time.LocalDate.parse(a.inServiceDate()).atStartOfDay(ctx.zone()).toInstant()
                    : Db.now(c, dialect);
        }
        BigDecimal meterBase = null;
        if ("METER".equals(p.trigger())) {
            Meter m = meterOpt(c, ctx.tenant(), assetId, p.meterType());
            if (m == null) {
                throw RegistryException.invalid("asset " + assetId + " has no meter " + p.meterType());
            }
            meterBase = m.total();
        }
        String id = AssetRows.insertPlan(c, ctx.tenant(), assetId, p, base, meterBase);
        audit.record(c, ctx, assetId, "MAINT_PLAN_ADDED", Maps.of("planId", id, "plan", p.toMap()));
        return plan(c, ctx.tenant(), id);
    }

    /** Updates interval, lead days, work type or tasks; the next due point is recomputed from the last done point. */
    public Plan updatePlan(Connection c, RegistryContext ctx, String planId, Long expectedVersion, Map<String, Object> patch) {
        Plan p = lockPlan(c, ctx, planId, expectedVersion);
        Integer days = patch.containsKey("intervalDays") ? Maps.integer(patch, "intervalDays") : p.intervalDays();
        BigDecimal mi = patch.containsKey("meterInterval") ? Maps.decimal(patch, "meterInterval") : p.meterInterval();
        if ("TIME".equals(p.trigger()) && (days == null || days <= 0)) {
            throw RegistryException.invalid("TIME plan needs a positive intervalDays");
        }
        if ("METER".equals(p.trigger()) && (mi == null || mi.signum() <= 0)) {
            throw RegistryException.invalid("METER plan needs a positive meterInterval");
        }
        Map<String, Object> tasks = new LinkedHashMap<>(p.tasks());
        if (patch.containsKey("tasks")) {
            tasks.put("tasks", patch.get("tasks"));
        }
        if (patch.containsKey("workOrderSeries")) {
            tasks.put("workOrderSeries", patch.get("workOrderSeries"));
        }
        Instant nextAt = p.nextDueAt();
        BigDecimal nextMeter = p.nextDueMeter();
        if ("TIME".equals(p.trigger()) && p.lastDoneAt() != null) {
            nextAt = AssetRows.nextTime(p.lastDoneAt(), days);
        }
        if ("METER".equals(p.trigger()) && p.lastDoneMeter() != null) {
            nextMeter = p.lastDoneMeter().add(mi);
        }
        Jdbc.update(c, "UPDATE REG_MAINT_PLAN SET interval_days=?, meter_interval=?, lead_days=?, work_type=?, task_json=?,"
                        + " next_due_at=?, next_due_meter=?, version=version+1 WHERE id=? AND tenant_id=?",
                days, mi, Maps.integer(patch, "leadDays", p.leadDays()), Maps.str(patch, "workType", p.workType()).toUpperCase(),
                Json.write(tasks), nextAt, nextMeter, planId, ctx.tenant());
        audit.record(c, ctx, p.assetId(), "MAINT_PLAN_UPDATED", Maps.of("planId", planId, "changes", patch));
        return plan(c, ctx.tenant(), planId);
    }

    /** ACTIVE, SUSPENDED or RETIRED. */
    public Plan setPlanStatus(Connection c, RegistryContext ctx, String planId, String status, String reason) {
        String s = status.toUpperCase();
        if (!Set.of("ACTIVE", "SUSPENDED", "RETIRED").contains(s)) {
            throw RegistryException.invalid("plan status must be ACTIVE, SUSPENDED or RETIRED");
        }
        Plan p = lockPlan(c, ctx, planId, null);
        if ("RETIRED".equals(p.status())) {
            throw RegistryException.state("plan " + planId + " is retired");
        }
        Jdbc.update(c, "UPDATE REG_MAINT_PLAN SET status=?, version=version+1 WHERE id=? AND tenant_id=?", s, planId, ctx.tenant());
        audit.record(c, ctx, p.assetId(), "MAINT_PLAN_" + s, Maps.of("planId", planId, "reason", reason));
        return plan(c, ctx.tenant(), planId);
    }

    public List<Plan> plans(Connection c, RegistryContext ctx, String assetId) {
        assets.get(c, ctx, assetId);
        return Jdbc.query(c, "SELECT " + Plan.COLUMNS + " FROM REG_MAINT_PLAN WHERE tenant_id=? AND asset_id=? ORDER BY plan_key",
                Plan::map, ctx.tenant(), assetId);
    }

    private Plan plan(Connection c, String tenant, String id) {
        return Jdbc.one(c, "SELECT " + Plan.COLUMNS + " FROM REG_MAINT_PLAN WHERE tenant_id=? AND id=?", Plan::map, tenant, id)
                .orElseThrow(() -> RegistryException.notFound("maintenance plan", id));
    }

    private Plan lockPlan(Connection c, RegistryContext ctx, String id, Long expectedVersion) {
        Plan p = Jdbc.one(c, dialect.lockingSelect(Plan.COLUMNS, "REG_MAINT_PLAN", "tenant_id=? AND id=?"), Plan::map,
                ctx.tenant(), id).orElseThrow(() -> RegistryException.notFound("maintenance plan", id));
        assets.get(c, ctx, p.assetId());
        if (expectedVersion != null && expectedVersion != p.version()) {
            throw RegistryException.conflict("plan " + id + " is at version " + p.version() + ", expected " + expectedVersion);
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------ due generation

    /**
     * Creates the work orders that are due at {@code asOf}: TIME plans whose next due date minus lead days has
     * passed, METER plans whose meter total reached the next due value. Idempotent: the work order's
     * {@code gen_key} is (plan, due point), so a re-run or a second node creates nothing twice.
     *
     * @return the work orders created by this call
     */
    public List<WorkOrder> generateDueWorkOrders(Connection c, RegistryContext ctx, Instant asOf, int limit) {
        Instant at = asOf == null ? Db.now(c, dialect) : asOf;
        int max = limit <= 0 ? 100 : Math.min(limit, 1000);
        int maxLead = Jdbc.one(c, "SELECT MAX(lead_days) FROM REG_MAINT_PLAN WHERE tenant_id=? AND status='ACTIVE'",
                rs -> rs.getInt(1), ctx.tenant()).orElse(0);
        List<Plan> candidates = new ArrayList<>(Db.page(c, dialect, "SELECT " + prefixed("p") + " FROM REG_MAINT_PLAN p",
                "p.tenant_id=? AND p.status='ACTIVE' AND p.trigger_type='TIME' AND p.next_due_at IS NOT NULL AND p.next_due_at <= ?",
                List.of(ctx.tenant(), at.plus(maxLead, ChronoUnit.DAYS)), null, "p.next_due_at, p.id", 0, max, Plan::map));
        candidates.addAll(Db.page(c, dialect, "SELECT " + prefixed("p") + " FROM REG_MAINT_PLAN p JOIN REG_METER m"
                        + " ON m.tenant_id = p.tenant_id AND m.asset_id = p.asset_id AND m.meter_type = p.meter_type",
                "p.tenant_id=? AND p.status='ACTIVE' AND p.trigger_type='METER' AND p.next_due_meter IS NOT NULL"
                        + " AND m.total_value >= p.next_due_meter", List.of(ctx.tenant()), null, "p.id", 0, max, Plan::map));
        List<WorkOrder> created = new ArrayList<>();
        for (Plan cand : candidates) {
            if (created.size() >= max) {
                break;
            }
            if ("TIME".equals(cand.trigger()) && cand.nextDueAt().minus(cand.leadDays(), ChronoUnit.DAYS).isAfter(at)) {
                continue;
            }
            WorkOrder wo = generateForPlan(c, ctx, cand.id(), at);
            if (wo != null) {
                created.add(wo);
            }
        }
        return created;
    }

    private WorkOrder generateForPlan(Connection c, RegistryContext ctx, String planId, Instant at) {
        Plan p = lockPlan(c, ctx, planId, null);
        if (!"ACTIVE".equals(p.status())) {
            return null;
        }
        String marker;
        Instant due;
        if ("TIME".equals(p.trigger())) {
            if (p.nextDueAt() == null) {
                return null;
            }
            marker = p.nextDueAt().toString();
            due = p.nextDueAt();
        } else if ("METER".equals(p.trigger())) {
            if (p.nextDueMeter() == null) {
                return null;
            }
            marker = "m" + p.nextDueMeter().toPlainString();
            due = at;
        } else {
            return null;
        }
        String genKey = p.id() + "|" + marker;
        if (Jdbc.one(c, "SELECT id FROM REG_WORK_ORDER WHERE tenant_id=? AND gen_key=?", rs -> rs.getString(1),
                ctx.tenant(), genKey).isPresent()) {
            return null;
        }
        Asset a = assets.asset(c, ctx.tenant(), p.assetId());
        if (!"IN_SERVICE".equals(a.status())) {
            return null;
        }
        return insertWorkOrder(c, ctx, a, p, genKey, p.workType(), "NORMAL", "planned: " + p.planKey(), "system:plan",
                due, null);
    }

    /** A CONDITION plan fired (an alarm or inspection result from a monitoring rule): creates its work order. */
    public WorkOrder conditionTriggered(Connection c, RegistryContext ctx, String planId, String condition, String priority) {
        Plan p = lockPlan(c, ctx, planId, null);
        if (!"ACTIVE".equals(p.status())) {
            throw RegistryException.state("plan " + planId + " is " + p.status());
        }
        Asset a = assets.asset(c, ctx.tenant(), p.assetId());
        String woId = Db.newId();
        return insertWorkOrder(c, ctx, a, p, woId, p.workType(), priority == null ? "HIGH" : priority.toUpperCase(),
                condition, ctx.actor(), null, null, woId);
    }

    // ----------------------------------------------------------------------------------------------- work orders

    /**
     * Creates a work order for intake by a person or an alarm. Fields: {@code assetId}, {@code workType}
     * (default CORRECTIVE), {@code priority}, {@code description}, {@code reportedBy} (default the actor; an alarm
     * uses e.g. {@code alarm:<source>}), {@code dueAt}, {@code planId}, {@code downtimeStart} (BREAKDOWN defaults to
     * the report time), {@code genKey} (optional idempotency key, e.g. the alarm id).
     */
    public WorkOrder createWorkOrder(Connection c, RegistryContext ctx, Map<String, Object> req) {
        Asset a = assets.get(c, ctx, Maps.required(req, "assetId"));
        if (!"IN_SERVICE".equals(a.status())) {
            throw RegistryException.state("asset " + a.tag() + " is " + a.status());
        }
        String planId = Maps.str(req, "planId");
        Plan p = planId == null ? null : plan(c, ctx.tenant(), planId);
        if (p != null && !p.assetId().equals(a.id())) {
            throw RegistryException.invalid("plan " + planId + " belongs to another asset");
        }
        String genKey = Maps.str(req, "genKey");
        if (genKey != null) {
            var existing = Jdbc.one(c, "SELECT " + WorkOrder.COLUMNS + " FROM REG_WORK_ORDER WHERE tenant_id=? AND gen_key=?",
                    WorkOrder::map, ctx.tenant(), genKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        String type = Maps.str(req, "workType", "CORRECTIVE").toUpperCase();
        Instant downtime = Maps.instant(req, "downtimeStart");
        String woId = Db.newId();
        return insertWorkOrder(c, ctx, a, p, genKey == null ? woId : genKey, type, Maps.str(req, "priority", "NORMAL").toUpperCase(),
                Maps.str(req, "description"), Maps.str(req, "reportedBy", ctx.actor()), Maps.instant(req, "dueAt"),
                downtime == null && "BREAKDOWN".equals(type) ? Instant.EPOCH : downtime, woId);
    }

    private WorkOrder insertWorkOrder(Connection c, RegistryContext ctx, Asset a, Plan p, String genKey, String type,
                                      String priority, String description, String reportedBy, Instant due,
                                      Instant downtimeStart) {
        return insertWorkOrder(c, ctx, a, p, genKey, type, priority, description, reportedBy, due, downtimeStart, Db.newId());
    }

    private WorkOrder insertWorkOrder(Connection c, RegistryContext ctx, Asset a, Plan p, String genKey, String type,
                                      String priority, String description, String reportedBy, Instant due,
                                      Instant downtimeStart, String woId) {
        Instant now = Db.now(c, dialect);
        Instant dt = Instant.EPOCH.equals(downtimeStart) ? now : downtimeStart;
        String number = number(c, ctx, a, p, woId);
        Jdbc.update(c, "INSERT INTO REG_WORK_ORDER(id, tenant_id, wo_number, gen_key, asset_id, plan_id, work_type, priority, state,"
                        + " description, reported_by, reported_at, due_at, downtime_start, version, updated_at, updated_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                woId, ctx.tenant(), number, genKey, a.id(), p == null ? null : p.id(), type, priority, "OPEN", description,
                reportedBy, now, due, dt, 1L, now, ctx.actor());
        audit.record(c, ctx, woId, "WORK_ORDER_CREATED", Maps.of("number", number, "assetId", a.id(), "assetTag", a.tag(),
                "planId", p == null ? null : p.id(), "workType", type, "priority", priority, "reportedBy", reportedBy,
                "dueAt", due, "downtimeStart", dt));
        return workOrder(c, ctx, woId);
    }

    private String number(Connection c, RegistryContext ctx, Asset a, Plan p, String woId) {
        String series = p == null ? null : Maps.str(p.tasks(), "workOrderSeries");
        if (series == null) {
            series = assets.resolveClass(c, ctx.tenant(), a.classKey()).workOrderSeries();
        }
        if (series == null) {
            return "WO-" + Hashes.hex(Hashes.sha256(woId)).substring(0, 10).toUpperCase();
        }
        return objects.numbering().allocate(c, ctx, series, null, null,
                Maps.of("assetTag", a.tag(), "classKey", a.classKey()), WO_REF, woId).formatted();
    }

    public WorkOrder workOrder(Connection c, RegistryContext ctx, String id) {
        WorkOrder wo = Jdbc.one(c, "SELECT " + WorkOrder.COLUMNS + " FROM REG_WORK_ORDER WHERE tenant_id=? AND id=?",
                WorkOrder::map, ctx.tenant(), id).orElseThrow(() -> RegistryException.notFound("work order", id));
        assets.get(c, ctx, wo.assetId());
        return wo;
    }

    public List<WorkOrder> workOrders(Connection c, RegistryContext ctx, String assetId, String state, String workType,
                                      int page, int size) {
        StringBuilder where = new StringBuilder("w.tenant_id=?");
        List<Object> params = new ArrayList<>(List.of(ctx.tenant()));
        if (assetId != null) {
            where.append(" AND w.asset_id=?");
            params.add(assetId);
        }
        if (state != null) {
            where.append(" AND w.state=?");
            params.add(state.toUpperCase());
        }
        if (workType != null) {
            where.append(" AND w.work_type=?");
            params.add(workType.toUpperCase());
        }
        return Db.page(c, dialect, "SELECT " + prefixedWo() + " FROM REG_WORK_ORDER w JOIN REG_OBJECT o ON o.id = w.asset_id",
                where.toString(), params, ports.visibility().visible(ctx, RegisteredObjectService.RESOURCE, "o"),
                "w.reported_at DESC, w.id", page, size, WorkOrder::map);
    }

    private WorkOrder lockWo(Connection c, RegistryContext ctx, String id, Long expectedVersion, Set<String> from, String step) {
        WorkOrder wo = Jdbc.one(c, dialect.lockingSelect(WorkOrder.COLUMNS, "REG_WORK_ORDER", "tenant_id=? AND id=?"),
                WorkOrder::map, ctx.tenant(), id).orElseThrow(() -> RegistryException.notFound("work order", id));
        assets.get(c, ctx, wo.assetId());
        if (expectedVersion != null && expectedVersion != wo.version()) {
            throw RegistryException.conflict("work order " + id + " is at version " + wo.version() + ", expected " + expectedVersion);
        }
        if (!from.contains(wo.state())) {
            throw RegistryException.state("work order " + wo.number() + " cannot " + step + " from " + wo.state());
        }
        return wo;
    }

    private WorkOrder step(Connection c, RegistryContext ctx, WorkOrder wo, String to, String event, String setSql,
                           List<Object> params, Map<String, Object> facts) {
        Instant now = Db.now(c, dialect);
        List<Object> all = new ArrayList<>();
        all.add(to);
        all.addAll(params);
        all.add(now);
        all.add(ctx.actor());
        all.add(wo.id());
        all.add(ctx.tenant());
        all.add(wo.version());
        int n = Jdbc.update(c, "UPDATE REG_WORK_ORDER SET state=?" + (setSql.isEmpty() ? "" : ", " + setSql)
                + ", version=version+1, updated_at=?, updated_by=? WHERE id=? AND tenant_id=? AND version=?", all.toArray());
        if (n == 0) {
            throw RegistryException.conflict("work order " + wo.id() + " changed concurrently");
        }
        Map<String, Object> f = new LinkedHashMap<>(facts);
        f.put("number", wo.number());
        f.put("fromState", wo.state());
        f.put("toState", to);
        audit.record(c, ctx, wo.id(), event, f);
        return workOrder(c, ctx, wo.id());
    }

    public WorkOrder assign(Connection c, RegistryContext ctx, String id, Long expectedVersion, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw RegistryException.invalid("an assignee is required");
        }
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("OPEN", "ASSIGNED", "ON_HOLD"), "be assigned");
        String to = "ON_HOLD".equals(wo.state()) ? "ON_HOLD" : "ASSIGNED";
        return step(c, ctx, wo, to, "WORK_ORDER_ASSIGNED", "assigned_to=?", List.of(assignee), Maps.of("assignedTo", assignee));
    }

    public WorkOrder start(Connection c, RegistryContext ctx, String id, Long expectedVersion) {
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("OPEN", "ASSIGNED"), "start");
        Instant now = Db.now(c, dialect);
        String assignee = wo.assignedTo() == null ? ctx.actor() : wo.assignedTo();
        return step(c, ctx, wo, "IN_PROGRESS", "WORK_ORDER_STARTED", "started_at=?, assigned_to=?",
                List.of(now, assignee), Maps.of("startedAt", now));
    }

    public WorkOrder hold(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required to put a work order on hold");
        }
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("IN_PROGRESS"), "be put on hold");
        return step(c, ctx, wo, "ON_HOLD", "WORK_ORDER_ON_HOLD", "", List.of(), Maps.of("reason", reason));
    }

    public WorkOrder resume(Connection c, RegistryContext ctx, String id, Long expectedVersion) {
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("ON_HOLD"), "resume");
        Instant started = wo.startedAt() == null ? Db.now(c, dialect) : wo.startedAt();
        return step(c, ctx, wo, "IN_PROGRESS", "WORK_ORDER_RESUMED", "started_at=?", List.of(started), Map.of());
    }

    /**
     * Completes a work order. Fields: {@code findings} (object; calibration results go here), {@code parts}
     * (list of parts used), {@code evidence} (list of document refs), {@code cost}, {@code meterAtDone},
     * {@code downtimeStart}, {@code downtimeEnd}, {@code completedAt}. A plan's last-done and next-due points move on.
     */
    public WorkOrder complete(Connection c, RegistryContext ctx, String id, Long expectedVersion, Map<String, Object> result) {
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("IN_PROGRESS"), "complete");
        Instant now = Db.now(c, dialect);
        Instant done = Maps.instant(result, "completedAt") == null ? now : Maps.instant(result, "completedAt");
        if (done.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw RegistryException.invalid("completion time is in the future");
        }
        Map<String, Object> findings = Maps.map(result, "findings");
        if ("CALIBRATION".equals(wo.workType()) && findings.isEmpty()) {
            throw RegistryException.invalid("a calibration work order needs its findings (results, standard, pass/fail)");
        }
        Instant dStart = Maps.instant(result, "downtimeStart") != null ? Maps.instant(result, "downtimeStart") : wo.downtimeStart();
        Instant dEnd = Maps.instant(result, "downtimeEnd") != null ? Maps.instant(result, "downtimeEnd") : dStart == null ? null : done;
        if (dStart != null && dEnd != null && dEnd.isBefore(dStart)) {
            throw RegistryException.invalid("downtime end before downtime start");
        }
        BigDecimal meterAtDone = Maps.decimal(result, "meterAtDone");
        Plan p = wo.planId() == null ? null : lockPlan(c, ctx, wo.planId(), null);
        if (p != null && meterAtDone == null && p.meterType() != null) {
            Meter m = meterOpt(c, ctx.tenant(), wo.assetId(), p.meterType());
            meterAtDone = m == null ? null : m.total();
        }
        List<Object> parts = result.get("parts") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        List<Object> evidence = result.get("evidence") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        BigDecimal cost = Maps.decimal(result, "cost");
        List<Object> params = new ArrayList<>();
        params.add(done);
        params.add(dStart);
        params.add(dEnd);
        params.add(meterAtDone);
        params.add(cost);
        params.add(Json.write(findings));
        params.add(Json.write(parts));
        params.add(Json.write(evidence));
        WorkOrder out = step(c, ctx, wo, "COMPLETED", "WORK_ORDER_COMPLETED", "completed_at=?, downtime_start=?, downtime_end=?,"
                        + " meter_at_done=?, cost=?, findings_json=?, parts_json=?, evidence_json=?", params,
                Maps.of("completedAt", done, "downtimeStart", dStart, "downtimeEnd", dEnd, "meterAtDone", meterAtDone,
                        "cost", cost, "parts", parts.size(), "evidence", evidence, "findings", findings));
        if (p != null) {
            Instant nextAt = "TIME".equals(p.trigger()) ? AssetRows.nextTime(done, p.intervalDays()) : p.nextDueAt();
            BigDecimal nextMeter = "METER".equals(p.trigger()) && meterAtDone != null ? meterAtDone.add(p.meterInterval())
                    : p.nextDueMeter();
            Jdbc.update(c, "UPDATE REG_MAINT_PLAN SET last_done_at=?, last_done_meter=?, next_due_at=?, next_due_meter=?,"
                    + " version=version+1 WHERE id=? AND tenant_id=?", done, meterAtDone, nextAt, nextMeter, p.id(), ctx.tenant());
            audit.record(c, ctx, p.assetId(), "MAINT_PLAN_DONE", Maps.of("planId", p.id(), "workOrderId", wo.id(),
                    "nextDueAt", nextAt, "nextDueMeter", nextMeter));
        }
        return out;
    }

    public WorkOrder close(Connection c, RegistryContext ctx, String id, Long expectedVersion, String remarks) {
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, Set.of("COMPLETED"), "close");
        Instant now = Db.now(c, dialect);
        return step(c, ctx, wo, "CLOSED", "WORK_ORDER_CLOSED", "closed_at=?", List.of(now), Maps.of("remarks", remarks));
    }

    public WorkOrder cancel(Connection c, RegistryContext ctx, String id, Long expectedVersion, String reason) {
        if (reason == null || reason.isBlank()) {
            throw RegistryException.invalid("a reason is required to cancel a work order");
        }
        WorkOrder wo = lockWo(c, ctx, id, expectedVersion, OPEN_STATES, "be cancelled");
        Instant now = Db.now(c, dialect);
        return step(c, ctx, wo, "CANCELLED", "WORK_ORDER_CANCELLED", "closed_at=?", List.of(now), Maps.of("reason", reason));
    }

    // ------------------------------------------------------------------------------------------------- reliability

    /**
     * Reliability figures of an asset over a window, from BREAKDOWN and CORRECTIVE work orders completed or closed:
     * failures, downtime hours, MTBF (operating hours / failures), MTTR (repair hours / failures) and availability
     * (operating / window).
     */
    public Map<String, Object> reliability(Connection c, RegistryContext ctx, String assetId, Instant from, Instant to) {
        assets.get(c, ctx, assetId);
        Instant end = to == null ? Db.now(c, dialect) : to;
        Instant start = from == null ? end.minus(365, ChronoUnit.DAYS) : from;
        if (!end.isAfter(start)) {
            throw RegistryException.invalid("the window end must be after its start");
        }
        List<WorkOrder> wos = Jdbc.query(c, "SELECT " + WorkOrder.COLUMNS + " FROM REG_WORK_ORDER WHERE tenant_id=? AND asset_id=?"
                        + " AND state IN ('COMPLETED','CLOSED') AND reported_at >= ? AND reported_at < ?", WorkOrder::map,
                ctx.tenant(), assetId, start, end);
        long failures = 0;
        long downtimeSec = 0;
        long repairSec = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (WorkOrder w : wos) {
            if (w.cost() != null) {
                cost = cost.add(w.cost());
            }
            if (!FAILURE_TYPES.contains(w.workType())) {
                continue;
            }
            failures++;
            if (w.downtimeStart() != null && w.downtimeEnd() != null) {
                Instant s = w.downtimeStart().isBefore(start) ? start : w.downtimeStart();
                Instant e = w.downtimeEnd().isAfter(end) ? end : w.downtimeEnd();
                if (e.isAfter(s)) {
                    downtimeSec += Duration.between(s, e).getSeconds();
                }
            }
            Instant rs = w.startedAt() != null ? w.startedAt() : w.reportedAt();
            if (w.completedAt() != null && rs != null && w.completedAt().isAfter(rs)) {
                repairSec += Duration.between(rs, w.completedAt()).getSeconds();
            }
        }
        long window = Duration.between(start, end).getSeconds();
        long operating = Math.max(0, window - downtimeSec);
        return Maps.of("assetId", assetId, "from", start, "to", end, "workOrders", wos.size(), "failures", failures,
                "downtimeHours", hours(downtimeSec), "operatingHours", hours(operating),
                "mtbfHours", failures == 0 ? null : hours(operating / failures),
                "mttrHours", failures == 0 ? null : hours(repairSec / failures),
                "availability", BigDecimal.valueOf(operating).divide(BigDecimal.valueOf(window), 6, RoundingMode.HALF_UP),
                "maintenanceCost", cost);
    }

    private static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    private static String prefixed(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String col : Plan.COLUMNS.split(",")) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(alias).append('.').append(col.trim());
        }
        return sb.toString();
    }

    private static String prefixedWo() {
        StringBuilder sb = new StringBuilder();
        for (String col : WorkOrder.COLUMNS.split(",")) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append("w.").append(col.trim());
        }
        return sb.toString();
    }
}
