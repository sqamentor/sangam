// SPDX-License-Identifier: Apache-2.0
package org.sangam.registry.support;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.SqlPredicate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** JDBC helpers shared by the registry services (on top of the core {@link Jdbc}). */
public final class Db {

    private Db() {
    }

    /** A new time-ordered id (UUIDv7). */
    public static String newId() {
        return Hashes.uuidV7().toString();
    }

    /** Database clock (A-1), truncated to microseconds so every dialect stores it exactly. */
    public static Instant now(Connection c, Dialect d) {
        return Jdbc.dbNow(c, d).truncatedTo(ChronoUnit.MICROS);
    }

    public static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : v.stripTrailingZeros();
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static boolean flag(ResultSet rs, String col) throws SQLException {
        return rs.getInt(col) == 1;
    }

    /**
     * A query with a visibility predicate and paging. {@code baseSql} must select from the aliased table and end
     * with its WHERE clause body (no ORDER BY); the predicate is AND-ed in.
     */
    public static <T> List<T> page(Connection c, Dialect d, String select, String where, List<Object> params,
                                   SqlPredicate visible, String orderBy, int page, int size, Jdbc.RowMapper<T> mapper) {
        int s = Math.max(1, Math.min(size <= 0 ? 50 : size, 1000));
        int offset = Math.max(0, page) * s;
        StringBuilder sql = new StringBuilder(select).append(" WHERE (").append(where).append(")");
        if (visible != null) {
            sql.append(" AND (").append(visible.sql()).append(")");
        }
        sql.append(" ORDER BY ").append(orderBy);
        sql.append(switch (d) {
            case POSTGRESQL, MYSQL -> " LIMIT " + s + " OFFSET " + offset;
            case ORACLE, SQLSERVER -> " OFFSET " + offset + " ROWS FETCH NEXT " + s + " ROWS ONLY";
        });
        List<Object> all = new ArrayList<>(params);
        if (visible != null) {
            all.addAll(visible.params());
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            Jdbc.bind(ps, 1, all.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new Jdbc.LayerSqlException(sql.toString(), e);
        }
    }

    /** Row lock by key, per dialect (L4 lock on a registry row). */
    public static String lockById(Dialect d, String columns, String table, String where) {
        return d.lockingSelect(columns, table, where);
    }
}
