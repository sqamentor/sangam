// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.sequence;

import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

import java.util.List;
import java.util.Map;

/**
 * Administrator view of the module-local sequence tables, for example {@code RES_BOOKING_SEQ} in sangam-resources and
 * {@code CFG_SERIES} in sangam-config. The counters stay in their modules. An administrator can list values and move one forward, with a
 * required reason. Moving backward is refused, because it would reissue references.
 */
public final class SequenceAdmin {

    /** How to read one counter table. Identifiers are operator configuration, validated at construction. */
    public record Table(String name, String tenantColumn, String seriesColumn, String periodColumn, String valueColumn) {
        public Table {
            for (String s : List.of(name, tenantColumn, seriesColumn, periodColumn, valueColumn)) {
                if (!s.matches("[A-Za-z0-9_]+")) {
                    throw new IllegalArgumentException("invalid identifier " + s);
                }
            }
        }
    }

    public record Value(String table, String series, String period, long value) {
    }

    private final Dialect dialect;
    private final Transactions tx;
    private final AuditChain audit;
    private final Map<String, Table> tables;

    public SequenceAdmin(Dialect dialect, Transactions tx, AuditChain audit, List<Table> tables) {
        this.dialect = dialect;
        this.tx = tx;
        this.audit = audit;
        this.tables = tables.stream().collect(java.util.stream.Collectors.toMap(Table::name, t -> t));
    }

    public List<Value> list(String tenantId, String tableName) {
        Table t = table(tableName);
        return tx.inTransaction(c -> Jdbc.query(c, "SELECT " + t.seriesColumn() + "," + t.periodColumn() + "," + t.valueColumn() + " FROM " + t.name() + " WHERE " + t.tenantColumn() + "=? ORDER BY 1,2",
                rs -> new Value(t.name(), rs.getString(1), rs.getString(2), rs.getLong(3)), tenantId));
    }

    public void advance(String tenantId, String tableName, String series, String period, long newValue, String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        Table t = table(tableName);
        String where = t.tenantColumn() + "=? AND " + t.seriesColumn() + "=? AND " + t.periodColumn() + "=?";
        tx.inTransaction(c -> {
            long current = Jdbc.one(c, dialect.lockingSelect(t.valueColumn(), t.name(), where), rs -> rs.getLong(1), tenantId, series, period)
                    .orElseThrow(() -> new IllegalArgumentException("no such sequence"));
            if (newValue <= current) {
                throw new IllegalArgumentException("SEQUENCE_BACKWARD_REFUSED: current value is " + current);
            }
            Jdbc.update(c, "UPDATE " + t.name() + " SET " + t.valueColumn() + "=? WHERE " + where, newValue, tenantId, series, period);
            audit.append(c, tenantId, "sequences:" + tenantId, "SEQUENCE_ADVANCED", Map.of("table", t.name(), "series", series, "period", period, "from", current, "to", newValue, "actor", actor, "reason", reason));
            return null;
        });
    }

    private Table table(String name) {
        Table t = tables.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown sequence table " + name);
        }
        return t;
    }

    public List<String> tableNames() {
        return tables.keySet().stream().sorted().toList();
    }
}
