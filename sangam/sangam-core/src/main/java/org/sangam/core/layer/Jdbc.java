// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Small plain-JDBC helpers. Every product component writes through a caller-supplied connection and never commits it. */
public final class Jdbc {

    private Jdbc() {
    }

    /** Unchecked wrapper so components can run inside {@code Function<Connection,T>} (EngineAdapter.inEngineTransaction). */
    public static final class LayerSqlException extends RuntimeException {
        public LayerSqlException(String message, SQLException cause) {
            super(message + ": " + cause.getMessage(), cause);
        }

        public SQLException sql() {
            return (SQLException) getCause();
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public static int update(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, sql, 1, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LayerSqlException(sql, e);
        }
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int limit = Dialect.markedLimit(sql);
            if (limit > 0) {
                // Oracle SKIP LOCKED locks rows as they are fetched: never prefetch or read past the claim limit
                ps.setMaxRows(limit);
                ps.setFetchSize(limit);
            }
            bind(ps, sql, 1, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new LayerSqlException(sql, e);
        }
    }

    /**
     * Executes one statement for each parameter row as a single JDBC batch (one round trip on PostgreSQL); a single
     * row is a plain update. Every row must affect exactly one row or the batch fails.
     */
    public static void batch(Connection c, String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        if (rows.size() == 1) {
            update(c, sql, rows.get(0));
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] r : rows) {
                bind(ps, sql, 1, r);
                ps.addBatch();
            }
            for (int n : ps.executeBatch()) {
                if (n != 1 && n != java.sql.Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException("batch row affected " + n + " rows: " + sql);
                }
            }
        } catch (SQLException e) {
            throw new LayerSqlException(sql, e);
        }
    }

    /**
     * At most one row, mapped. An existing row whose mapped value is null is an error, never an empty result: an
     * empty {@code Optional} means "no row", and a null mapping (for example a NULL column read with
     * {@code rs.getString}) is a data gap the caller must see. It fails as an {@link IllegalStateException} naming the
     * statement (it used to surface as a bare {@code NullPointerException} from {@code Optional.of(null)}).
     */
    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(c, sql, mapper, params);
        if (rows.size() > 1) {
            throw new IllegalStateException("expected at most one row: " + sql);
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        T value = rows.get(0);
        if (value == null) {
            throw new IllegalStateException("row exists but mapped value is NULL: " + sql);
        }
        return Optional.of(value);
    }

    public static long insertReturningId(Connection c, Dialect d, String sql, String idColumn, Object... params) {
        String col = d == Dialect.POSTGRESQL ? idColumn.toLowerCase() : idColumn.toUpperCase();
        try (PreparedStatement ps = c.prepareStatement(sql, new String[]{col})) {
            bind(ps, sql, 1, params);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no generated key: " + sql);
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LayerSqlException(sql, e);
        }
    }

    /**
     * Insert that tolerates an existing row with the same key (X.2.2.8).
     * {@code insertSql} must be a plain {@code INSERT INTO t(...) VALUES (...)}; on PostgreSQL
     * {@code ON CONFLICT DO NOTHING} is appended so the surrounding transaction is not aborted.
     *
     * @return true if a row was inserted
     */
    public static boolean insertIfAbsent(Connection c, Dialect d, String insertSql, Object... params) {
        if (d.supportsOnConflictDoNothing()) {
            return update(c, insertSql + " ON CONFLICT DO NOTHING", params) == 1;
        }
        try (PreparedStatement ps = c.prepareStatement(insertSql)) {
            bind(ps, insertSql, 1, params);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (d.isUniqueViolation(e)) {
                return false;
            }
            throw new LayerSqlException(insertSql, e);
        }
    }

    /**
     * Binds {@code params} from index {@code start}. Prefer {@link #bind(PreparedStatement, String, int, Object...)}
     * when the statement text is at hand: on SQL Server a null's type is then described once per statement text
     * instead of once per execution (see {@link NullTypes}).
     */
    public static void bind(PreparedStatement ps, int start, Object... params) throws SQLException {
        bind(ps, null, start, params);
    }

    /**
     * Binds {@code params} from index {@code start} of the statement prepared from {@code sql} ({@code null} when the
     * text is unknown, or when the target schema may change under a running process, as for a pipeline's external
     * database). Every null is bound with the JDBC type its database needs ({@link NullTypes}).
     */
    public static void bind(PreparedStatement ps, String sql, int start, Object... params) throws SQLException {
        int i = start;
        NullTypes nulls = null;   // resolved on the first null only
        for (Object p : params) {
            if (p == null) {
                if (nulls == null) {
                    nulls = NullTypes.of(ps, sql);
                }
                ps.setNull(i, nulls.type(i));
            } else if (p instanceof Instant t) {
                ps.setTimestamp(i, Timestamp.from(t));
            } else if (p instanceof byte[] b) {
                ps.setBytes(i, b);
            } else if (p instanceof Boolean b) {
                ps.setInt(i, b ? 1 : 0);
            } else if (p instanceof java.util.UUID u) {
                ps.setString(i, u.toString());
            } else if (p instanceof Enum<?> en) {
                ps.setString(i, en.name());
            } else {
                ps.setObject(i, p);
            }
            i++;
        }
    }

    /**
     * Binds SQL NULL at {@code index} with the JDBC type its database needs ({@link NullTypes}); for statements that
     * bind values themselves instead of through {@link #bind}. {@code sql} as in
     * {@link #bind(PreparedStatement, String, int, Object...)}.
     */
    public static void setNull(PreparedStatement ps, String sql, int index) throws SQLException {
        ps.setNull(index, NullTypes.of(ps, sql).type(index));
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    /** Current database time (A-1). */
    public static Instant dbNow(Connection c, Dialect d) {
        return one(c, "SELECT " + d.now() + " AS n" + d.fromDual(), rs -> instant(rs, "n")).orElseThrow();
    }
}
