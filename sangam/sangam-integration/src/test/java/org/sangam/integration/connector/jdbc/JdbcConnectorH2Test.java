// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;

/**
 * M-105 JDBC connector on an in-memory H2 database: configured statements only, the operationId and tenant bound by the
 * connector (never by the caller), exact decimals, one transaction per call (a failed statement leaves nothing), SQL
 * states mapped onto business or no-effect failures, bounded result sets, and the lookup statement settling a lost
 * answer.
 */
class JdbcConnectorH2Test {

    JdbcDataSource ds;

    @BeforeEach
    void db() throws SQLException {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jdbc-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table payment_request (op_id varchar(36) primary key, tenant varchar(64) not null, "
                    + "ref varchar(64) not null unique, value_due decimal(18,2) not null)");
        }
    }

    JdbcConnector connector(int maxRows) {
        Map<String, NamedStatement> st = new LinkedHashMap<>();
        st.put("record", NamedStatement.parse("record", "insert into payment_request (op_id, tenant, ref, value_due) "
                + "values (:operationId, :tenantId, :ref, :due)", NamedStatement.Kind.UPDATE));
        st.put("list", NamedStatement.parse("list", "select ref, value_due from payment_request where tenant = :tenantId "
                + "order by ref", NamedStatement.Kind.QUERY));
        st.put("broken", NamedStatement.parse("broken", "select * from no_such_table", NamedStatement.Kind.QUERY));
        NamedStatement lookup = NamedStatement.parse("lookup", "select ref from payment_request where op_id = :operationId",
                NamedStatement.Kind.QUERY);
        return new JdbcConnector(new JdbcConnector.Config("ledger-db", st, lookup, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP,
                maxRows, Duration.ofSeconds(5), ConnectorProfile.LIVE), ds);
    }

    static ConnectorRequest req(UUID op, String tenant, String statement, Map<String, Object> params) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("statement", statement);
        in.put("params", params);
        return new ConnectorRequest(tenant, op, op.toString(), 1, null, null, "invoke", in, Instant.now().plusSeconds(10));
    }

    @Test
    void anUpdateBindsTheOperationIdAndTenantAndAQueryReadsExactDecimals() {
        JdbcConnector c = connector(100);
        UUID op = UUID.randomUUID();
        ConnectorResult r = c.invoke(req(op, "t1", "record", Map.of("ref", "R-1", "due", new BigDecimal("1234.50"))));
        assertEquals(1, assertInstanceOf(ConnectorResult.Success.class, r).output().get("updateCount"));

        ConnectorResult.Success q = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "t1", "list", Map.of())));
        List<?> rows = (List<?>) q.output().get("rows");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("R-1", row.get("REF"));
        assertEquals(0, new BigDecimal("1234.50").compareTo((BigDecimal) row.get("VALUE_DUE")));
        assertEquals(List.of(), ((List<?>) assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "t2", "list", Map.of()))).output().get("rows")), "another tenant's rows");
    }

    @Test
    void theLookupFindsTheEffectOfThisOperationOnly() {
        JdbcConnector c = connector(100);
        UUID op = UUID.randomUUID();
        ConnectorRequest record = req(op, "t1", "record", Map.of("ref", "R-2", "due", 10));
        assertInstanceOf(LookupResult.NotFound.class, c.lookup(record));
        c.invoke(record);
        LookupResult.Found f = assertInstanceOf(LookupResult.Found.class, c.lookup(record));
        assertTrue(f.succeeded());
        assertInstanceOf(LookupResult.NotFound.class, c.lookup(req(UUID.randomUUID(), "t1", "record", Map.of())));
    }

    @Test
    void sqlFailuresAreMappedAndRolledBack() throws SQLException {
        JdbcConnector c = connector(100);
        c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R-3", "due", 1)));
        ConnectorResult dup = c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R-3", "due", 2)));
        assertEquals("CONSTRAINT_VIOLATION", assertInstanceOf(ConnectorResult.BusinessFailure.class, dup).code());
        ConnectorResult bad = c.invoke(req(UUID.randomUUID(), "t1", "broken", Map.of()));
        assertEquals("INVALID_STATEMENT", assertInstanceOf(ConnectorResult.BusinessFailure.class, bad).code());
        ConnectorResult data = c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R-4", "due", "not a number")));
        assertEquals("DATA_ERROR", assertInstanceOf(ConnectorResult.BusinessFailure.class, data).code());
        try (Connection conn = ds.getConnection(); Statement s = conn.createStatement();
             var rs = s.executeQuery("select count(*) from payment_request")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "failed statements left nothing behind");
        }
    }

    @Test
    void callerInputIsCheckedBeforeTheDatabaseIsTouched() {
        JdbcConnector c = connector(100);
        assertEquals("UNKNOWN_STATEMENT", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "t1", "drop", Map.of()))).code());
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R", "due", 1,
                        "operationId", "forged")))).code(), "reserved parameters are bound by the connector");
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R")))).code(), "missing parameter");
        assertEquals("INVALID_REQUEST", assertInstanceOf(ConnectorResult.BusinessFailure.class,
                c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", List.of("R"), "due", 1)))).code());
    }

    @Test
    void resultSetsAreBoundedAndFlaggedWhenTruncated() {
        JdbcConnector c = connector(2);
        for (int i = 0; i < 3; i++) {
            c.invoke(req(UUID.randomUUID(), "t1", "record", Map.of("ref", "R-" + i, "due", i)));
        }
        ConnectorResult.Success q = assertInstanceOf(ConnectorResult.Success.class,
                c.invoke(req(UUID.randomUUID(), "t1", "list", Map.of())));
        assertEquals(2, q.output().get("rowCount"));
        assertEquals(true, q.output().get("truncated"));
    }

    @Test
    void anUnreachableDatabaseIsANoEffectFailure() {
        JdbcDataSource down = new JdbcDataSource();
        down.setURL("jdbc:h2:tcp://127.0.0.1:1/nothing-listens-here");
        JdbcConnector c = new JdbcConnector(new JdbcConnector.Config("x",
                Map.of("q", NamedStatement.parse("q", "select 1", NamedStatement.Kind.QUERY)), null, null, 0, null,
                ConnectorProfile.LIVE), down);
        ConnectorResult r = c.invoke(req(UUID.randomUUID(), "t1", "q", Map.of()));
        assertTrue(assertInstanceOf(ConnectorResult.TransientFailure.class, r).noEffect());
        assertInstanceOf(LookupResult.NotSupported.class, c.lookup(req(UUID.randomUUID(), "t1", "q", Map.of())));
    }

    @Test
    void configurationRulesForTheLookupStatement() {
        Map<String, NamedStatement> st = Map.of("q", NamedStatement.parse("q", "select 1", NamedStatement.Kind.QUERY));
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnector.Config("x", st, null,
                CapabilityClass.B_LOOKUP_ONLY, 0, null, ConnectorProfile.LIVE), "class (b) needs a lookup");
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnector.Config("x", st,
                NamedStatement.parse("l", "select 1 from t where ref = :ref", NamedStatement.Kind.QUERY),
                CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, 0, null, ConnectorProfile.LIVE), "lookup without :operationId");
        assertThrows(IllegalArgumentException.class, () -> new JdbcConnector.Config("x", Map.of(), null, null, 0, null,
                ConnectorProfile.LIVE));
    }
}
