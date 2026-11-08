// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.ops.retention.HierarchyRetention;
import org.sangam.ops.search.SearchIndexMaintenance;
import org.sangam.rest.modules.ops.OpsApi;

/** Adapters of sangam-ops to the engine: retention selection and purge, index readers, migration status. */
public final class OpsBindings {

    /** A root instance carrying this true history variable is on hold and is never purged. */
    public static final String HOLD_VARIABLE = "sangamLegalHold";

    private OpsBindings() {
    }

    /**
     * Retention by definition key minus every key with an Operaton historyTimeToLive in this tenant: engine cleanup
     * owns those. A key without an entry in the returned map is kept by {@code HierarchyRetention.plan}.
     */
    public static Map<String, Duration> ownedRetention(ProcessEngine engine, String tenant, Map<String, Duration> configured) {
        Map<String, Duration> out = new LinkedHashMap<>();
        configured.forEach((key, ttl) -> {
            boolean engineOwned = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(key)
                    .tenantIdIn(tenant).list().stream().anyMatch(d -> d.getHistoryTimeToLive() != null);
            if (!engineOwned) {
                out.put(key, ttl);
            }
        });
        return out;
    }

    public static OpsApi.Retention retention(HierarchyRetention service, ProcessEngine engine, Map<String, Duration> configured) {
        return new OpsApi.Retention() {
            @Override
            public List<HierarchyRetention.Tree> plan(String tenant, int limit) {
                return service.plan(tenant, ownedRetention(engine, tenant, configured), Instant.now(), root -> onHold(engine, root), limit);
            }

            @Override
            public int execute(String tenant, int limit, String actor, String reason) {
                return service.execute(plan(tenant, limit), (t, ids) -> engine.getHistoryService().deleteHistoricProcessInstancesIfExists(ids), actor, reason);
            }

            @Override
            public List<HierarchyRetention.Run> runs(String tenant) {
                return service.runs(tenant);
            }
        };
    }

    private static boolean onHold(ProcessEngine engine, String rootId) {
        return engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(rootId)
                .variableName(HOLD_VARIABLE).list().stream().anyMatch(v -> Boolean.TRUE.equals(v.getValue()));
    }

    public static List<SearchIndexMaintenance.Reader> engineReaders(Transactions tx) {
        return List.of(new EngineReader(tx, "TASK", "ACT_RU_TASK", "NAME_", "DESCRIPTION_"),
                new EngineReader(tx, "HISTORIC_PROCESS_INSTANCE", "ACT_HI_PROCINST", "BUSINESS_KEY_", "PROC_DEF_KEY_"),
                new EngineReader(tx, "PROCESS_DEFINITION", "ACT_RE_PROCDEF", "NAME_", "KEY_"));
    }

    private record EngineReader(Transactions tx, String type, String table, String title, String body) implements SearchIndexMaintenance.Reader {
        @Override
        public long count(String tenant) {
            return tx.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM " + table + " WHERE TENANT_ID_=?", rs -> rs.getLong(1), tenant).orElse(0L));
        }

        @Override
        public List<SearchIndexMaintenance.Doc> read(String tenant, String afterId, int limit) {
            return tx.inTransaction(c -> {
                String sql = "SELECT ID_, " + title + ", " + body + " FROM " + table + " WHERE TENANT_ID_=?"
                        + (afterId == null ? "" : " AND ID_>?") + " ORDER BY ID_";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setMaxRows(limit);
                    ps.setString(1, tenant);
                    if (afterId != null) {
                        ps.setString(2, afterId);
                    }
                    List<SearchIndexMaintenance.Doc> out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new SearchIndexMaintenance.Doc(rs.getString(1), rs.getString(2), rs.getString(3)));
                        }
                    }
                    return out;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("cannot read " + table, e);
                }
            });
        }
    }

    /**
     * Every job's checkpoints, read through {@link org.sangam.ops.migrate.LegacyHistoryMigrator#checkpoints}. The migrator is
     * built without a legacy source: reading checkpoints touches only the target schema.
     */
    public static List<OpsApi.MigrationStatus> migrationStatus(Transactions tx, org.sangam.ops.migrate.LegacyHistoryMigrator migrator) {
        List<String> jobs = tx.inTransaction(c -> Jdbc.query(c, "SELECT DISTINCT job_id FROM OPS_MIG_CHECKPOINT ORDER BY job_id", rs -> rs.getString(1)));
        List<OpsApi.MigrationStatus> out = new java.util.ArrayList<>();
        for (String job : jobs) {
            migrator.checkpoints(job).forEach(cp -> out.add(new OpsApi.MigrationStatus(job, cp.table(), cp.copied(), cp.failed(), cp.mismatched(), cp.status())));
        }
        return out;
    }
}
