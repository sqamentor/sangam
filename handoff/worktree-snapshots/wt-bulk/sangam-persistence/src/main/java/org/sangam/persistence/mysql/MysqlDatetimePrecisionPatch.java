// SPDX-License-Identifier: Apache-2.0
package org.sangam.persistence.mysql;

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
 * <b>Engine schema patch P-1 (MySQL only; FLOWABLE-INTO-OPERATON D-03, FLOWABLE-INTO-OPERATON FC-30).</b> Operaton 2.2.0's MySQL create scripts declare 66 time
 * columns with whole-second precision (61 {@code datetime} + 5 {@code timestamp}), while the MariaDB scripts of the
 * same jar use precision 3. Measured effects (spike patch ledger, MySQL (2)): a lease re-offered 200 ms early, a timer
 * fired 300 ms early, start times of different instances collapsed into one second.
 *
 * <p>Changes every {@code ACT_*} column of type {@code datetime}/{@code timestamp} with precision 0 to precision 3,
 * keeping nullability (the scripts declare no time defaults). One {@code ALTER TABLE} per table. Idempotent: run after
 * every engine create/upgrade. Hardened from {@code spike/adapter-operaton/.../MysqlDatetimePrecisionPatch.java}.
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
                + "from information_schema.columns where table_schema = database() "
                + "and upper(table_name) like 'ACT\\_%' "
                + "and data_type in ('datetime', 'timestamp') and datetime_precision = 0 "
                + "order by table_name, ordinal_position");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Column(rs.getString(1), rs.getString(2), rs.getString(3), "YES".equals(rs.getString(4))));
            }
        }
        return out;
    }

    /** Applies the patch (DDL auto-commits on MySQL) and returns the altered columns. */
    public static List<Column> apply(Connection c) throws SQLException {
        List<Column> columns = secondPrecisionColumns(c);
        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        for (Column col : columns) {
            byTable.computeIfAbsent(col.table(), t -> new ArrayList<>()).add(col);
        }
        try (Statement s = c.createStatement()) {
            for (Map.Entry<String, List<Column>> e : byTable.entrySet()) {
                StringBuilder sql = new StringBuilder("alter table `").append(e.getKey()).append('`');
                String sep = " ";
                for (Column col : e.getValue()) {
                    sql.append(sep).append("modify column `").append(col.column()).append("` ")
                            .append(col.type()).append("(3)").append(col.nullable() ? " null" : " not null");
                    sep = ", ";
                }
                s.execute(sql.toString());
            }
        }
        return columns;
    }
}
