// SPDX-License-Identifier: Apache-2.0
package org.sangam.ops.utc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.integration.connector.Json;

/**
 * Row access of the UTC conversion tool on one table: the primary key from the database's metadata, keyset batches in
 * primary-key order, reads and guarded updates by key. Table and column names come from the inventory (identifier
 * pattern checked there) and are used unquoted, as the generated DDL creates them; values are always bound.
 * <p>
 * Owner decision 240: with a {@link Session}, every statement runs with session zone {@link SessionZones#UTC} (stored
 * instants), and each read also reads the session-zoned columns with the declared historical zone (the wall clock as
 * written); the connection's own session zone is restored after every call.
 */
final class UtcRows {

    /** A row's primary key: typed text values ({@code N:} number, {@code T:} instant, {@code S:} text). */
    record Key(List<String> parts) {
        Key {
            parts = List.copyOf(parts);
        }

        String json() {
            return Json.write(parts);
        }

        String hash() {
            return Hashes.hex(Hashes.sha256(json()));
        }

        static Key parse(String json) {
            List<String> parts = new ArrayList<>();
            JsonNode list = Json.tree(json);
            if (list == null || !list.isArray() || list.isEmpty()) {
                throw new IllegalStateException("not a stored row key: " + json);
            }
            for (JsonNode part : list) {
                parts.add(part.asText());
            }
            return new Key(parts);
        }

        Object[] bindValues() {
            Object[] out = new Object[parts.size()];
            for (int i = 0; i < out.length; i++) {
                String p = parts.get(i);
                String v = p.substring(2);
                out[i] = switch (p.charAt(0)) {
                    case 'N' -> new BigDecimal(v);
                    case 'T' -> Instant.parse(v);
                    case 'S' -> v;
                    default -> throw new IllegalStateException("unknown key part " + p);
                };
            }
            return out;
        }
    }

    /**
     * One row: its key and the read time columns (null values kept as null).
     *
     * @param values the values the conversion reads: the wall clock under the declared session zone for a session-zoned
     *               column, otherwise the stored value
     * @param stored the stored values (equal to {@code values} without a session)
     */
    record Row(Key key, Map<String, Instant> values, Map<String, Instant> stored) {
    }

    /**
     * Decision 240: the declared historical session zone and the session-zoned columns among the time columns.
     *
     * @param zones   the dialect's session zone access
     * @param zone    the declared zone as session zone text
     * @param columns the session-zoned columns of this table that are read
     */
    record Session(SessionZones zones, String zone, Set<String> columns) {
        Session {
            columns = Set.copyOf(columns);
        }
    }

    final String table;
    final List<String> primaryKey;
    final List<String> timeColumns;
    final String tenantColumn;
    final Session session;

    private UtcRows(String table, List<String> primaryKey, List<String> timeColumns, String tenantColumn,
                    Session session) {
        this.table = table;
        this.primaryKey = List.copyOf(primaryKey);
        this.timeColumns = List.copyOf(timeColumns);
        this.tenantColumn = tenantColumn;
        this.session = session;
    }

    /** Reads the table's primary key and checks that every named column exists. */
    static UtcRows of(Connection c, String table, List<String> timeColumns, boolean tenantFiltered) {
        return of(c, table, timeColumns, tenantFiltered, null);
    }

    /** As {@link #of(Connection, String, List, boolean)}, reading the session-zoned columns of {@code session}. */
    static UtcRows of(Connection c, String table, List<String> timeColumns, boolean tenantFiltered, Session session) {
        if (session != null && (session.columns().isEmpty() || !timeColumns.containsAll(session.columns()))) {
            throw new IllegalArgumentException("session-zoned columns " + session.columns() + " are not read columns");
        }
        try {
            DatabaseMetaData md = c.getMetaData();
            Map<String, String> cols = new LinkedHashMap<>();
            Map<Integer, String> pk = new TreeMap<>();
            for (String name : List.of(table, table.toLowerCase(Locale.ROOT))) {
                try (ResultSet rs = md.getColumns(c.getCatalog(), c.getSchema(), name, null)) {
                    while (rs.next()) {
                        cols.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT), rs.getString("TYPE_NAME"));
                    }
                }
                try (ResultSet rs = md.getPrimaryKeys(c.getCatalog(), c.getSchema(), name)) {
                    while (rs.next()) {
                        pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
                    }
                }
                if (!cols.isEmpty()) {
                    break;
                }
            }
            if (cols.isEmpty()) {
                throw new IllegalArgumentException("table " + table + " does not exist in this schema");
            }
            if (pk.isEmpty()) {
                throw new IllegalArgumentException("table " + table + " has no primary key: rows cannot be keyed");
            }
            for (String col : timeColumns) {
                if (!cols.containsKey(col)) {
                    throw new IllegalArgumentException("column " + table + "." + col + " does not exist");
                }
            }
            if (tenantFiltered && !cols.containsKey("TENANT_ID")) {
                throw new IllegalArgumentException("table " + table + " has no tenant_id column to filter on");
            }
            return new UtcRows(table, new ArrayList<>(pk.values()), timeColumns, tenantFiltered ? "TENANT_ID" : null,
                    session);
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException("metadata of " + table, e);
        }
    }

    /** Up to {@code limit} rows after {@code after} (null: from the start), in primary-key order. */
    List<Row> batch(Connection c, String tenantId, Key after, int limit) {
        Set<String> select = new LinkedHashSet<>(primaryKey);
        select.addAll(timeColumns);
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", select)).append(" FROM ").append(table)
                .append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (tenantColumn != null) {
            sql.append(" AND ").append(tenantColumn).append(" = ?");
            params.add(tenantId);
        }
        if (after != null) {
            Object[] k = after.bindValues();
            sql.append(" AND (");
            for (int i = 0; i < primaryKey.size(); i++) {
                sql.append(i == 0 ? "(" : " OR (");
                for (int j = 0; j < i; j++) {
                    sql.append(primaryKey.get(j)).append(" = ? AND ");
                    params.add(k[j]);
                }
                sql.append(primaryKey.get(i)).append(" > ?)");
                params.add(k[i]);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY ").append(String.join(", ", primaryKey));
        return read(c, sql.toString(), limit, params.toArray());
    }

    /** The row with {@code key}, if it still exists. */
    Optional<Row> find(Connection c, Key key) {
        Set<String> select = new LinkedHashSet<>(primaryKey);
        select.addAll(timeColumns);
        String sql = "SELECT " + String.join(", ", select) + " FROM " + table + " WHERE " + keyPredicate();
        List<Row> rows = read(c, sql, 2, key.bindValues());
        if (rows.size() > 1) {
            throw new IllegalStateException("primary key matched two rows in " + table);
        }
        return rows.stream().findFirst();
    }

    /**
     * Sets {@code column} to {@code to} on the row with {@code key} only while it still holds {@code from}.
     *
     * @return false when the row changed or vanished (a writer was active)
     */
    boolean replace(Connection c, Key key, String column, Instant from, Instant to) {
        Object[] k = key.bindValues();
        Object[] params = new Object[k.length + 2];
        params[0] = to;
        System.arraycopy(k, 0, params, 1, k.length);
        params[params.length - 1] = from;
        return inZone(c, SessionZones.UTC, () -> Jdbc.update(c, "UPDATE " + table + " SET " + column + " = ? WHERE "
                + keyPredicate() + " AND " + column + " = ?", params) == 1);
    }

    /**
     * Decision 240 rollback of a session-zoned cell: while it still stores {@code storedNow} (checked under a row lock
     * in {@link SessionZones#UTC}), writes {@code wallClock} under the declared session zone, so the server stores the
     * original instant again, and reads it back. The apply never converts a wall clock in a DST overlap of the session
     * zone, so the write is unambiguous.
     *
     * @return false when the cell no longer stores {@code storedNow}
     */
    boolean restoreWallClock(Connection c, Key key, String column, Instant storedNow, Instant wallClock) {
        if (session == null || !session.columns().contains(column)) {
            throw new IllegalStateException(table + "." + column + " is not a session-zoned column of this run");
        }
        Object[] k = key.bindValues();
        String where = " WHERE " + keyPredicate();
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, SessionZones.UTC);
            Optional<Instant> now = Jdbc.one(c, "SELECT " + column + " FROM " + table + where + " FOR UPDATE",
                    rs -> Jdbc.instant(rs, column), k);
            if (now.isEmpty() || !storedNow.equals(now.get())) {
                return false;
            }
            session.zones().set(c, session.zone());
            Object[] params = new Object[k.length + 1];
            params[0] = wallClock;
            System.arraycopy(k, 0, params, 1, k.length);
            if (Jdbc.update(c, "UPDATE " + table + " SET " + column + " = ?" + where, params) != 1) {
                return false;
            }
            Instant back = Jdbc.one(c, "SELECT " + column + " FROM " + table + where, rs -> Jdbc.instant(rs, column), k)
                    .orElseThrow();
            if (!wallClock.equals(back)) {
                throw new IllegalStateException(table + "." + column + " " + key.json() + " reads back " + back
                        + " under session zone " + session.zone() + ", not " + wallClock + ": rollback stopped");
            }
            return true;
        } finally {
            session.zones().set(c, saved);
        }
    }

    /** Runs {@code body} with the session zone {@code zone} when this table has a session, then restores it. */
    private <T> T inZone(Connection c, String zone, java.util.function.Supplier<T> body) {
        if (session == null) {
            return body.get();
        }
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, zone);
            return body.get();
        } finally {
            session.zones().set(c, saved);
        }
    }

    private String keyPredicate() {
        return String.join(" AND ", primaryKey.stream().map(p -> p + " = ?").toList());
    }

    private List<Row> read(Connection c, String sql, int limit, Object... params) {
        if (session == null) {
            return readOnce(c, sql, limit, params);
        }
        String saved = session.zones().current(c);
        try {
            session.zones().set(c, SessionZones.UTC);
            List<Row> stored = readOnce(c, sql, limit, params);
            session.zones().set(c, session.zone());
            List<Row> wall = readOnce(c, sql, limit, params);
            if (wall.size() != stored.size()) {
                throw new IllegalStateException(table + " changed between its two reads: stop its writers");
            }
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < stored.size(); i++) {
                Row s = stored.get(i);
                Row w = wall.get(i);
                if (!s.key().equals(w.key())) {
                    throw new IllegalStateException(table + " changed between its two reads: stop its writers");
                }
                Map<String, Instant> values = new LinkedHashMap<>(s.stored());
                for (String col : session.columns()) {
                    values.put(col, w.stored().get(col));
                }
                out.add(new Row(s.key(), java.util.Collections.unmodifiableMap(values), s.stored()));
            }
            return out;
        } finally {
            session.zones().set(c, saved);
        }
    }

    private List<Row> readOnce(Connection c, String sql, int limit, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(limit);
            ps.setFetchSize(Math.min(limit, 1000));
            Jdbc.bind(ps, sql, 1, params);
            List<Row> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    List<String> parts = new ArrayList<>();
                    for (String p : primaryKey) {
                        parts.add(keyPart(rs, md, p));
                    }
                    Map<String, Instant> values = new LinkedHashMap<>();
                    for (String col : timeColumns) {
                        values.put(col, Jdbc.instant(rs, col));
                    }
                    out.add(new Row(new Key(parts), values, values));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql, e);
        }
    }

    private static String keyPart(ResultSet rs, ResultSetMetaData md, String column) throws SQLException {
        int index = rs.findColumn(column);
        int type = md.getColumnType(index);
        switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL -> {
                BigDecimal v = rs.getBigDecimal(index);
                return "N:" + v.toPlainString();
            }
            // vendor codes: Oracle TIMESTAMP WITH (LOCAL) TIME ZONE -101 / -102, SQL Server DATETIMEOFFSET -155
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, -101, -102, -155 -> {
                return "T:" + Jdbc.instant(rs, column);
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> throw new IllegalArgumentException(
                    "binary primary-key column " + column + " is not supported by the conversion tool");
            default -> {
                return "S:" + rs.getString(index);
            }
        }
    }
}
