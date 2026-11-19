// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.connector.ConnectorInvoker;
import org.sangam.integration.relay.OutboxWriter;
import org.sangam.integration.relay.PublisherRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * RM-05 and owner decision 126 (part 2) at start-up: a GL connector without the integration beans that carry its
 * entries is refused (the old null-writer return dropped every entry), production requires the GL export wherever the
 * ledger is enabled (both production forms, SL-109), and the ledger refuses to start without the SL-133 pending-audit
 * drainer that carries its {@code LEDGER_POSTED} rows (RM-04).
 */
class LedgerWiringGlGuardTest {

    private static MockEnvironment helm() {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "true");
        env.setActiveProfiles("postgres", "oidc");
        return env;
    }

    private static MockEnvironment prodProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("postgres", "prod");
        return env;
    }

    private static MockEnvironment development() {
        // decision 143: a development installation states the switch; without it the environment is production (fail closed)
        MockEnvironment env = new MockEnvironment().withProperty("sangam.security.production", "false");
        env.setActiveProfiles("h2");
        return env;
    }

    @Test
    void aGlConnectorWithoutTheIntegrationBeansIsRefused() {
        IllegalStateException noWriter = assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport("gl-1", false, false, true, true));
        assertTrue(noWriter.getMessage().contains("OutboxWriter") && noWriter.getMessage().contains("RM-05"),
                noWriter.getMessage());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport("gl-1", false, true, false, true)).getMessage()
                .contains("PublisherRegistry"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport("gl-1", false, true, true, false)).getMessage()
                .contains("ConnectorInvoker"));
        assertEquals("gl-1", LedgerWiring.requireGlExport(" gl-1 ", true, true, true, true));
    }

    @Test
    void productionRequiresTheGlExportWhereTheLedgerIsEnabled() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport(null, true, true, true, true));
        assertTrue(e.getMessage().contains("sangam.ledger.gl-connector must be set in production"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> LedgerWiring.requireGlExport("  ", true, true, true, true));
        assertNull(LedgerWiring.requireGlExport(null, false, false, false, false), "development may run without it");
    }

    @Test
    void theStoreBeanRefusesBothProductionFormsWithoutAGlConnector() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        for (MockEnvironment env : new MockEnvironment[] {helm(), prodProfile()}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerStore(
                    null, env, bf.getBeanProvider(PendingAuditDrainer.class), bf.getBeanProvider(OutboxWriter.class),
                    bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class)));
            assertTrue(e.getMessage().contains("sangam.ledger.gl-connector must be set in production"), e.getMessage());
        }
    }

    @Test
    void theStoreBeanRefusesAGlConnectorWithIntegrationOff() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        MockEnvironment env = development().withProperty("sangam.ledger.gl-connector", "gl-1");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerStore(
                null, env, bf.getBeanProvider(PendingAuditDrainer.class), bf.getBeanProvider(OutboxWriter.class),
                bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class)));
        assertTrue(e.getMessage().contains("RM-05"), e.getMessage());
    }

    @Test
    void theRegistrationRefusesAGlConnectorWithoutPublisherOrInvoker() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        MockEnvironment env = development().withProperty("sangam.ledger.gl-connector", "gl-1");
        assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerConnectorRegistration(null,
                bf.getBeanProvider(org.sangam.integration.connector.ConnectorRegistry.class),
                bf.getBeanProvider(org.sangam.integration.packs.PackConnectorBindings.class),
                bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class), env)
                .afterPropertiesSet());
    }

    @Test
    void theLedgerNeedsThePendingAuditDrainer() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LedgerWiring.requirePendingAudit(null));
        assertTrue(e.getMessage().contains("LEDGER_POSTED"), e.getMessage());
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerStore(null, development(),
                bf.getBeanProvider(PendingAuditDrainer.class), bf.getBeanProvider(OutboxWriter.class),
                bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class)));
    }

    @Test
    void theJournalCarriesTheGlExportOnlyWhenAConnectorIsSet() {
        PendingAudit pending = new PendingAudit(Transactions.of(new org.h2.jdbcx.JdbcDataSource()), Dialect.POSTGRESQL,
                new AuditChain(Dialect.POSTGRESQL));
        try (PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10))) {
            assertTrue(LedgerWiring.journal(drainer, null, null) != null, "audit-only journal without a GL connector");
            OutboxWriter writer = new OutboxWriter(Dialect.POSTGRESQL, OutboxWriter.SubscriptionResolver.fixed(java.util.List.of()));
            assertTrue(LedgerWiring.journal(drainer, "gl-1", writer) != null);
        }
    }

    @Test
    void productionRefusesAGlConnectorIdThatNamesNoConnector() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireKnownGlConnector("no-such-gl", true, id -> false, java.util.Set.of()));
        assertTrue(e.getMessage().startsWith(LedgerWiring.GL_CONNECTOR_UNKNOWN + ":")
                && e.getMessage().contains("no-such-gl"), e.getMessage());
        assertEquals("LEDGER_GL_CONNECTOR_UNKNOWN", LedgerWiring.GL_CONNECTOR_UNKNOWN);
        assertTrue(!LedgerWiring.requireKnownGlConnector("no-such-gl", false, id -> false, java.util.Set.of()),
                "development warns and keeps the entries (retried, then dead-lettered)");
    }

    @Test
    void guardARegisteredOrDeclaredGlConnectorIsAccepted() {
        org.sangam.integration.connector.ConnectorRegistry registry = new org.sangam.integration.connector.ConnectorRegistry();
        assertTrue(!registry.knows("gl-1") && !registry.knows(null) && !registry.knows(" "));
        assertTrue(LedgerWiring.requireKnownGlConnector("gl-1", true, id -> id.equals("gl-1"), java.util.Set.of()));
        assertTrue(LedgerWiring.requireKnownGlConnector("gl-1", true, id -> false, java.util.Set.of("gl-1")));
        assertTrue(LedgerWiring.requireKnownGlConnector(null, true, id -> false, java.util.Set.of()), "unset: requireGlExport");
        MockEnvironment env = development().withProperty("sangam.channels.enterprise.t1.gl-sap.type", "sap-odata")
                .withProperty("sangam.channels.enterprise.t1.gl-sap.service-root", "https://sap.example/odata")
                .withProperty("sangam.channels.enterprise.t2.other.service-root", "https://x");
        assertEquals(java.util.Set.of("gl-sap"), LedgerWiring.declaredEnterpriseConnectors(env));
    }

    @Test
    void theGlExportWaiverAllowsProductionWithoutAConnector() {
        assertNull(LedgerWiring.requireGlExport(null, "GL go-live pending", true, true, true, true));
        assertNull(LedgerWiring.requireGlExport(" ", "GL go-live pending", true, false, false, false),
                "no GL rows are written, so the integration beans are not needed for them");
        IllegalStateException without = assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport(null, null, true, true, true, true));
        assertTrue(without.getMessage().contains(LedgerWiring.GL_EXPORT_WAIVER), without.getMessage());
    }

    @Test
    void aBlankWaiverOrAWaiverWithAConnectorIsRefused() {
        for (String blank : new String[] {"", "   ", "\t"}) {
            for (boolean production : new boolean[] {true, false}) {
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> LedgerWiring.requireGlExport(null, blank, production, true, true, true));
                assertTrue(e.getMessage().contains("needs a written reason"), e.getMessage());
            }
        }
        IllegalStateException both = assertThrows(IllegalStateException.class,
                () -> LedgerWiring.requireGlExport("gl-1", "reason", true, true, true, true));
        assertTrue(both.getMessage().contains("ambiguous"), both.getMessage());
        assertThrows(IllegalStateException.class, () -> LedgerWiring.requireGlExport("gl-1", "reason", false, true, true, true));
        assertEquals("sangam.ledger.gl-export-waiver", LedgerWiring.GL_EXPORT_WAIVER);
    }

    @Test
    void theStoreBeanStartsProductionUnderTheWaiverAndRefusesTheAmbiguousForm() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        MockEnvironment both = helm().withProperty("sangam.ledger.gl-connector", "gl-1")
                .withProperty(LedgerWiring.GL_EXPORT_WAIVER, "reason");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerStore(
                null, both, bf.getBeanProvider(PendingAuditDrainer.class), bf.getBeanProvider(OutboxWriter.class),
                bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class)));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
        MockEnvironment waived = helm().withProperty(LedgerWiring.GL_EXPORT_WAIVER, "reason");
        IllegalStateException next = assertThrows(IllegalStateException.class, () -> new LedgerWiring().sangamLedgerStore(
                null, waived, bf.getBeanProvider(PendingAuditDrainer.class), bf.getBeanProvider(OutboxWriter.class),
                bf.getBeanProvider(PublisherRegistry.class), bf.getBeanProvider(ConnectorInvoker.class)));
        assertTrue(next.getMessage().contains("LEDGER_POSTED"), "past the GL rule, stopped at the audit drainer: "
                + next.getMessage());
    }

    @Test
    void aStartUnderTheWaiverIsASystemAuditEventWithTheReason() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (java.sql.Connection c = ds.getConnection()) {
            org.sangam.core.layer.Schema.create(c, Dialect.POSTGRESQL);
            PendingAudit.create(c, Dialect.POSTGRESQL);
        }
        Transactions tx = Transactions.of(ds);
        PendingAudit pending = new PendingAudit(tx, Dialect.POSTGRESQL, new AuditChain(Dialect.POSTGRESQL));
        try (PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ZERO, 10))) {
            LedgerWiring.recordWaiver(tx, drainer, "system:platform", "  GL go-live pending  ", true);
            String payload = tx.inTransaction(c -> org.sangam.core.layer.Jdbc.one(c, "SELECT payload FROM AUDIT_PENDING"
                            + " WHERE tenant_id=? AND chain_id=? AND event_type=?", rs -> rs.getString(1), "system:platform",
                    LedgerWiring.GL_EXPORT_WAIVER_CHAIN, LedgerWiring.GL_EXPORT_WAIVED)).orElse(null);
            String chained = tx.inTransaction(c -> org.sangam.core.layer.Jdbc.one(c, "SELECT p.payload FROM AUDIT_EVENT e"
                            + " JOIN AUDIT_PAYLOAD p ON p.event_id = e.event_id"
                            + " WHERE e.tenant_id=? AND e.chain_id=? AND e.event_type=?", rs -> rs.getString(1), "system:platform",
                    LedgerWiring.GL_EXPORT_WAIVER_CHAIN, LedgerWiring.GL_EXPORT_WAIVED)).orElse(null);
            String recorded = payload != null ? payload : chained;
            assertTrue(recorded != null && recorded.contains("GL go-live pending")
                    && recorded.contains(LedgerWiring.GL_EXPORT_WAIVER), String.valueOf(recorded));
            assertThrows(IllegalStateException.class,
                    () -> LedgerWiring.recordWaiver(tx, drainer, "acme", "reason", true), "never a customer tenant");
        }
    }
}
