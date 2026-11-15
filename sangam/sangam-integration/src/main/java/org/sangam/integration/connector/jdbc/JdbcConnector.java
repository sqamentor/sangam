// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.sangam.core.engine.LockScope;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;

/**
 * M-105 JDBC connector (R71) through the connector SPI: runs one of the connector's <b>configured</b> statements against
 * an external database, with the input's {@code params} bound by name. The process never supplies SQL, only the
 * statement name and values, and values only ever reach the driver as bound parameters.
 *
 * <ul>
 *   <li><b>Statements.</b> {@link NamedStatement}s by name ({@code :name} parameters). {@code :operationId} and
 *       {@code :tenantId} are bound by the connector from the request and cannot come from {@code params}, so an
 *       idempotent design (an insert keyed by the operationId, an upsert) is possible.</li>
 *   <li><b>One transaction per call.</b> Auto-commit off, the statement, then commit. A failure before the commit is
 *       rolled back, so it had no effect: a data error (SQLState class 22 or 23, a constraint) is a business failure,
 *       a syntax or access error (class 42) is {@code INVALID_STATEMENT}, anything else (timeout, deadlock, lost
 *       connection) a no-effect transient failure. A failure of the commit itself is an unknown outcome. Statements must
 *       therefore be transactional (no DDL that commits implicitly, no autonomous transactions).</li>
 *   <li><b>Capability class.</b> Declared per connector (default (c)). With a {@code lookupStatement} (a query with
 *       {@code :operationId}) the connector can settle a lost commit answer: one row means the effect happened.</li>
 *   <li><b>Bounds.</b> A query returns at most {@code maxRows} rows ({@code truncated=true} beyond); every statement
 *       has the query timeout. VI.1 rule 5 (owner decision 86): no call while the thread's engine command holds an L2
 *       root lock ({@link LockScope}), exactly as for HTTP.</li>
 *   <li><b>Egress.</b> The database endpoint is the operator's data-source configuration (never process input), so it
 *       is fixed when the connector is built; the URL-based {@code EgressPolicy} applies to the HTTP-family
 *       transports.</li>
 * </ul>
 */
public final class JdbcConnector implements SangamConnector {

    /** Parameter names the connector binds itself. */
    public static final Set<String> RESERVED = Set.of("operationId", "tenantId");

    /** Static configuration of one database connector. */
    public record Config(String id, Map<String, NamedStatement> statements, NamedStatement lookupStatement,
                         CapabilityClass capabilityClass, int maxRows, Duration queryTimeout, ConnectorProfile profile) {
        public Config {
            Objects.requireNonNull(id, "id");
            statements = statements == null ? Map.of() : Map.copyOf(statements);
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("JDBC connector " + id + " has no statements");
            }
            capabilityClass = capabilityClass == null ? CapabilityClass.C_NEITHER : capabilityClass;
            if (lookupStatement != null && (lookupStatement.kind() != NamedStatement.Kind.QUERY
                    || !lookupStatement.parameters().contains("operationId"))) {
                throw new IllegalArgumentException("JDBC connector " + id + ": the lookup statement must be a query with"
                        + " :operationId");
            }
            if (capabilityClass == CapabilityClass.B_LOOKUP_ONLY && lookupStatement == null) {
                throw new IllegalArgumentException("JDBC connector " + id + ": class (b) needs a lookup statement");
            }
            maxRows = maxRows <= 0 ? 1000 : maxRows;
            queryTimeout = queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()
                    ? Duration.ofSeconds(30) : queryTimeout;
        }
    }

    private final Config config;
    private final DataSource dataSource;
    private final ConnectorDescriptor descriptor;

    public JdbcConnector(Config config, DataSource dataSource) {
        this.config = Objects.requireNonNull(config, "config");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.descriptor = new ConnectorDescriptor(config.id(), "jdbc", config.capabilityClass(),
                List.copyOf(config.statements().keySet()), config.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Owner decision 320: read-only when the statement this request resolves to (its {@code statement}, else the
     * operation, exactly as {@link #invoke} resolves it) is configured as a {@link NamedStatement.Kind#QUERY} and its
     * SQL is a plain {@code SELECT} ({@link NamedStatement#isSelect()}). An unknown statement is not read-only.
     */
    @Override
    public boolean readOnly(ConnectorRequest r) {
        NamedStatement st = config.statements().get(r.str("statement", r.operation()));
        return st != null && st.isSelect();
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        LockScope.assertNoLockHeld("JDBC " + config.id());
        String name = r.str("statement", r.operation());
        NamedStatement st = config.statements().get(name);
        if (st == null) {
            return ConnectorResult.BusinessFailure.of("UNKNOWN_STATEMENT", "no statement '" + name + "' (configured: "
                    + config.statements().keySet() + ")");
        }
        List<Object> values;
        try {
            values = values(st, r);
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_REQUEST", e.getMessage());
        }
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            return ConnectorResult.TransientFailure.noEffect("no database connection, nothing sent: " + describe(e));
        }
        try (c) {
            c.setAutoCommit(false);
            Map<String, Object> out;
            try (PreparedStatement ps = c.prepareStatement(st.positionalSql())) {
                ps.setQueryTimeout(timeoutSeconds(r));
                bind(ps, values);
                out = execute(st, ps);
            } catch (SQLException e) {
                rollbackQuietly(c);
                return beforeCommit(e);
            }
            try {
                c.commit();
            } catch (SQLException e) {
                return ConnectorResult.OutcomeUnknown.of("commit failed, the effect may have happened: " + describe(e));
            }
            return ConnectorResult.Success.of(out, null);
        } catch (SQLException e) {
            // setAutoCommit or close: before the commit nothing is kept; after it, close failures do not undo it
            return ConnectorResult.TransientFailure.noEffect("database session failed before the statement: " + describe(e));
        }
    }

    /** One row of the lookup statement for this operationId means the effect happened. */
    @Override
    public LookupResult lookup(ConnectorRequest r) {
        NamedStatement st = config.lookupStatement();
        if (st == null) {
            return new LookupResult.NotSupported();
        }
        LockScope.assertNoLockHeld("JDBC lookup " + config.id());
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(st.positionalSql())) {
            ps.setQueryTimeout(timeoutSeconds(r));
            bind(ps, values(st, r));
            Map<String, Object> out = execute(st, ps);
            Object rows = out.get("rows");
            return rows instanceof List<?> l && !l.isEmpty() ? LookupResult.Found.success(out, null)
                    : new LookupResult.NotFound();
        } catch (SQLException | IllegalArgumentException e) {
            return new LookupResult.Unavailable("lookup failed: " + e.getMessage());
        }
    }

    private int timeoutSeconds(ConnectorRequest r) {
        long ms = r.remainingMillis(config.queryTimeout().toMillis());
        long cap = Math.min(ms, config.queryTimeout().toMillis());
        return (int) Math.max(1, (cap + 999) / 1000);
    }

    /** The values in parameter order; reserved names from the request, the rest from {@code params}. */
    static List<Object> values(NamedStatement st, ConnectorRequest r) {
        Map<String, Object> params = r.map("params");
        for (String reserved : RESERVED) {
            if (params.containsKey(reserved)) {
                throw new IllegalArgumentException("parameter '" + reserved + "' is bound by the connector");
            }
        }
        List<Object> out = new ArrayList<>();
        for (String p : st.parameters()) {
            if (p.equals("operationId")) {
                out.add(r.operationId().toString());
            } else if (p.equals("tenantId")) {
                out.add(r.tenantId());
            } else if (!params.containsKey(p)) {
                throw new IllegalArgumentException("statement '" + st.name() + "' needs parameter '" + p + "'");
            } else {
                Object v = params.get(p);
                if (v instanceof Map<?, ?> || v instanceof List<?>) {
                    throw new IllegalArgumentException("parameter '" + p + "' must be a scalar value");
                }
                out.add(v);
            }
        }
        return out;
    }

    /** Binds JSON-shaped values: text, whole numbers as long, other numbers as exact decimals, booleans, null. */
    static void bind(PreparedStatement ps, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            int idx = i + 1;
            switch (v) {
                case null -> ps.setNull(idx, Types.NULL);
                case String s -> ps.setString(idx, s);
                case Boolean b -> ps.setBoolean(idx, b);
                case Integer n -> ps.setLong(idx, n);
                case Long n -> ps.setLong(idx, n);
                case Short n -> ps.setLong(idx, n);
                case BigInteger n -> ps.setBigDecimal(idx, new BigDecimal(n));
                case BigDecimal n -> ps.setBigDecimal(idx, n);
                // an exact decimal from the number's own text, never a binary floating-point value
                case Number n -> ps.setBigDecimal(idx, new BigDecimal(n.toString()));
                default -> ps.setString(idx, v.toString());
            }
        }
    }

    private Map<String, Object> execute(NamedStatement st, PreparedStatement ps) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", st.name());
        if (st.kind() == NamedStatement.Kind.UPDATE) {
            out.put("updateCount", ps.executeUpdate());
            return out;
        }
        ps.setMaxRows(config.maxRows() + 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                if (rows.size() == config.maxRows()) {
                    truncated = true;
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int col = 1; col <= md.getColumnCount(); col++) {
                    row.put(md.getColumnLabel(col), value(rs.getObject(col)));
                }
                rows.add(row);
            }
        }
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
        return out;
    }

    /** A column value in a JSON-safe form (timestamps as ISO-8601 instants, binary as base64, LOBs read). */
    static Object value(Object v) throws SQLException {
        return switch (v) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Integer n -> n;
            case Long n -> n;
            case Short n -> n.intValue();
            case BigDecimal n -> n;
            case BigInteger n -> n;
            case Timestamp t -> t.toInstant().toString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case java.sql.Time t -> t.toLocalTime().toString();
            case java.time.temporal.TemporalAccessor t -> t.toString();
            case byte[] b -> Base64.getEncoder().encodeToString(b);
            case Clob clob -> clob.getSubString(1, (int) Math.min(Integer.MAX_VALUE, clob.length()));
            case Blob blob -> Base64.getEncoder().encodeToString(blob.getBytes(1, (int) Math.min(Integer.MAX_VALUE,
                    blob.length())));
            case Number n -> new BigDecimal(n.toString());
            default -> v.toString();
        };
    }

    /** A statement failure before the commit (the transaction was rolled back, so nothing was kept). */
    static ConnectorResult beforeCommit(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (e instanceof SQLIntegrityConstraintViolationException || state.startsWith("23")) {
            return ConnectorResult.BusinessFailure.of("CONSTRAINT_VIOLATION", describe(e));
        }
        if (state.startsWith("22")) {
            return ConnectorResult.BusinessFailure.of("DATA_ERROR", describe(e));
        }
        if (e instanceof SQLSyntaxErrorException || state.startsWith("42")) {
            return ConnectorResult.BusinessFailure.of("INVALID_STATEMENT", describe(e));
        }
        // timeout, deadlock (40xxx), lost connection (08xxx) or any other failure: not committed, so no effect
        String kind = e instanceof SQLTransientException || state.startsWith("40") || state.startsWith("08")
                ? "transient" : "other";
        return ConnectorResult.TransientFailure.noEffect("rolled back (" + kind + "): " + describe(e));
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the transaction was never committed; closing the connection discards it
        }
    }

    static String describe(SQLException e) {
        return e.getClass().getSimpleName() + (e.getSQLState() == null ? "" : " [" + e.getSQLState() + "]") + ": "
                + e.getMessage();
    }
}
