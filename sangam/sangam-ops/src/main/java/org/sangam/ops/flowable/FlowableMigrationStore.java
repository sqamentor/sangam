// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.flowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

import java.sql.Connection;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * M-135: JDBC persistence of migration runs and items ({@link FlowableMigrationSchema}). Every method works on the
 * caller's connection, so the pipeline writes the state change and its audit event in one transaction. Run state
 * changes are compare-and-set on {@code (state, version)}: two operators (or nodes) cannot both move a run.
 */
public final class FlowableMigrationStore {

    /** Run states; see {@link FlowableMigrationPipeline} for the allowed transitions. */
    public enum RunState { DRY_RUN, FROZEN, APPLYING, APPLIED, RECONCILED, RECONCILE_FAILED, ROLLED_BACK, CUT_OVER }

    /** Item statuses. */
    public enum ItemStatus { PLANNED, REJECTED, APPLYING, APPLIED, FAILED, MATCHED, MISMATCHED, ROLLED_BACK }

    public record Run(String runId, String tenantId, String sourceSystem, String sourceTenant, String sourceZone,
                      RunState state, List<DefinitionMapping> mappings, long planned, long rejected, long applied,
                      long failed, long matched, long mismatched, long version) {
    }

    public record Item(String runId, String sourceInstanceId, String tenantId, ItemStatus status, String fingerprint,
                       InstancePlan plan, String targetInstanceId, int attempts, String error) {
    }

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Dialect dialect;

    public FlowableMigrationStore(Dialect dialect) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    Dialect dialect() {
        return dialect;
    }

    public void createRun(Connection c, Run r, String actor) {
        Instant now = Jdbc.dbNow(c, dialect);
        Jdbc.update(c, "INSERT INTO OPS_FMIG_RUN(run_id,tenant_id,source_system,source_tenant,source_zone,state,mapping_json,"
                        + "planned,rejected,applied,failed,matched,mismatched,version,created_by,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,0,0,0,0,1,?,?,?)",
                r.runId(), r.tenantId(), r.sourceSystem(), r.sourceTenant(), r.sourceZone(), r.state().name(), write(r.mappings()),
                r.planned(), r.rejected(), actor, now, now);
    }

    public Optional<Run> run(Connection c, String runId) {
        return Jdbc.one(c, "SELECT run_id,tenant_id,source_system,source_tenant,source_zone,state,mapping_json,planned,rejected,"
                + "applied,failed,matched,mismatched,version FROM OPS_FMIG_RUN WHERE run_id=?", rs -> new Run(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), RunState.valueOf(rs.getString(6)),
                read(rs.getString(7), new TypeReference<List<DefinitionMapping>>() { }), rs.getLong(8), rs.getLong(9),
                rs.getLong(10), rs.getLong(11), rs.getLong(12), rs.getLong(13), rs.getLong(14)), runId);
    }

    /**
     * Moves the run from {@code expected} (at {@code version}) to {@code next}; false when another actor moved it
     * first (nothing changed).
     */
    public boolean transition(Connection c, String runId, RunState expected, long version, RunState next) {
        return Jdbc.update(c, "UPDATE OPS_FMIG_RUN SET state=?, version=version+1, updated_at=? WHERE run_id=? AND state=? AND version=?",
                next.name(), Jdbc.dbNow(c, dialect), runId, expected.name(), version) == 1;
    }

    public void report(Connection c, String runId, String reportJson) {
        Jdbc.update(c, "UPDATE OPS_FMIG_RUN SET report_json=?, updated_at=? WHERE run_id=?", reportJson, Jdbc.dbNow(c, dialect), runId);
    }

    /** Recomputes the run counters from its items. */
    public void recount(Connection c, String runId) {
        long[] n = new long[ItemStatus.values().length];
        for (Object[] row : Jdbc.query(c, "SELECT status, COUNT(*) FROM OPS_FMIG_ITEM WHERE run_id=? GROUP BY status",
                rs -> new Object[]{rs.getString(1), rs.getLong(2)}, runId)) {
            n[ItemStatus.valueOf((String) row[0]).ordinal()] = (Long) row[1];
        }
        long applied = n[ItemStatus.APPLIED.ordinal()] + n[ItemStatus.MATCHED.ordinal()] + n[ItemStatus.MISMATCHED.ordinal()];
        long planned = applied + n[ItemStatus.PLANNED.ordinal()] + n[ItemStatus.APPLYING.ordinal()] + n[ItemStatus.FAILED.ordinal()]
                + n[ItemStatus.ROLLED_BACK.ordinal()];
        Jdbc.update(c, "UPDATE OPS_FMIG_RUN SET planned=?, rejected=?, applied=?, failed=?, matched=?, mismatched=?, updated_at=?"
                        + " WHERE run_id=?", planned, n[ItemStatus.REJECTED.ordinal()], applied, n[ItemStatus.FAILED.ordinal()],
                n[ItemStatus.MATCHED.ordinal()], n[ItemStatus.MISMATCHED.ordinal()], Jdbc.dbNow(c, dialect), runId);
    }

    public void insertItems(Connection c, String runId, Collection<InstancePlan> plans, String runTenant) {
        Instant now = Jdbc.dbNow(c, dialect);
        for (InstancePlan p : plans) {
            Jdbc.update(c, "INSERT INTO OPS_FMIG_ITEM(run_id,source_instance_id,tenant_id,definition_key,status,fingerprint,plan_json,"
                            + "attempts,updated_at) VALUES (?,?,?,?,?,?,?,0,?)", runId, p.sourceInstanceId(), runTenant,
                    p.definitionKey(), (p.status() == InstancePlan.Status.SUPPORTED ? ItemStatus.PLANNED : ItemStatus.REJECTED).name(),
                    p.fingerprint(), write(p), now);
        }
    }

    public List<Item> items(Connection c, String runId, Collection<ItemStatus> statuses) {
        List<String> names = statuses.stream().map(Enum::name).toList();
        String in = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        Object[] params = new Object[names.size() + 1];
        params[0] = runId;
        for (int i = 0; i < names.size(); i++) {
            params[i + 1] = names.get(i);
        }
        return Jdbc.query(c, "SELECT run_id,source_instance_id,tenant_id,status,fingerprint,plan_json,target_instance_id,attempts,error"
                + " FROM OPS_FMIG_ITEM WHERE run_id=? AND status IN (" + in + ") ORDER BY source_instance_id", rs -> new Item(
                rs.getString(1), rs.getString(2), rs.getString(3), ItemStatus.valueOf(rs.getString(4)), rs.getString(5),
                read(rs.getString(6), new TypeReference<InstancePlan>() { }), rs.getString(7), rs.getInt(8), rs.getString(9)), params);
    }

    /** Marks an item before the engine call (attempts + 1); false when it is no longer in {@code expected}. */
    public boolean begin(Connection c, String runId, String sourceId, ItemStatus expected) {
        return Jdbc.update(c, "UPDATE OPS_FMIG_ITEM SET status=?, attempts=attempts+1, error=NULL, updated_at=? WHERE run_id=?"
                + " AND source_instance_id=? AND status=?", ItemStatus.APPLYING.name(), Jdbc.dbNow(c, dialect), runId, sourceId,
                expected.name()) == 1;
    }

    public void finish(Connection c, String runId, String sourceId, ItemStatus status, String targetId, String error) {
        Jdbc.update(c, "UPDATE OPS_FMIG_ITEM SET status=?, target_instance_id=COALESCE(?, target_instance_id), error=?, updated_at=?"
                + " WHERE run_id=? AND source_instance_id=?", status.name(), targetId, error, Jdbc.dbNow(c, dialect), runId, sourceId);
    }

    /**
     * Another run of the same source system that applied this source instance and was not rolled back (its target
     * instance id), so the instance is never migrated twice.
     */
    public Optional<String> appliedElsewhere(Connection c, String sourceSystem, String sourceId, String runId) {
        return Jdbc.one(c, "SELECT i.target_instance_id, i.run_id FROM OPS_FMIG_ITEM i JOIN OPS_FMIG_RUN r ON r.run_id = i.run_id"
                + " WHERE i.source_instance_id=? AND r.source_system=? AND i.run_id<>? AND i.status IN ('APPLYING','APPLIED','MATCHED','MISMATCHED')",
                rs -> rs.getString(1) == null ? "(being applied in run " + rs.getString(2) + ")" : rs.getString(1),
                sourceId, sourceSystem, runId);
    }

    // ------------------------------------------------------------------ decision 231: listener acknowledgements

    /** An operator's acknowledgement of a source definition's listeners (OPS_FMIG_ACK, changeset 0045-07). */
    public record Acknowledgement(String tenantId, String sourceSystem, String definitionId, String definitionKey,
                                  int definitionVersion, String listenersSha256, String listenersJson, String reason,
                                  String acknowledgedBy, Instant acknowledgedAt) {
    }

    /** Flowable definition id to acknowledged listener fingerprint, for one tenant and source system. */
    public java.util.Map<String, String> acknowledgements(Connection c, String tenantId, String sourceSystem) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String[] r : Jdbc.query(c, "SELECT definition_id, listeners_sha256 FROM OPS_FMIG_ACK WHERE tenant_id=? AND source_system=?",
                rs -> new String[]{rs.getString(1), rs.getString(2)}, tenantId, sourceSystem)) {
            out.put(r[0], r[1]);
        }
        return out;
    }

    public Optional<Acknowledgement> acknowledgement(Connection c, String tenantId, String sourceSystem, String definitionId) {
        return Jdbc.one(c, "SELECT tenant_id, source_system, definition_id, definition_key, definition_version, listeners_sha256,"
                        + " listeners_json, reason, acknowledged_by, acknowledged_at FROM OPS_FMIG_ACK"
                        + " WHERE tenant_id=? AND source_system=? AND definition_id=?",
                rs -> new Acknowledgement(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), Jdbc.instant(rs, "acknowledged_at")),
                tenantId, sourceSystem, definitionId);
    }

    /** Inserts the acknowledgement, or replaces one for other listeners; the caller writes the audit event. */
    public void acknowledge(Connection c, Acknowledgement a) {
        int n = Jdbc.update(c, "UPDATE OPS_FMIG_ACK SET definition_key=?, definition_version=?, listeners_sha256=?, listeners_json=?,"
                        + " reason=?, acknowledged_by=?, acknowledged_at=? WHERE tenant_id=? AND source_system=? AND definition_id=?",
                a.definitionKey(), a.definitionVersion(), a.listenersSha256(), a.listenersJson(), a.reason(), a.acknowledgedBy(),
                a.acknowledgedAt(), a.tenantId(), a.sourceSystem(), a.definitionId());
        if (n == 0) {
            Jdbc.update(c, "INSERT INTO OPS_FMIG_ACK(tenant_id, source_system, definition_id, definition_key, definition_version,"
                            + " listeners_sha256, listeners_json, reason, acknowledged_by, acknowledged_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    a.tenantId(), a.sourceSystem(), a.definitionId(), a.definitionKey(), a.definitionVersion(), a.listenersSha256(),
                    a.listenersJson(), a.reason(), a.acknowledgedBy(), a.acknowledgedAt());
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write migration JSON: " + e.getMessage(), e);
        }
    }

    static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read migration JSON: " + e.getMessage(), e);
        }
    }
}
