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
}
