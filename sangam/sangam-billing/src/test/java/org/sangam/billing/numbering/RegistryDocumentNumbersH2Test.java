// SPDX-License-Identifier: Apache-2.0
package org.sangam.billing.numbering;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.billing.DocumentNumbers;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryException;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.RegistrySchema;
import org.sangam.registry.numbering.NumberingService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SL-130 / SL-131 (M-002) over the registry {@link NumberingService} on H2 (MODE=PostgreSQL): one GAP_FREE number per
 * document identity, recorded with ref_type / ref_id, stored with the plan in {@code BIL_DOCUMENT}; a retry draws
 * nothing, and a failed first issue leaves neither a document row nor a burned number.
 *
 * <p>Needs the H2 driver on the sangam-billing test classpath (test-scoped {@code com.h2database:h2}, as in
 * sangam-config / sangam-ledger / sangam-registry); the driver is loaded by URL, so the test compiles without it.
 */
class RegistryDocumentNumbersH2Test {

    static final LocalDate DAY = LocalDate.parse("2026-11-12");

    String url;
    Transactions tx;
    NumberingService numbering;
    RegistryDocumentNumbers numbers;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException | Error e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        tx.inTransaction(c -> {
            Schema.create(c, Dialect.POSTGRESQL);
            RegistrySchema.create(c, Dialect.POSTGRESQL);
            try (Statement s = c.createStatement()) {
                // the BIL_DOCUMENT shape of generate_changelogs.py 0037-01 (H2 types)
                s.execute("CREATE TABLE BIL_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, doc_type VARCHAR(32) NOT NULL,"
                        + " doc_key VARCHAR(255) NOT NULL, series_key VARCHAR(64) NOT NULL, doc_number VARCHAR(255),"
                        + " plan_json CHARACTER LARGE OBJECT, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                        + " created_by VARCHAR(255), CONSTRAINT PK_BIL_DOCUMENT PRIMARY KEY (tenant_id, doc_type, doc_key))");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        // decision 143: a test installation (switch false plus the test profile), resolved by the shared policy
        numbering = new RegistryModule(Dialect.POSTGRESQL, RegistryPorts.defaults(), null,
                org.sangam.core.security.EnvironmentPolicy.resolve("false", "test",
                        Map.of("test", org.sangam.core.security.EnvironmentPolicy.Signal.DEVELOPMENT))).numbering();
        numbers = new RegistryDocumentNumbers(tx, Dialect.POSTGRESQL, numbering);
    }

    static DocumentNumbers.Request receipt(String paymentRef) {
        return new DocumentNumbers.Request("T", DocumentNumbers.Kind.RECEIPT, DocumentNumbers.receiptKey(paymentRef),
                "COUNTER", DAY, "cashier");
    }

    static DocumentNumbers.Request invoice(String consumer, String cycle) {
        return new DocumentNumbers.Request("T", DocumentNumbers.Kind.INVOICE,
                DocumentNumbers.invoiceKey("DEMAND", consumer, cycle), cycle, DAY, "sys");
    }

    private int count(String sql, Object... params) {
        return tx.inTransaction(c -> Jdbc.one(c, sql, rs -> rs.getInt(1), params).orElse(0));
    }

    @Test
    void aRetryReturnsTheStoredNumberAndPlanAndDrawsNothing() {
        AtomicInteger planned = new AtomicInteger();
        Map<String, Object> plan = Map.of("amount", "100.00", "currency", "INR", "legs", List.of(Map.of("amount", "100.00")));
        DocumentNumbers.Issued first = numbers.issue(receipt("PAY-1"), () -> {
            planned.incrementAndGet();
            return plan;
        });
        DocumentNumbers.Issued again = numbers.issue(receipt("PAY-1"), () -> {
            planned.incrementAndGet();
            return Map.of("amount", "999.00");
        });
        assertEquals("RCP/COUNTER/2026-11-12/1", first.number());
        assertTrue(first.first());
        assertFalse(again.first());
        assertEquals(first.number(), again.number());
        assertEquals(plan, again.plan(), "the stored plan, not a new one");
        assertEquals(1, planned.get(), "the plan is computed once");

        NumberingService.GapReport gaps = tx.inTransaction(c -> numbering.gapReport(c, RegistryContext.of("T", "auditor"),
                "RECEIPT", "COUNTER", "2026-11-12"));
        assertEquals(1, gaps.issued());
        assertEquals(1, gaps.used());
        assertEquals(0, gaps.missing());
        assertEquals(1, count("SELECT COUNT(*) FROM REG_NUMBER_ALLOCATION WHERE tenant_id=? AND series_key=? AND"
                + " ref_type=? AND ref_id=? AND status='USED'", "T", "RECEIPT", "billing.receipt", "PAY-1"));
    }

    @Test
    void invoicesAreNumberedPerCycleInTheConfigSeriesFormat() {
        assertEquals("INV/2026-10/1", numbers.issue(invoice("C-1", "2026-10"), Map::of).number());
        assertEquals("INV/2026-10/1", numbers.issue(invoice("C-1", "2026-10"), Map::of).number(), "re-invoice");
        assertEquals("INV/2026-10/2", numbers.issue(invoice("C-2", "2026-10"), Map::of).number());
        assertEquals("INV/2026-11/1", numbers.issue(invoice("C-1", "2026-11"), Map::of).number());
        assertEquals(1, count("SELECT COUNT(*) FROM REG_NUMBER_ALLOCATION WHERE tenant_id=? AND ref_type=? AND ref_id=?",
                "T", "billing.invoice", DocumentNumbers.invoiceKey("DEMAND", "C-1", "2026-10")));
    }

    @Test
    void aFailedFirstIssueLeavesNoDocumentAndBurnsNoNumber() {
        numbers.issue(receipt("PAY-1"), Map::of);   // defines the series, number 1
        tx.inTransaction(c -> {
            numbering.closePeriod(c, RegistryContext.of("T", "officer"), "RECEIPT", "COUNTER", "2026-11-12");
            return null;
        });
        assertThrows(RegistryException.class, () -> numbers.issue(receipt("PAY-2"), Map::of));
        assertEquals(0, count("SELECT COUNT(*) FROM BIL_DOCUMENT WHERE tenant_id=? AND doc_key=?", "T", "PAY-2"),
                "the claim rolled back with the refused allocation");
        assertEquals(2, count("SELECT next_value FROM REG_NUMBER_COUNTER WHERE tenant_id=? AND series_key=?"
                + " AND scope_key=? AND period_key=?", "T", "RECEIPT", "COUNTER", "2026-11-12"), "no number drawn");
    }

    @Test
    void aSeriesTheTenantDefinedIsUsedAsDefined() {
        tx.inTransaction(c -> numbering.define(c, RegistryContext.of("T", "admin"), Map.of("seriesKey", "INVOICE",
                "pattern", "BILL/{scope}/{seq:4}", "gapPolicy", "GAP_FREE", "reset", "NEVER")));
        assertEquals("BILL/2026-10/0001", numbers.issue(invoice("C-1", "2026-10"), Map::of).number());
        assertEquals("BILL/{scope}/{seq:4}", tx.inTransaction(c -> numbering.series(c, "T", "INVOICE")).pattern(),
                "not overwritten by the default");
    }

    @Test
    void tenantsAreSeparate() {
        DocumentNumbers.Request other = new DocumentNumbers.Request("U", DocumentNumbers.Kind.RECEIPT,
                DocumentNumbers.receiptKey("PAY-1"), "COUNTER", DAY, "cashier");
        assertEquals("RCP/COUNTER/2026-11-12/1", numbers.issue(receipt("PAY-1"), Map::of).number());
        DocumentNumbers.Issued u = numbers.issue(other, Map::of);
        assertTrue(u.first(), "the same paymentRef in another tenant is another document");
        assertEquals("RCP/COUNTER/2026-11-12/1", u.number());
    }
}
