package org.sangam.spike.operaton;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Engine schema patch P-1 (MySQL only).</b> Operaton 2.2.0's MySQL create scripts
 * ({@code org/operaton/bpm/engine/db/create/activiti.mysql.create.*.sql}) declare every time column as plain
 * {@code datetime} / {@code timestamp}, i.e. <b>whole seconds</b> (61 {@code datetime} + 5 {@code timestamp} columns),
 * while the MariaDB scripts of the same jar use {@code datetime(3)} / {@code timestamp(3)}. On MySQL a millisecond
 * value written to such a column is rounded to the nearest second (or truncated with Operaton's recommended
 * {@code sendFractionalSeconds=false}), so lock expiries, timer due dates and history times move by up to 0.5 s
 * (1 s when truncated).
 *
 * <p>This patch changes every {@code ACT_*} column of type {@code datetime}/{@code timestamp} with precision 0 in the
 * current schema to precision 3, keeping nullability (the scripts declare no time defaults). Run it after the engine
 * created or upgraded its schema; it is idempotent. Production would ship the same statements as a migration script
 * and re-check them on every engine upgrade (an upgrade script may re-create a table or add new second-precision
 * columns).
 */
public final class MysqlDatetimePrecisionPatch {

    private MysqlDatetimePrecisionPatch() {
    }

    /** One altered column. */
    public record Column(String table, String column, String type, boolean nullable) {
    }

    /** The {@code ACT_*} time columns of the current schema that still have precision 0. */
    public static List<Column> secondPrecisionColumns(Connection c) throws SQLException {
        List<Column> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name, column_name, data_type, is_nullable "
                + "from information_schema.columns where table_schema = database() and table_name like 'ACT\\_%' "
                + "and data_type in ('datetime', 'timestamp') and datetime_precision = 0 "
                + "order by table_name, ordinal_position");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Column(rs.getString(1), rs.getString(2), rs.getString(3), "YES".equals(rs.getString(4))));
            }
        }
        return out;
    }

    /** Applies the patch (one {@code ALTER TABLE} per table, DDL auto-commits) and returns the altered columns. */
    public static List<Column> apply(Connection c) throws SQLException {
        List<Column> columns = secondPrecisionColumns(c);
        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        for (Column col : columns) {
            byTable.computeIfAbsent(col.table(), t -> new ArrayList<>()).add(col);
        }
        try (Statement s = c.createStatement()) {
            for (Map.Entry<String, List<Column>> e : byTable.entrySet()) {
                StringBuilder sql = new StringBuilder("alter table ").append(e.getKey());
                String sep = " ";
                for (Column col : e.getValue()) {
                    sql.append(sep).append("modify column ").append(col.column()).append(' ')
                            .append(col.type()).append("(3)").append(col.nullable() ? " null" : " not null");
                    sep = ", ";
                }
                s.execute(sql.toString());
            }
        }
        return columns;
    }
}
