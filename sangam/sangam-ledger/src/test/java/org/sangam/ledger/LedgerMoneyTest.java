// SPDX-License-Identifier: Apache-2.0
package org.sangam.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SG-20 / SG-01: the ledger holds integer minor units and its limit checks are exact at band boundaries. */
class LedgerMoneyTest {

    static final Map<String, Object> DEF = Map.of("id", "BUDGET", "kind", "LIMIT", "currency", "INR",
            "stages", List.of("ALLOCATION", "RELEASE"));

    static LedgerService jdbc(String name) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            LedgerSchema.create(c, Dialect.POSTGRESQL);
        }
        LedgerService s = new LedgerService(new JdbcLedgerStore(ds, Dialect.POSTGRESQL),
                new LedgerControlStore.JdbcStore(ds, Dialect.POSTGRESQL), null);
        s.define("T", List.of(LedgerDefinition.from(DEF)));
        DS.put(name, ds);
        return s;
    }

    static final Map<String, JdbcDataSource> DS = new java.util.concurrent.ConcurrentHashMap<>();

    static AccountKey key(String line) {
        return new AccountKey("T", "BUDGET", "2026-27", line, "DDO", "RELEASE", null);
    }

    @Test
    void balancesAreStoredAsBigintPaise() throws Exception {
        LedgerService s = jdbc("money1");
        AccountKey k = key("L1");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("100000.01"), "a")).applied());
        try (Connection c = DS.get("money1").getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT total FROM LDG_BALANCE")) {
            assertTrue(rs.next());
            assertEquals(java.sql.Types.BIGINT, rs.getMetaData().getColumnType(1));
            assertEquals(10_000_001L, rs.getLong(1));
        }
        assertEquals(0, new BigDecimal("100000.01").compareTo(s.available(k)));
    }

    @Test
    void amountsFinerThanTheMinorUnitAreRejectedNotRounded() throws Exception {
        LedgerService s = jdbc("money2");
        AccountKey k = key("L2");
        assertThrows(IllegalArgumentException.class,
                () -> s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("0.001"), "a")));
        assertEquals(0, s.available(k).signum());
    }

    /** SG-22: IFMS session case, COMMIT of INR 0.001 (below one paisa) was accepted. */
    @Test
    void sg22CommitBelowOnePaisaIsRejected() throws Exception {
        LedgerService s = jdbc("sg22");
        AccountKey k = key("S22");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("100"), "a")).applied());
        assertThrows(IllegalArgumentException.class, () -> s.apply(LedgerRequest.of("COMMIT", k, new BigDecimal("0.001"), "c")));
        assertEquals(0, new BigDecimal("100").compareTo(s.available(k)));
    }

    /** SG-23: IFMS session case, a negative COMMIT of -50 was accepted (it raised availability). */
    @ParameterizedTest
    @CsvSource({"COMMIT,-50", "ENCUMBER,-50", "CONSUME,-50", "ALLOCATE,-50", "COMMIT,0"})
    void sg23NonPositiveDirectionalAmountsAreRejected(String op, String amount) throws Exception {
        LedgerService s = jdbc("sg23" + op + amount.replace('-', 'm'));
        AccountKey k = key("S23");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("100"), "a")).applied());
        assertThrows(IllegalArgumentException.class, () -> s.apply(LedgerRequest.of(op, k, new BigDecimal(amount), "x")));
        assertEquals(0, new BigDecimal("100").compareTo(s.available(k)));
    }

    /** Limit 1,00,000.00: a commitment at or below it fits, one paisa / one rupee above does not. */
    @ParameterizedTest
    @CsvSource({"99999,true", "99999.99,true", "100000,true", "100000.00,true", "100000.01,false", "100001,false"})
    void limitBandBoundary(String commit, boolean fits) throws Exception {
        LedgerService s = jdbc("band" + commit.replace('.', '_'));
        AccountKey k = key("B");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("100000"), "a")).applied());
        assertEquals(fits, s.apply(LedgerRequest.of("COMMIT", k, new BigDecimal(commit), "c")).applied(), commit);
    }

    /** Same band in minor units: limit 1,00,000 paise (1,000.00). */
    @ParameterizedTest
    @CsvSource({"999.99,true", "1000.00,true", "1000.01,false"})
    void limitBandBoundaryInPaise(String commit, boolean fits) {
        LedgerService s = new LedgerService(new LedgerStore.InMemory(), null);
        s.define("T", List.of(LedgerDefinition.from(DEF)));
        AccountKey k = key("P");
        assertTrue(s.apply(LedgerRequest.of("ALLOCATE", k, new BigDecimal("1000.00"), "a")).applied());
        assertEquals(fits, s.apply(LedgerRequest.of("COMMIT", k, new BigDecimal(commit), "c")).applied(), commit);
    }

    /** Typed money API: LedgerRequest.money / availableMoney round-trip exact minor units. */
    @Test
    void moneyApi() {
        LedgerService s = new LedgerService(new LedgerStore.InMemory(), null);
        s.define("T", List.of(LedgerDefinition.from(DEF)));
        AccountKey k = key("M");
        org.sangam.core.money.Money m = org.sangam.core.money.Money.parse("100000.01", "INR");
        assertTrue(s.apply(LedgerRequest.money("ALLOCATE", k, m, "a")).applied());
        assertEquals(m, s.availableMoney(k));
        assertEquals(m, s.balance(k).orElseThrow().availableMoney());
        assertTrue(s.apply(LedgerRequest.money("COMMIT", k, org.sangam.core.money.Money.ofMinor(1, "INR"), "c")).applied());
        assertEquals(org.sangam.core.money.Money.parse("100000.00", "INR"), s.availableMoney(k));
    }

    @Test
    void unitsHelperIsExact() {
        assertEquals(10_000_000L, JdbcLedgerStore.units(new BigDecimal("100000"), "INR"));
        assertEquals(1_234L, JdbcLedgerStore.units(new BigDecimal("1.234"), "KWD"));
        assertEquals(5L, JdbcLedgerStore.units(new BigDecimal("5"), "JPY"));
        assertEquals(1_500_000L, JdbcLedgerStore.units(new BigDecimal("1.5"), null)); // quantity: 6 places
        assertThrows(IllegalArgumentException.class, () -> JdbcLedgerStore.units(new BigDecimal("1.5"), "JPY"));
        assertThrows(ArithmeticException.class, () -> JdbcLedgerStore.units(new BigDecimal("1E+30"), "INR"));
    }
}
