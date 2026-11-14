// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;

/**
 * Decision 150 (W4-E REQUEST 2026-11-13T01:36:38Z item 3, accepted): the UTC tool's apply refuses while an Operaton job
 * ({@code ACT_RU_JOB}) or external task ({@code ACT_RU_EXT_TASK}) holds an unexpired lock ({@code LOCK_EXP_TIME_} after
 * the database clock), in addition to the operator's maintenance reference: a locked job or task is being executed and
 * may write any table. Read before the apply starts and again in every batch transaction; the rollback does the same
 * (owner decision 240).
 * <p>
 * When the engine tables are not visible on the tool's connection (engine in another schema or database), a product
 * table is still converted under the maintenance reference alone, but an {@code ACT_*} table never is: converting
 * engine rows needs the engine schema, so its lock tables must be readable.
 */
final class EngineLocks {

    static final List<String> LOCK_TABLES = List.of("ACT_RU_JOB", "ACT_RU_EXT_TASK");

    private EngineLocks() {
    }

    /** Throws {@link IllegalStateException} naming every lock table with an unexpired lock. */
    static void requireNone(Connection c, Dialect dialect, String datasetTable) {
        Instant now = Jdbc.dbNow(c, dialect);
        List<String> live = new ArrayList<>();
        int visible = 0;
        for (String table : LOCK_TABLES) {
            String name = visibleName(c, table);
            if (name == null) {
                continue;
            }
            visible++;
            long n = Jdbc.one(c, "SELECT COUNT(*) FROM " + name + " WHERE LOCK_EXP_TIME_ IS NOT NULL AND LOCK_EXP_TIME_ > ?",
                    rs -> rs.getLong(1), now).orElse(0L);
            if (n > 0) {
                live.add(table + " " + n);
            }
        }
        if (!live.isEmpty()) {
            throw new IllegalStateException("the engine holds unexpired locks (" + String.join(", ", live) + "): stop the"
                    + " job executor and external task workers and wait for the locks to expire before converting");
        }
        if (visible < LOCK_TABLES.size() && datasetTable.startsWith("ACT_")) {
            throw new IllegalStateException("engine table " + datasetTable + " is converted only where " + LOCK_TABLES
                    + " are readable on the same connection, to check their locks");
        }
    }

    /** The table name as the database stores it (upper or lower case), or null when the table is not visible. */
    private static String visibleName(Connection c, String table) {
        try {
            DatabaseMetaData md = c.getMetaData();
            for (String name : List.of(table, table.toLowerCase(Locale.ROOT))) {
                try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), name, "%")) {
                    while (rs.next()) {
                        if ("LOCK_EXP_TIME_".equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                            return name;
                        }
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the engine lock tables", e);
        }
    }
}
