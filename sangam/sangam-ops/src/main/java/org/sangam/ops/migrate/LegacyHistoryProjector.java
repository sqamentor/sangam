// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.migrate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.TenantKey;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

/**
 * M-131 (NG-25, owner decision 160): maps the legacy Camunda 7 / Operaton history that {@link LegacyHistoryMigrator}
 * copied verbatim into {@code OPS_MIG_ROW} onto the product history read model ({@code HRM_*}) and into an audit chain
 * of its own.
 * <ul>
 *   <li><b>Read model:</b> {@code ACT_HI_PROCINST} and {@code ACT_HI_CASEINST} become {@code HRM_PROCESS_INSTANCE},
 *       {@code ACT_HI_ACTINST} {@code HRM_ACTIVITY_INSTANCE}, {@code ACT_HI_TASKINST} {@code HRM_TASK}. Every row is
 *       marked {@code record_source = 'LEGACY'} with {@code legacy_ref = <job>:<table>:<source id>} (changesets
 *       0044-05..07). Its id is {@link #legacyId} (deterministic, prefixed {@code lg-}), never the legacy id, so it
 *       cannot collide with a live engine id that the live projector updates by id; references between imported rows
 *       (process instance, parent, super, root) use the same mapping.</li>
 *   <li><b>Audit:</b> chain {@code legacy:<job>} per tenant, never the live chains. One
 *       {@value #EVENT_IMPORTED} per batch and tenant (count, first and last source id, SHA-256 over the source ids
 *       and payload hashes), one {@value #EVENT_OPERATION} per legacy user operation ({@code ACT_HI_OP_LOG}: who did
 *       what, when; old and new values only as SHA-256), one {@value #EVENT_UNRESOLVED} per row not projected, and
 *       {@value #EVENT_STARTED} / {@value #EVENT_COMPLETED} around a run. The legacy time is in the payload; the
 *       envelope time is the import time.</li>
 *   <li><b>Time (decision 150):</b> the legacy engine wrote wall-clock {@code timestamp} values; the migrator read them
 *       under the strict-UTC JVM, so the stored text is that wall clock labelled UTC. The caller names the zone the
 *       legacy engine wrote in (a region id with historical rules, never a fixed offset). A wall clock in a DST gap or
 *       overlap is not guessed: the row is not projected and is recorded unresolved, as is an unknown state code.</li>
 *   <li><b>Resumable and idempotent:</b> keyset batches in source-id order; each batch (rows, audit, checkpoint) is one
 *       transaction, its checkpoint row in {@code OPS_MIG_CHECKPOINT} under {@code HRM:<table>}; inserts are
 *       insert-if-absent, and only inserted rows are counted and audited. A dry run counts and reads only.</li>
 * </ul>
 * Variables, details, incidents, decisions, comments and identity links are kept in {@code OPS_MIG_ROW} only (no read
 * model table holds them; values never enter the history stream).
 */
public final class LegacyHistoryProjector {

    public static final String CHAIN_PREFIX = "legacy:";
    public static final String EVENT_STARTED = "LEGACY_HISTORY_PROJECTION_STARTED";
    public static final String EVENT_IMPORTED = "LEGACY_HISTORY_IMPORTED";
    public static final String EVENT_OPERATION = "LEGACY_OPERATION_IMPORTED";
    public static final String EVENT_UNRESOLVED = "LEGACY_ROW_UNRESOLVED";
    public static final String EVENT_COMPLETED = "LEGACY_HISTORY_PROJECTION_COMPLETED";
    public static final String CHECKPOINT_PREFIX = "HRM:";
    static final int MAX_LISTED = 100;

    /** A projected legacy table, in projection order (instances before the rows that point at them). */
    public enum Source {
        ACT_HI_PROCINST("HRM_PROCESS_INSTANCE"), ACT_HI_CASEINST("HRM_PROCESS_INSTANCE"),
        ACT_HI_ACTINST("HRM_ACTIVITY_INSTANCE"), ACT_HI_TASKINST("HRM_TASK"), ACT_HI_OP_LOG(null);

        private final String target;

        Source(String target) {
            this.target = target;
        }

        /** The read-model table, or null for the user operation log (audit only). */
        public String target() {
            return target;
        }
    }

    /** Why a row was not projected. */
    public enum Unresolved { DST_GAP, DST_OVERLAP, BAD_TIMESTAMP, UNKNOWN_STATE, MISSING_ID }

    /**
     * Outcome per legacy table.
     *
     * @param read           rows of the job read from {@code OPS_MIG_ROW}
     * @param projected      rows written now (read-model rows or operation audit events)
     * @param alreadyPresent rows a previous run had written (left unchanged); for {@code ACT_HI_OP_LOG} in a dry run,
     *                       every row up to the checkpoint (handled before, written or recorded unresolved)
     * @param unresolved     rows not projected, each recorded with {@value #EVENT_UNRESOLVED}
     * @param unresolvedIds  the first {@value #MAX_LISTED} unresolved source ids with their reason
     */
    public record Result(Source source, long read, long projected, long alreadyPresent, long unresolved,
                         List<String> unresolvedIds) {
        public Result {
            unresolvedIds = List.copyOf(unresolvedIds);
        }
    }

    private final Transactions target;
    private final Dialect dialect;
    private final AuditChain audit;

    public LegacyHistoryProjector(Transactions target, Dialect dialect, AuditChain audit) {
        this.target = Objects.requireNonNull(target, "target");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** The read-model id of a legacy row: {@code lg-} and 32 hex digits of SHA-256(job, kind, legacy id). */
    public static String legacyId(String jobId, String kind, String legacyId) {
        byte[] h = Hashes.sha256(jobId + '\u0000' + kind + '\u0000' + legacyId);
        return "lg-" + Hashes.hex(h).substring(0, 32);
    }

    /** The zone the legacy engine wrote its wall clock in: a region id with historical rules; fixed offsets refused. */
    public static ZoneId requireNamedZone(ZoneId zone) {
        if (zone == null || zone instanceof ZoneOffset) {
            throw new IllegalArgumentException("a named source zone (region id such as Asia/Kolkata or UTC) is required,"
                    + " never a fixed offset (decision 150): " + zone);
        }
        return zone;
    }

    /** Counts what {@link #project} would do, without writing. */
    public List<Result> dryRun(String jobId, ZoneId sourceZone) {
        requireNamedZone(sourceZone);
        List<Result> out = new ArrayList<>();
        for (Source s : Source.values()) {
            Tally t = new Tally();
            // operations leave no read-model row: those up to the checkpoint are already audited
            String done = s.target() != null ? null : target.inTransaction(c -> Jdbc.one(c,
                    "SELECT last_id FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?", rs -> rs.getString(1),
                    jobId, CHECKPOINT_PREFIX + s.name()).orElse(null));
            if (done != null) {
                // counted in the database's own collation, the order the keyset batches use
                long before = target.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_MIG_ROW WHERE job_id=?"
                        + " AND source_table=? AND source_id <= ?", rs -> rs.getLong(1), jobId, s.name(), done).orElse(0L));
                t.read += before;
                t.alreadyPresent += before;
            }
            String last = done;
            while (true) {
                List<MigRow> batch = batch(jobId, s, last, 500);
                if (batch.isEmpty()) {
                    break;
                }
                for (MigRow r : batch) {
                    t.read++;
                    Mapped m = map(jobId, s, r, sourceZone);
                    if (m.unresolved != null) {
                        t.unresolved(r.sourceId, m.unresolved);
                    } else if (s.target() != null && present(s.target(), m.id)) {
                        t.alreadyPresent++;
                    } else {
                        t.projected++;
                    }
                }
                last = batch.get(batch.size() - 1).sourceId;
            }
            out.add(t.result(s));
        }
        return out;
    }

    /**
     * Projects the rows of job {@code jobId}; resumes after the last committed batch.
     *
     * @param sourceZone the zone the legacy engine wrote its timestamps in (see {@link #requireNamedZone})
     */
    public List<Result> project(String jobId, ZoneId sourceZone, int batchSize, String actor, String auditTenant) {
        requireNamedZone(sourceZone);
        Objects.requireNonNull(actor, "actor");
        AuditChain.requireTenant(auditTenant, EVENT_STARTED);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        String chain = CHAIN_PREFIX + jobId;
        target.inTransaction(c -> audit.append(c, auditTenant, chain, EVENT_STARTED,
                Map.of("job", jobId, "actor", actor, "sourceZone", sourceZone.getId())));
        List<Result> out = new ArrayList<>();
        for (Source s : Source.values()) {
            Tally t = new Tally();
            String last = target.inTransaction(c -> Jdbc.one(c, "SELECT last_id FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?",
                    rs -> rs.getString(1), jobId, CHECKPOINT_PREFIX + s.name()).orElse(null));
            while (true) {
                List<MigRow> batch = batch(jobId, s, last, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                String from = last;
                String next = batch.get(batch.size() - 1).sourceId;
                target.inTransaction(c -> {
                    projectBatch(c, jobId, s, batch, sourceZone, chain, t);
                    checkpoint(c, jobId, s, from == null, next, t);
                    return null;
                });
                last = next;
            }
            out.add(t.result(s));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job", jobId);
        summary.put("actor", actor);
        for (Result r : out) {
            summary.put(r.source().name(), Map.of("read", r.read(), "projected", r.projected(),
                    "alreadyPresent", r.alreadyPresent(), "unresolved", r.unresolved()));
        }
        target.inTransaction(c -> audit.append(c, auditTenant, chain, EVENT_COMPLETED, summary));
        return out;
    }

    // ------------------------------------------------------------------ one batch

    private void projectBatch(Connection c, String jobId, Source s, List<MigRow> batch, ZoneId zone, String chain, Tally t) {
        Map<String, List<String>> digestByTenant = new TreeMap<>();
        Map<String, String[]> rangeByTenant = new TreeMap<>();
        for (MigRow r : batch) {
            t.read++;
            Mapped m = map(jobId, s, r, zone);
            if (m.unresolved != null) {
                t.unresolved(r.sourceId, m.unresolved);
                audit.append(c, r.tenant, chain, EVENT_UNRESOLVED, Map.of("job", jobId, "sourceTable", s.name(),
                        "sourceId", r.sourceId, "reason", m.unresolved.name(), "detail", m.detail,
                        "sourceZone", zone.getId()));
                continue;
            }
            boolean inserted = s == Source.ACT_HI_OP_LOG
                    ? operation(c, jobId, r, m, chain)
                    : Jdbc.insertIfAbsent(c, dialect, m.insertSql, m.params);
            if (!inserted) {
                t.alreadyPresent++;
                continue;
            }
            t.projected++;
            if (s != Source.ACT_HI_OP_LOG) {
                digestByTenant.computeIfAbsent(r.tenant, k -> new ArrayList<>())
                        .add(r.sourceId + "=" + Hashes.hex(Hashes.sha256(r.payloadText)));
                rangeByTenant.merge(r.tenant, new String[]{r.sourceId, r.sourceId}, (a, b) -> new String[]{a[0], b[1]});
            }
        }
        for (Map.Entry<String, List<String>> e : digestByTenant.entrySet()) {
            String[] range = rangeByTenant.get(e.getKey());
            audit.append(c, e.getKey(), chain, EVENT_IMPORTED, Map.of("job", jobId, "sourceTable", s.name(),
                    "targetTable", s.target(), "count", e.getValue().size(), "firstSourceId", range[0],
                    "lastSourceId", range[1], "sha256",
                    Hashes.hex(Hashes.sha256(String.join("\n", e.getValue()).getBytes(StandardCharsets.UTF_8)))));
        }
    }

    /** A legacy user operation becomes one audit event (values hashed, never copied). True when it was new. */
    private boolean operation(Connection c, String jobId, MigRow r, Mapped m, String chain) {
        // OPS_MIG_CHECKPOINT advances in the same transaction, so a committed operation is never read again; a
        // replayed batch after a crash re-runs from the last committed checkpoint with nothing of it committed.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("job", jobId);
        p.put("sourceId", r.sourceId);
        m.audit.forEach(p::put);
        audit.append(c, r.tenant, chain, EVENT_OPERATION, p);
        return true;
    }

    private void checkpoint(Connection c, String jobId, Source s, boolean first, String next, Tally t) {
        // copied counts projected rows and mismatched unresolved rows of all runs; status UNRESOLVED stays once set
        Instant now = Jdbc.dbNow(c, dialect);
        long projected = t.projected - t.committedProjected;
        long unresolved = t.unresolved - t.committedUnresolved;
        String status = unresolved > 0 ? "UNRESOLVED" : "DONE";
        if (first) {
            // no checkpoint row exists yet: a row is only ever written here, together with a non-null last_id
            Jdbc.update(c, "INSERT INTO OPS_MIG_CHECKPOINT(job_id,source_table,last_id,copied,updated_at,failed,mismatched,status)"
                    + " VALUES (?,?,?,?,?,0,?,?)", jobId, CHECKPOINT_PREFIX + s.name(), next, projected, now, unresolved, status);
        } else {
            Jdbc.update(c, "UPDATE OPS_MIG_CHECKPOINT SET last_id=?, copied=copied+?, mismatched=mismatched+?, updated_at=?,"
                            + " status=CASE WHEN status='UNRESOLVED' THEN status ELSE ? END WHERE job_id=? AND source_table=?",
                    next, projected, unresolved, now, status, jobId, CHECKPOINT_PREFIX + s.name());
        }
        // the transaction commits after this method; a failure rolls back rows and checkpoint together and ends the run
        t.committedProjected = t.projected;
        t.committedUnresolved = t.unresolved;
    }

    // ------------------------------------------------------------------ mapping

    private record MigRow(String sourceId, String tenant, Map<String, Object> payload, String payloadText) {
    }

    private static final class Mapped {
        String id;
        String insertSql;
        Object[] params;
        Map<String, Object> audit;
        Unresolved unresolved;
        String detail;

        static Mapped unresolved(Unresolved why, String detail) {
            Mapped m = new Mapped();
            m.unresolved = why;
            m.detail = detail == null ? "" : detail;
            return m;
        }
    }

    /** Thrown inside mapping when a value cannot be resolved; turned into {@link Mapped#unresolved}. */
    private static final class Refused extends RuntimeException {
        final Unresolved why;

        Refused(Unresolved why, String detail) {
            super(detail, null, false, false);
            this.why = why;
        }
    }

    private Mapped map(String jobId, Source s, MigRow r, ZoneId zone) {
        try {
            return switch (s) {
                case ACT_HI_PROCINST -> processInstance(jobId, r, zone);
                case ACT_HI_CASEINST -> caseInstance(jobId, r, zone);
                case ACT_HI_ACTINST -> activity(jobId, r, zone);
                case ACT_HI_TASKINST -> task(jobId, r, zone);
                case ACT_HI_OP_LOG -> operationLog(jobId, r, zone);
            };
        } catch (Refused e) {
            return Mapped.unresolved(e.why, e.getMessage());
        }
    }

    private static String ref(String jobId, Source s, MigRow r) {
        return jobId + ":" + s.name() + ":" + r.sourceId;
    }

    private Mapped processInstance(String jobId, MigRow r, ZoneId zone) {
        Map<String, Object> p = r.payload;
        String legacy = require(text(p, "proc_inst_id_", "id_"));
        Instant end = instant(p, "end_time_", zone);
        String state = text(p, "state_");
        state = state != null ? state.toLowerCase(java.util.Locale.ROOT) : end == null ? "active" : "completed";
        String superId = text(p, "super_process_instance_id_", "super_case_instance_id_");
        return instance(jobId, Source.ACT_HI_PROCINST, r, legacy, "process", text(p, "proc_def_key_"),
                text(p, "proc_def_id_"), text(p, "business_key_"), state, superId, text(p, "root_proc_inst_id_"),
                instant(p, "start_time_", zone), end);
    }

    /** CaseExecutionState codes of Operaton 2.2.0 (checked against the engine jar); any other code is unresolved. */
    static final String[] CASE_STATES = {"new", "available", "enabled", "disabled", "active", "suspended", "terminated",
            "completed", "failed", "closed"};

    private Mapped caseInstance(String jobId, MigRow r, ZoneId zone) {
        Map<String, Object> p = r.payload;
        String legacy = require(text(p, "case_inst_id_", "id_"));
        String code = text(p, "state_");
        String state = null;
        if (code != null) {
            int n;
            try {
                n = Integer.parseInt(code.trim());
            } catch (NumberFormatException e) {
                throw new Refused(Unresolved.UNKNOWN_STATE, "case state " + code);
            }
            if (n < 0 || n >= CASE_STATES.length) {
                throw new Refused(Unresolved.UNKNOWN_STATE, "case state " + code);
            }
            state = CASE_STATES[n];
        }
        String superId = text(p, "super_case_instance_id_", "super_process_instance_id_");
        return instance(jobId, Source.ACT_HI_CASEINST, r, legacy, "case", null, text(p, "case_def_id_"),
                text(p, "business_key_"), state, superId, null, instant(p, "create_time_", zone),
                instant(p, "close_time_", zone));
    }

    private Mapped instance(String jobId, Source s, MigRow r, String legacy, String scope, String defKey, String defId,
                            String businessKey, String state, String superId, String rootId, Instant start, Instant end) {
        Mapped m = new Mapped();
        m.id = legacyId(jobId, "instance", legacy);
        m.insertSql = "INSERT INTO HRM_PROCESS_INSTANCE(id, tenant_id, scope_type, definition_key, definition_id, business_key,"
                + " state, super_id, root_id, start_time, end_time, last_seq, record_source, legacy_ref)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,1,'LEGACY',?)";
        m.params = new Object[]{m.id, r.tenant, scope, defKey, defId, businessKey, state,
                superId == null ? null : legacyId(jobId, "instance", superId),
                rootId == null ? null : legacyId(jobId, "instance", rootId), start, end, ref(jobId, s, r)};
        return m;
    }

    private Mapped activity(String jobId, MigRow r, ZoneId zone) {
        Map<String, Object> p = r.payload;
        String legacy = require(text(p, "id_"));
        String instance = text(p, "proc_inst_id_");
        String parent = text(p, "parent_act_inst_id_");
        Instant end = instant(p, "end_time_", zone);
        // ActivityInstanceState of the engine: 2 = canceled (checked against the engine jar)
        String state = end == null ? "open" : "2".equals(text(p, "act_inst_state_")) ? "canceled" : "completed";
        Mapped m = new Mapped();
        m.id = legacyId(jobId, "activity", legacy);
        m.insertSql = "INSERT INTO HRM_ACTIVITY_INSTANCE(id, tenant_id, process_instance_id, activity_id, activity_type, parent_id,"
                + " state, start_time, end_time, record_source, legacy_ref) VALUES (?,?,?,?,?,?,?,?,?,'LEGACY',?)";
        // the root activity instance of a process instance has the process instance's id
        String parentId = parent == null ? null : parent.equals(instance) ? legacyId(jobId, "instance", parent)
                : legacyId(jobId, "activity", parent);
        m.params = new Object[]{m.id, r.tenant, instance == null ? null : legacyId(jobId, "instance", instance),
                text(p, "act_id_"), text(p, "act_type_"), parentId, state, instant(p, "start_time_", zone), end,
                ref(jobId, Source.ACT_HI_ACTINST, r)};
        return m;
    }

    private Mapped task(String jobId, MigRow r, ZoneId zone) {
        Map<String, Object> p = r.payload;
        String legacy = require(text(p, "id_"));
        Instant end = instant(p, "end_time_", zone);
        String reason = text(p, "delete_reason_");
        String assignee = text(p, "assignee_");
        // TaskEntity.DELETE_REASON_COMPLETED = "completed" (checked against the engine jar); any other end is a delete
        String state = end == null ? (assignee == null ? "created" : "assigned") : "completed".equals(reason) ? "completed" : "deleted";
        String instance = text(p, "proc_inst_id_");
        String caseInstance = text(p, "case_inst_id_");
        Mapped m = new Mapped();
        m.id = legacyId(jobId, "task", legacy);
        m.insertSql = "INSERT INTO HRM_TASK(id, tenant_id, process_instance_id, case_instance_id, task_def_key, name, assignee,"
                + " owner_id, state, delete_reason, create_time, end_time, record_source, legacy_ref)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'LEGACY',?)";
        m.params = new Object[]{m.id, r.tenant, instance == null ? null : legacyId(jobId, "instance", instance),
                caseInstance == null ? null : legacyId(jobId, "instance", caseInstance), text(p, "task_def_key_"),
                text(p, "name_"), assignee, text(p, "owner_"), state, reason, instant(p, "start_time_", zone), end,
                ref(jobId, Source.ACT_HI_TASKINST, r)};
        return m;
    }

    private Mapped operationLog(String jobId, MigRow r, ZoneId zone) {
        Map<String, Object> p = r.payload;
        Instant at = instant(p, "timestamp_", zone);
        if (at == null) {
            throw new Refused(Unresolved.BAD_TIMESTAMP, "timestamp_ missing");
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("legacyAt", at.toString());
        a.put("userId", text(p, "user_id_"));
        a.put("operationId", text(p, "operation_id_"));
        a.put("operationType", text(p, "operation_type_"));
        a.put("entityType", text(p, "entity_type_"));
        a.put("category", text(p, "category_"));
        a.put("property", text(p, "property_"));
        a.put("orgValueSha256", sha(text(p, "org_value_")));
        a.put("newValueSha256", sha(text(p, "new_value_")));
        String instance = text(p, "proc_inst_id_");
        a.put("processInstanceId", instance == null ? null : legacyId(jobId, "instance", instance));
        String caseInstance = text(p, "case_inst_id_");
        a.put("caseInstanceId", caseInstance == null ? null : legacyId(jobId, "instance", caseInstance));
        String task = text(p, "task_id_");
        a.put("taskId", task == null ? null : legacyId(jobId, "task", task));
        a.put("legacyRef", ref(jobId, Source.ACT_HI_OP_LOG, r));
        a.values().removeIf(Objects::isNull);
        Mapped m = new Mapped();
        m.audit = a;
        return m;
    }

    private static String sha(String v) {
        return v == null ? null : Hashes.hex(Hashes.sha256(v));
    }

    private static String require(String id) {
        if (id == null || id.isBlank()) {
            throw new Refused(Unresolved.MISSING_ID, "no legacy id");
        }
        return id;
    }

    private static String text(Map<String, Object> p, String... keys) {
        for (String k : keys) {
            Object v = p.get(k);
            if (v != null && !v.toString().isEmpty()) {
                return v.toString();
            }
        }
        return null;
    }

    /**
     * The instant of a legacy wall-clock value. The migrator stored it as {@code <wall clock>Z}; it is resolved in
     * {@code zone}. A gap (no such local time) or an overlap (two instants) is refused, never guessed.
     */
    static Instant instant(Map<String, Object> p, String key, ZoneId zone) {
        String v = text(p, key);
        if (v == null) {
            return null;
        }
        LocalDateTime wall;
        try {
            wall = LocalDateTime.ofInstant(Instant.parse(v), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new Refused(Unresolved.BAD_TIMESTAMP, key + " " + v);
        }
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(wall);
        if (offsets.isEmpty()) {
            throw new Refused(Unresolved.DST_GAP, key + " " + wall + " does not exist in " + zone.getId());
        }
        if (offsets.size() > 1) {
            throw new Refused(Unresolved.DST_OVERLAP, key + " " + wall + " is ambiguous in " + zone.getId() + " " + offsets);
        }
        return ZonedDateTime.ofLocal(wall, zone, offsets.get(0)).toInstant();
    }

    // ------------------------------------------------------------------ reads

    private List<MigRow> batch(String jobId, Source s, String after, int size) {
        String sql = "SELECT source_id, tenant_id, payload FROM OPS_MIG_ROW WHERE job_id=? AND source_table=?"
                + (after == null ? "" : " AND source_id > ?") + " ORDER BY source_id";
        Object[] params = after == null ? new Object[]{jobId, s.name()} : new Object[]{jobId, s.name(), after};
        return target.inTransaction(c -> {
            List<MigRow> rows = new ArrayList<>();
            try (var ps = c.prepareStatement(sql)) {
                ps.setMaxRows(size);
                Jdbc.bind(ps, sql, 1, params);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String payload = rs.getString(3);
                        rows.add(new MigRow(rs.getString(1), TenantKey.of(rs.getString(2)), Json.readMap(payload), payload));
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("cannot read OPS_MIG_ROW of job " + jobId, e);
            }
            return rows;
        });
    }

    private boolean present(String table, String id) {
        return target.inTransaction(c -> Jdbc.one(c, "SELECT 1 FROM " + table + " WHERE id=?", rs -> 1, id).isPresent());
    }

    private static final class Tally {
        long read;
        long projected;
        long alreadyPresent;
        long unresolved;
        long committedProjected;
        long committedUnresolved;
        final List<String> ids = new ArrayList<>();

        void unresolved(String sourceId, Unresolved why) {
            unresolved++;
            if (ids.size() < MAX_LISTED) {
                ids.add(sourceId + " " + why);
            }
        }

        Result result(Source s) {
            return new Result(s, read, projected, alreadyPresent, unresolved, ids);
        }
    }
}
