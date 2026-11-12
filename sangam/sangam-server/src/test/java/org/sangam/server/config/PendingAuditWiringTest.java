// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.PendingAudit;
import org.sangam.core.layer.PendingAuditDrainer;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.bridge.operaton.IntegrationBridge;
import org.sangam.integration.connector.ConnectorInvoker;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.mock.env.MockEnvironment;

/**
 * SL-133 (M-003) goes live: the server's JavaDelegate invoker ({@code IntegrationBridge.delegateInvoker()}) defers its
 * audit to the durable pending store and the drainer, so a connector call inside a job no longer waits for the root
 * lock its own job holds. The direct invoker bean keeps direct audit. Drainer settings come from
 * {@code sangam.audit.pending.*} with the core defaults.
 */
class PendingAuditWiringTest {

    static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setConversionService(new ApplicationConversionService());
        return env;
    }

    @Test
    void drainerSettingsDefaultToTheCoreDefaults() {
        assertEquals(PendingAuditDrainer.Settings.defaults(), IntegrationWiring.pendingAuditSettings(env()));
        assertEquals(new PendingAuditDrainer.Settings(2, Duration.ofSeconds(30), Duration.ofSeconds(10), 200),
                IntegrationWiring.pendingAuditSettings(env()), "2 workers, 30s, 10s, 200");
    }

    @Test
    void drainerSettingsAreConfigurable() {
        MockEnvironment env = env().withProperty("sangam.audit.pending.workers", "4")
                .withProperty("sangam.audit.pending.sweep-interval", "0s")
                .withProperty("sangam.audit.pending.sweep-min-age", "1m")
                .withProperty("sangam.audit.pending.sweep-batch", "50");
        assertEquals(new PendingAuditDrainer.Settings(4, Duration.ZERO, Duration.ofMinutes(1), 50),
                IntegrationWiring.pendingAuditSettings(env));
    }

    @Test
    void theDelegateInvokerDefersItsAuditAndTheDirectInvokerDoesNot() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pendingwiring" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ConnectorInvoker direct = new ConnectorInvoker(Transactions.of(ds), null, null, null, new AuditChain(Dialect.POSTGRESQL));
        IntegrationBridge previous;
        try {
            previous = IntegrationBridge.get();
        } catch (IllegalStateException none) {
            previous = null;
        }
        IntegrationWiring wiring = new IntegrationWiring();
        PendingAudit pending = new PendingAudit(Transactions.of(ds), Dialect.POSTGRESQL, new AuditChain(Dialect.POSTGRESQL));
        try (PendingAuditDrainer drainer = new PendingAuditDrainer(pending,
                new PendingAuditDrainer.Settings(1, Duration.ZERO, Duration.ofSeconds(10), 10))) {
            IntegrationBridge bridge = wiring.sangamIntegrationBridge(direct, drainer, null,
                    env().withProperty("sangam.tenant.default", "T1"));
            assertTrue(bridge.delegateInvoker().defersAudit(), "JavaDelegate path: pending rows plus drain (SL-133)");
            assertFalse(direct.defersAudit(), "the shared invoker bean keeps direct audit");
        } finally {
            if (previous != null) {
                IntegrationBridge.install(previous);
            }
        }
    }
}
