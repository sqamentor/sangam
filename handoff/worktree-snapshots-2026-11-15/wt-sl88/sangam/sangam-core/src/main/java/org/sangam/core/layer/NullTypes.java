package org.sangam.core.layer;

import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JDBC type of a SQL NULL bind parameter, per database (SL-88). A Java {@code null} carries no type, so the
 * binder must find one the database accepts for the parameter's target:
 * <ul>
 *   <li><b>PostgreSQL, MySQL/MariaDB, H2:</b> {@link Types#NULL}. The driver sends an untyped (unspecified) NULL and
 *       the server types it from the context. A VARCHAR null is not an option: PostgreSQL rejects it into a numeric
 *       or timestamp column.</li>
 *   <li><b>Oracle:</b> {@link Types#VARCHAR}. ojdbc rejects {@code setNull(i, Types.NULL)} (ORA-17004) and converts a
 *       VARCHAR null into any column type.</li>
 *   <li><b>SQL Server:</b> the parameter's declared type from {@link PreparedStatement#getParameterMetaData()}.
 *       mssql-jdbc sends {@code Types.NULL} as {@code varbinary(8000)}, which SQL Server will not convert implicitly
 *       to {@code datetimeoffset}, {@code datetime2}, {@code date}, {@code time}, {@code float} or {@code real} (msg
 *       257, "Implicit conversion from data type varbinary to datetimeoffset is not allowed"); an NVARCHAR null is
 *       not convertible to {@code varbinary}. No single type fits both {@code {ts}} (DATETIMEOFFSET) and
 *       {@code {blob}}/{@code {bytes32}} (VARBINARY), so the type comes from the server
 *       ({@code sp_describe_undeclared_parameters}). One round trip per statement text: the result is cached by
 *       connection URL and SQL text when the text is known (product statements; the schema is fixed while the
 *       process runs). A statement the server cannot describe (an undeducible parameter, e.g. {@code ? IS NULL})
 *       has no column-typed target for that parameter, and SQL Server converts an NVARCHAR null implicitly to every
 *       non-binary type, so NVARCHAR is used for all its nulls and the statement is logged once.</li>
 * </ul>
 */
final class NullTypes {

    private static final Logger LOG = LoggerFactory.getLogger(NullTypes.class);

    /** Distinct product statements are a few hundred; the cap only guards against unbounded dynamic SQL. */
    private static final int CACHE_LIMIT = 4096;

    /** Marker for a statement SQL Server could not describe. */
    private static final int[] UNDESCRIBED = new int[0];

    private static final Map<String, int[]> SQLSERVER_CACHE = new ConcurrentHashMap<>();

    private static final NullTypes UNTYPED = new NullTypes(null, Types.NULL);
    private static final NullTypes VARCHAR = new NullTypes(null, Types.VARCHAR);

    /** Per-index types (SQL Server), or null to use {@link #fixed} for every index. */
    private final int[] described;
    private final int fixed;

    private NullTypes(int[] described, int fixed) {
        this.described = described;
        this.fixed = fixed;
    }

    /** The resolver for {@code ps}; {@code sql} is the statement text or null when unknown (no caching). */
    static NullTypes of(PreparedStatement ps, String sql) throws SQLException {
        String product = ps.getConnection().getMetaData().getDatabaseProductName();
        String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (p.contains("oracle")) {
            return VARCHAR;
        }
        if (p.contains("microsoft sql server")) {
            return new NullTypes(sqlServerTypes(ps, sql), Types.NVARCHAR);
        }
        return UNTYPED;
    }

    /** The JDBC type for a NULL at the 1-based parameter {@code index}. */
    int type(int index) {
        if (described == null || described == UNDESCRIBED) {
            return fixed;
        }
        if (index < 1 || index >= described.length) {
            throw new IllegalStateException("parameter " + index + " outside the statement's " + (described.length - 1)
                    + " parameters");
        }
        return described[index];
    }

    private static int[] sqlServerTypes(PreparedStatement ps, String sql) throws SQLException {
        if (sql == null) {
            return describe(ps, null);
        }
        String key = ps.getConnection().getMetaData().getURL() + '\n' + sql;
        int[] cached = SQLSERVER_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int[] types = describe(ps, sql);
        if (SQLSERVER_CACHE.size() < CACHE_LIMIT) {
            SQLSERVER_CACHE.putIfAbsent(key, types);
        }
        return types;
    }

    private static int[] describe(PreparedStatement ps, String sql) {
        try {
            ParameterMetaData md = ps.getParameterMetaData();
            int n = md.getParameterCount();
            int[] types = new int[n + 1];
            for (int k = 1; k <= n; k++) {
                types[k] = md.getParameterType(k);
            }
            return types;
        } catch (SQLException e) {
            LOG.warn("SQL Server cannot describe the parameters of [{}] ({}); its NULLs are bound as NVARCHAR",
                    sql == null ? "statement" : sql, e.getMessage());
            return UNDESCRIBED;
        }
    }

    /** Test hook: forget the described statements. */
    static void clearCache() {
        SQLSERVER_CACHE.clear();
    }
}
