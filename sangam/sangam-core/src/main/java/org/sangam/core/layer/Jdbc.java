package org.sangam.core.layer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
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
            bind(ps, 1, params);
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
            bind(ps, 1, params);
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
                bind(ps, 1, r);
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

    public static <T> Optional<T> one(Connection c, String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(c, sql, mapper, params);
        if (rows.size() > 1) {
            throw new IllegalStateException("expected at most one row: " + sql);
        }
        return rows.stream().findFirst();
    }

    public static long insertReturningId(Connection c, Dialect d, String sql, String idColumn, Object... params) {
        String col = d == Dialect.POSTGRESQL ? idColumn.toLowerCase() : idColumn.toUpperCase();
        try (PreparedStatement ps = c.prepareStatement(sql, new String[]{col})) {
            bind(ps, 1, params);
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
            bind(ps, 1, params);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (d.isUniqueViolation(e)) {
                return false;
            }
            throw new LayerSqlException(insertSql, e);
        }
    }

    public static void bind(PreparedStatement ps, int start, Object... params) throws SQLException {
        int i = start;
        int nullType = Integer.MIN_VALUE;   // resolved on the first null only
        for (Object p : params) {
            if (p == null) {
                if (nullType == Integer.MIN_VALUE) {
                    nullType = nullSqlType(ps);
                }
                ps.setNull(i, nullType);
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
     * Oracle's driver rejects {@code setNull(i, Types.NULL)} (ORA-17004) but converts a VARCHAR null into any column
     * type; PostgreSQL is the reverse (a VARCHAR null into a numeric column fails), so the untyped NULL stays the
     * default for every other database.
     */
    private static int nullSqlType(PreparedStatement ps) throws SQLException {
        String product = ps.getConnection().getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("oracle") ? Types.VARCHAR : Types.NULL;
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
