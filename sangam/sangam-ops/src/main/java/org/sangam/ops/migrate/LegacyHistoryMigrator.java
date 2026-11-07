// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.migrate;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.Json;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Standalone migrator for Camunda 7 and Operaton history and identity tables ({@code ACT_HI_*}, {@code ACT_ID_*}). The source is
 * only read. Each row is kept as a JSON document with its source id in {@code OPS_MIG_ROW}, so nothing is lost before a mapping to
 * product views is chosen. Keyset batches with a checkpoint make it resumable; rows are insert-if-absent, so a re-run is safe. A dry
 * run only counts. {@link #reconcile} compares source and copied counts. Password hashes and salts are never copied: migrated users
 * authenticate through OIDC. It does not start or move running instances.
 */
public final class LegacyHistoryMigrator {

    /** Copied tables: name, key column. Order puts identities first. */
    public static final List<Table> DEFAULT_TABLES = List.of(
            new Table("ACT_ID_TENANT", "ID_"), new Table("ACT_ID_USER", "ID_"), new Table("ACT_ID_GROUP", "ID_"),
            new Table("ACT_ID_MEMBERSHIP", "USER_ID_", "GROUP_ID_"), new Table("ACT_ID_TENANT_MEMBER", "ID_"),
            new Table("ACT_HI_PROCINST", "ID_"), new Table("ACT_HI_ACTINST", "ID_"), new Table("ACT_HI_TASKINST", "ID_"),
            new Table("ACT_HI_VARINST", "ID_"), new Table("ACT_HI_DETAIL", "ID_"), new Table("ACT_HI_INCIDENT", "ID_"),
            new Table("ACT_HI_IDENTITYLINK", "ID_"), new Table("ACT_HI_COMMENT", "ID_"), new Table("ACT_HI_OP_LOG", "ID_"),
            new Table("ACT_HI_DECINST", "ID_"), new Table("ACT_HI_CASEINST", "ID_"));

    private static final Set<String> NEVER_COPY = Set.of("pwd_", "salt_", "bytes_");
    private static final Map<String, String> SAFE = Map.of();

    /** {@code keyColumns} is one key column, or two for a composite key (their values joined by {@code |} form the source id). */
    public record Table(String name, String... keyColumns) {
        public Table {
            if (!name.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException("invalid table " + name);
            }
        }

        String order() {
            return String.join(", ", keyColumns);
        }

        String id(ResultSet rs) throws SQLException {
            List<String> v = new ArrayList<>();
            for (String k : keyColumns) {
                v.add(rs.getString(k));
            }
            return String.join("|", v);
        }

        /** Keyset predicate after the last id; the parameters come from {@link #params}. */
        String after() {
            return keyColumns.length == 1 ? keyColumns[0] + " > ?"
                    : "(" + keyColumns[0] + " > ? OR (" + keyColumns[0] + " = ? AND " + keyColumns[1] + " > ?))";
        }

        Object[] params(String last) {
            if (keyColumns.length == 1) {
                return new Object[]{last};
            }
            String[] p = last.split("\\|", 2);
            return new Object[]{p[0], p[0], p[1]};
        }
    }

    public record TableResult(String table, long sourceCount, long copied, boolean matches) {
    }

    private final DataSource source;
    private final Transactions target;
    private final Dialect dialect;
    private final AuditChain audit;
    private final String defaultTenant;

    public LegacyHistoryMigrator(DataSource source, Transactions target, Dialect targetDialect, AuditChain audit, String defaultTenant) {
        this.source = source;
        this.target = target;
        this.dialect = targetDialect;
        this.audit = audit;
        this.defaultTenant = defaultTenant;
    }

    /** Counts per table without writing anything. A table absent from the source (older schema) counts as 0. */
    public List<TableResult> dryRun(List<Table> tables) throws SQLException {
        List<TableResult> out = new ArrayList<>();
        for (Table t : tables) {
            out.add(new TableResult(t.name(), sourceCount(t), 0, false));
        }
        return out;
    }

    public List<TableResult> run(String jobId, List<Table> tables, int batchSize, String actor) throws SQLException {
        target.inTransaction(c -> audit.append(c, defaultTenant, "migration:" + jobId, "MIGRATION_RUN", Map.of("job", jobId, "actor", actor)));
        for (Table t : tables) {
            if (exists(t)) {
                try {
                    copy(jobId, t, batchSize);
                } catch (SQLException | RuntimeException e) {
                    markFailed(jobId, t);
                    throw e;
                }
            }
        }
        List<TableResult> result = reconcile(jobId, tables);
        target.inTransaction(c -> audit.append(c, defaultTenant, "migration:" + jobId, "MIGRATION_RECONCILED",
                Map.of("job", jobId, "matches", result.stream().allMatch(TableResult::matches))));
        return result;
    }

    public List<TableResult> reconcile(String jobId, List<Table> tables) throws SQLException {
        List<TableResult> out = new ArrayList<>();
        for (Table t : tables) {
            long src = sourceCount(t);
            long copied = target.inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM OPS_MIG_ROW WHERE job_id=? AND source_table=?", rs -> rs.getLong(1), jobId, t.name()).orElse(0L));
            out.add(new TableResult(t.name(), src, copied, src == copied));
            // Checkpoint status: DONE when counts match, else MISMATCHED with the difference; FAILED (set by run) is kept.
            target.inTransaction(c -> Jdbc.update(c, "UPDATE OPS_MIG_CHECKPOINT SET mismatched=?, status=CASE WHEN status='FAILED' THEN status WHEN ?=0 THEN 'DONE' ELSE 'MISMATCHED' END,"
                    + " updated_at=? WHERE job_id=? AND source_table=?", Math.abs(src - copied), Math.abs(src - copied), Jdbc.dbNow(c, dialect), jobId, t.name()));
        }
        return out;
    }

    /** The checkpoint rows of a job: table, copied, failed, mismatched, status. */
    public record Checkpoint(String table, long copied, long failed, long mismatched, String status) {
    }

    public List<Checkpoint> checkpoints(String jobId) {
        return target.inTransaction(c -> Jdbc.query(c, "SELECT source_table,copied,failed,mismatched,status FROM OPS_MIG_CHECKPOINT WHERE job_id=? ORDER BY source_table",
                rs -> new Checkpoint(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)), jobId));
    }

    private void markFailed(String jobId, Table t) {
        target.inTransaction(c -> {
            java.time.Instant now = Jdbc.dbNow(c, dialect);
            if (Jdbc.update(c, "UPDATE OPS_MIG_CHECKPOINT SET failed=failed+1, status='FAILED', updated_at=? WHERE job_id=? AND source_table=?", now, jobId, t.name()) == 0) {
                Jdbc.update(c, "INSERT INTO OPS_MIG_CHECKPOINT(job_id,source_table,last_id,copied,updated_at,failed,mismatched,status) VALUES (?,?,NULL,0,?,1,0,'FAILED')", jobId, t.name(), now);
            }
            return null;
        });
    }

    private void copy(String jobId, Table t, int batchSize) throws SQLException {
        String last = target.inTransaction(c -> Jdbc.one(c, "SELECT last_id FROM OPS_MIG_CHECKPOINT WHERE job_id=? AND source_table=?", rs -> rs.getString(1), jobId, t.name()).orElse(null));
        while (true) {
            List<Row> batch = read(t, last, batchSize);
            if (batch.isEmpty()) {
                return;
            }
            String next = batch.get(batch.size() - 1).id;
            boolean first = last == null;
            target.inTransaction(c -> {
                java.time.Instant now = Jdbc.dbNow(c, dialect);
                long n = 0;
                for (Row r : batch) {
                    if (Jdbc.insertIfAbsent(c, dialect, "INSERT INTO OPS_MIG_ROW(job_id,source_table,source_id,tenant_id,payload,migrated_at) VALUES (?,?,?,?,?,?)",
                            jobId, t.name(), r.id, r.tenant, Json.write(r.payload), now)) {
                        n++;
                    }
                }
                if (first) {
                    Jdbc.update(c, "INSERT INTO OPS_MIG_CHECKPOINT(job_id,source_table,last_id,copied,updated_at,failed,mismatched,status) VALUES (?,?,?,?,?,0,0,'RUNNING')", jobId, t.name(), next, n, now);
                } else {
                    Jdbc.update(c, "UPDATE OPS_MIG_CHECKPOINT SET last_id=?, copied=copied+?, updated_at=?, status='RUNNING' WHERE job_id=? AND source_table=?", next, n, now, jobId, t.name());
                }
                return null;
            });
            last = next;
        }
    }

    private record Row(String id, String tenant, Map<String, Object> payload) {
    }

    private List<Row> read(Table t, String last, int batchSize) throws SQLException {
        String sql = "SELECT * FROM " + t.name() + (last == null ? "" : " WHERE " + t.after()) + " ORDER BY " + t.order();
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(batchSize);
            ps.setFetchSize(batchSize);
            if (last != null) {
                Jdbc.bind(ps, 1, t.params(last));
            }
            List<Row> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String tenant = defaultTenant;
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String col = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                        if (NEVER_COPY.contains(col)) {
                            continue;
                        }
                        Object v = rs.getObject(i);
                        if (col.equals("tenant_id_") && v != null) {
                            tenant = v.toString();
                        }
                        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean) {
                            m.put(col, v);
                        } else if (v instanceof java.sql.Timestamp ts) {
                            m.put(col, ts.toInstant().toString());
                        } else if (!(v instanceof byte[]) && !(v instanceof java.sql.Blob)) {
                            m.put(col, v.toString());
                        }
                    }
                    out.add(new Row(t.id(rs), tenant, m));
                }
            }
            return out;
        }
    }

    private boolean exists(Table t) throws SQLException {
        try (Connection c = source.getConnection(); ResultSet rs = c.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (t.name().equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private long sourceCount(Table t) throws SQLException {
        if (!exists(t)) {
            return 0;
        }
        try (Connection c = source.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + t.name()); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
