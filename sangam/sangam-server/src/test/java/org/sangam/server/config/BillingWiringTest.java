// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.billing.DocumentNumbers;
import org.sangam.billing.numbering.RegistryDocumentNumbers;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.registry.RegistryModule;
import org.sangam.registry.RegistryPorts;
import org.sangam.registry.RegistrySchema;
import org.sangam.registry.numbering.NumberingService;
import org.sangam.rest.support.TxSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * SL-130 / SL-131 go live: the server's {@code BillingService} issues invoice and receipt numbers through the registry
 * numbering ({@link RegistryDocumentNumbers}) on the server's own transactions ({@code TxSupport.newTransactions()}),
 * not through the node-local config-series map. On H2 (MODE=PostgreSQL), with the {@code BIL_DOCUMENT} shape of
 * {@code generate_changelogs.py} 0037-01.
 */
class BillingWiringTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-12");

    TxSupport tx;
    NumberingService numbering;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:billwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        tx.newTransactions().inTransaction(c -> {
            Schema.create(c, Dialect.POSTGRESQL);
            RegistrySchema.create(c, Dialect.POSTGRESQL);
            try (Statement s = c.createStatement()) {
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
    }

    static DocumentNumbers.Request receipt(String paymentRef) {
        return new DocumentNumbers.Request("T", DocumentNumbers.Kind.RECEIPT, DocumentNumbers.receiptKey(paymentRef),
                "COUNTER", DAY, "cashier");
    }

    private int allocations(String refId) {
        return tx.newTransactions().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM REG_NUMBER_ALLOCATION WHERE"
                + " tenant_id=? AND ref_type=? AND ref_id=?", rs -> rs.getInt(1), "T", "billing.receipt", refId).orElse(0));
    }

    @Test
    void theServerIssuesThroughTheRegistryNumberingAndARetryDrawsNothing() {
        DocumentNumbers numbers = BillingWiring.documentNumbers(tx, numbering);
        assertInstanceOf(RegistryDocumentNumbers.class, numbers, "the durable registry numbers, not overSeries");
        DocumentNumbers.Issued first = numbers.issue(receipt("PAY-1"), () -> Map.of("amount", "100.00"));
        DocumentNumbers.Issued again = numbers.issue(receipt("PAY-1"), () -> Map.of("amount", "999.00"));
        assertTrue(first.first());
        assertFalse(again.first());
        assertEquals("RCP/COUNTER/2026-11-12/1", first.number());
        assertEquals(first.number(), again.number());
        assertEquals(Map.of("amount", "100.00"), again.plan(), "the stored plan is replayed (SL-131)");
        assertEquals(1, allocations("PAY-1"), "one allocation, recorded with the document as ref_type / ref_id");
    }

    @Test
    void aNumberIssuedInsideACallerTransactionThatRollsBackIsKeptForTheRetry() {
        DocumentNumbers numbers = BillingWiring.documentNumbers(tx, numbering);
        String inRolledBack = tx.inTxWithStatus(status -> {
            String n = numbers.issue(receipt("PAY-2"), Map::of).number();
            status.setRollbackOnly();
            return n;
        });
        DocumentNumbers.Issued retry = numbers.issue(receipt("PAY-2"), Map::of);
        assertFalse(retry.first(), "the issue committed in its own transaction");
        assertEquals(inRolledBack, retry.number(), "the retry gets the same number, no second one");
        assertEquals(1, allocations("PAY-2"));
    }

    @Test
    void billingIsWiredOnlyWithTheRegistryModule() {
        String condition = BillingWiring.class.getAnnotation(ConditionalOnExpression.class).value();
        assertTrue(condition.contains("${sangam.modules.registry.enabled:true}"), condition);
        assertTrue(condition.contains("${sangam.modules.billing.enabled:true}"), condition);
    }
}
