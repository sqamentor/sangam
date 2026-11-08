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
 * <b>Engine schema patch P-2 (MySQL only; FLOWABLE-INTO-OPERATON D-04).</b> Operaton 2.2.0 creates its MySQL tables with
 * {@code DEFAULT CHARSET=utf8 COLLATE utf8_bin}, i.e. utf8mb3: 4-byte characters (emoji, CJK extension B) cannot be
 * stored in engine string columns. This patch converts every {@code ACT_*} table whose collation is utf8mb3 to
 * {@code utf8mb4 / utf8mb4_bin} (still binary, so ids stay case-sensitive).
 *
 * <p>Index byte limit: InnoDB DYNAMIC rows allow 3072 bytes per index key. Before converting, the widest index of the
 * table is recomputed at 4 bytes per character; a table whose widest index would exceed the limit is <b>skipped</b> and
 * reported (never half-converted). Idempotent; run after every engine create/upgrade.
 */
public final class MysqlUtf8mb4Patch {

    /** InnoDB key limit with innodb_large_prefix (default since 5.7.7, only mode in 8.x). */
    public static final int MAX_INDEX_BYTES = 3072;

    private MysqlUtf8mb4Patch() {
    }

    /** Outcome per table. */
    public record Result(List<String> converted, Map<String, Integer> skippedWidestIndexBytes) {
    }

    /** {@code ACT_*} tables still in a utf8mb3 collation. */
    public static List<String> utf8mb3Tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("select table_name from information_schema.tables "
                + "where table_schema = database() and upper(table_name) like 'ACT\\_%' and table_type = 'BASE TABLE' "
                + "and (table_collation like 'utf8\\_%' or table_collation like 'utf8mb3%') order by table_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Widest index of {@code table} in bytes if its character columns were utf8mb4 (4 bytes per character). */
    public static int widestIndexBytesAsUtf8mb4(Connection c, String table) throws SQLException {
        Map<String, Integer> perIndex = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select s.index_name, s.sub_part, col.data_type, "
                + "col.character_maximum_length, col.numeric_precision "
                + "from information_schema.statistics s join information_schema.columns col "
                + "on col.table_schema = s.table_schema and col.table_name = s.table_name and col.column_name = s.column_name "
                + "where s.table_schema = database() and s.table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String index = rs.getString(1);
                    long subPart = rs.getLong(2);
                    boolean hasSubPart = !rs.wasNull();
                    String type = rs.getString(3);
                    long chars = rs.getLong(4);
                    int bytes;
                    if ("varchar".equalsIgnoreCase(type) || "char".equalsIgnoreCase(type)) {
                        bytes = (int) ((hasSubPart ? subPart : chars) * 4 + 2);
                    } else if (type != null && type.toLowerCase().contains("int")) {
                        bytes = 8;
                    } else {
                        bytes = 8; // datetime/decimal and others: small fixed widths
                    }
                    perIndex.merge(index, bytes, Integer::sum);
                }
            }
        }
        return perIndex.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** Converts every convertible utf8mb3 {@code ACT_*} table; DDL auto-commits on MySQL. */
    public static Result apply(Connection c) throws SQLException {
        List<String> converted = new ArrayList<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();
        for (String table : utf8mb3Tables(c)) {
            int widest = widestIndexBytesAsUtf8mb4(c, table);
            if (widest > MAX_INDEX_BYTES) {
                skipped.put(table, widest);
                continue;
            }
            try (Statement s = c.createStatement()) {
                s.execute("alter table `" + table + "` convert to character set utf8mb4 collate utf8mb4_bin");
            }
            converted.add(table);
        }
        return new Result(converted, skipped);
    }
}
