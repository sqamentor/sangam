// SPDX-License-Identifier: Apache-2.0
package org.sangam.programme.progress;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.money.Money;
import org.sangam.programme.ProgrammeException;
import org.sangam.programme.ProgrammeSchema;
import org.sangam.programme.ports.ProgrammePorts;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SG-20: programme financials are BIGINT minor units; roll-ups add exact integers and never mix currencies. */
class FinancialMoneyTest {

    static Connection db(String name) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        Connection c = ds.getConnection();
        ProgrammeSchema.create(c, Dialect.POSTGRESQL);
        return c;
    }

    static final ProgrammePorts.HierarchyPort TREE = new ProgrammePorts.HierarchyPort() {
        @Override
        public List<String> children(Connection c, String t, String node) {
            return "STATE".equals(node) ? List.of("D1", "D2", "D3") : List.of();
        }

        @Override
        public Optional<String> parent(Connection c, String t, String node) {
            return node.startsWith("D") ? Optional.of("STATE") : Optional.empty();
        }

        @Override
        public String level(Connection c, String t, String node) {
            return null;
        }

        @Override
        public List<String> nodesAtLevel(Connection c, String t, String root, String level) {
            return List.of();
        }
    };

    static void row(Connection c, String unit, long allocated, long expended, String ccy) {
        Jdbc.update(c, "INSERT INTO PG_FINANCIAL(tenant_id, programme_id, period_id, org_unit, head, allocated, released,"
                + " committed_amt, expended, currency, source, updated_by, updated_at) VALUES ('T','P','Q1',?,'TOTAL',?,NULL,NULL,?,?,"
                + "'MANUAL','u',CURRENT_TIMESTAMP)", unit, allocated, expended, ccy);
    }

    static ProgressService service() {
        return new ProgressService(Dialect.POSTGRESQL, null, null, null, null, null, null, TREE, null);
    }

    @Test
    void columnsAreBigintAndRollupIsExact() throws Exception {
        try (Connection c = db("pgfin1")) {
            try (ResultSet rs = c.createStatement().executeQuery("SELECT allocated FROM PG_FINANCIAL WHERE 1=0")) {
                assertEquals(java.sql.Types.BIGINT, rs.getMetaData().getColumnType(1));
            }
            // 3 x 33,333.33 + one paisa = 1,00,000.00 exactly; no decimal drift in the sum
            row(c, "D1", 3_333_333, 1_000_001, "INR");
            row(c, "D2", 3_333_333, 999_999, "INR");
            row(c, "D3", 3_333_334, 1, "INR");
            assertEquals(1, service().rollupFinancial(c, "T", "P", "Q1", "STATE"));
            ProgressService.Financial f = service().financial(c, "T", "P", "Q1", "STATE").get(0);
            assertEquals(Money.parse("100000.00", "INR"), f.allocated());
            assertEquals(Money.parse("20000.01", "INR"), f.expended());
            assertEquals(null, f.released(), "an all-NULL bucket stays NULL, it is not summed as zero");
            assertEquals("ROLLUP", f.source());
        }
    }

    /** The old roll-up summed every child and labelled the sum with MAX(currency). */
    @Test
    void mixedCurrenciesAreNeverAdded() throws Exception {
        try (Connection c = db("pgfin2")) {
            row(c, "D1", 100, 0, "INR");
            row(c, "D2", 100, 0, "USD");
            assertThrows(ProgrammeException.class, () -> service().rollupFinancial(c, "T", "P", "Q1", "STATE"));
        }
    }

    @Test
    void budgetBoundaryRejectsSubPaisaAndMissingCurrency() {
        assertEquals(10_000_000L, org.sangam.programme.programme.ProgrammeServiceAccess.minor(new BigDecimal("100000"), "INR"));
        assertThrows(ProgrammeException.class,
                () -> org.sangam.programme.programme.ProgrammeServiceAccess.minor(new BigDecimal("0.001"), "INR"));
        assertThrows(ProgrammeException.class,
                () -> org.sangam.programme.programme.ProgrammeServiceAccess.minor(new BigDecimal("1"), null));
        assertEquals(Map.of(), Map.of());
    }
}
