// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.rest.support.WorkLongPoll;
import org.sangam.security.tenant.TenantContext;
import org.sangam.server.bridge.CellTenantStateStore;

/**
 * M-005 / M-053 / M-008 server wiring: the LISTEN connection is a physical connection outside the pool, a data source
 * that is not a HikariCP pool gets none (in-JVM wake-up only), the long-poll carries the request's tenant identity to
 * the poll thread (and nothing else), and broker inboxes take messages only for an ACTIVE cell tenant.
 */
class WakeupLongPollWiringTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theListenerConnectionIsPhysicalAndNeverBorrowedFromThePool() throws Exception {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:wakeuplisten" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            pool.setUsername("sa");
            pool.setPassword("");
            var source = IntegrationWiring.listenerConnections(pool);
            assertNotNull(source);
            try (Connection c = source.open()) {
                assertTrue(c.isValid(1));
                assertFalse(c.getClass().getName().contains("hikari"), "a driver connection, not a pool proxy: "
                        + c.getClass().getName());
            }
            assertFalse(pool.isRunning(), "the pool was never started for the listener");
        }
    }

    @Test
    void aDataSourceThatIsNotAHikariPoolGetsNoListenerConnection() {
        DataSource plain = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, args) -> m.getName().equals("isWrapperFor") ? false : null);
        assertNull(IntegrationWiring.listenerConnections(plain));
        try (HikariDataSource noUrl = new HikariDataSource()) {
            assertNull(IntegrationWiring.listenerConnections(noUrl), "a pool without a JDBC URL");
        }
    }

    @Test
    void theLongPollCarriesTheRequestsTenantIdentityAndOnlyThat() throws Exception {
        WorkLongPoll.ContextCarrier carrier = IntegrationWiring.tenantIdentityCarrier();
        SecurityIdentity t1 = SecurityIdentity.system("T1", "worker-test");
        TenantContext.set(t1);
        WorkLongPoll.ContextCarrier.Scope withT1 = carrier.capture();
        TenantContext.clear();
        WorkLongPoll.ContextCarrier.Scope withNone = carrier.capture();

        CompletableFuture<Optional<SecurityIdentity>> seen = new CompletableFuture<>();
        CompletableFuture<Optional<SecurityIdentity>> after = new CompletableFuture<>();
        CompletableFuture<Optional<SecurityIdentity>> none = new CompletableFuture<>();
        Thread poller = new Thread(() -> {
            seen.complete(withT1.call(TenantContext::current));
            after.complete(TenantContext.current());
            // a poll thread that still had an identity bound must not lend it to a request without one
            TenantContext.set(SecurityIdentity.system("T2", "stale"));
            none.complete(withNone.call(TenantContext::current));
            TenantContext.clear();
        });
        poller.start();
        poller.join(5_000);
        assertEquals(Optional.of(t1), seen.get(1, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), after.get(1, TimeUnit.SECONDS), "restored after the poll");
        assertEquals(Optional.empty(), none.get(1, TimeUnit.SECONDS));
    }

    @Test
    void brokerInboxesTakeMessagesOnlyForAnActiveCellTenant() {
        for (CellTenantStateStore.State s : CellTenantStateStore.State.values()) {
            assertEquals(s == CellTenantStateStore.State.ACTIVE, CellWiring.admitsInbound(s), s.name());
        }
    }
}
