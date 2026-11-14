// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import org.sangam.rest.config.SpringTxSupport;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.SqlPredicate;
import org.sangam.registry.RegistryContext;
import org.sangam.registry.RegistryPorts;
import org.sangam.rest.support.TxSupport;
import org.sangam.security.SangamSecurity;
import org.sangam.security.mask.FieldMaskService;
import org.sangam.security.policy.AuthorizationService;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Registry-port connection defect (integrator to W3-E, for W3-L M-130): the visibility and privacy ports ran their
 * security reads on {@code TxSupport.connection()}, i.e. {@code DataSourceUtils.getConnection(ds)}. A caller that holds
 * its own connection outside a Spring transaction (the bulk worker, an engine delegate on a non-Spring command, any
 * {@code Transactions.of(ds)} unit) then got a SECOND pool connection that nothing closed, and reads that could not see
 * its uncommitted writes. Fixed at the root: the ports take the caller's connection. This test holds the only
 * connection of a one-connection pool, as such a caller does; before the fix each port call timed out waiting for a
 * second connection (and leaked it when the pool had one), after the fix the security service receives the caller's
 * connection and the pool lends nothing more. H2 in PostgreSQL mode.
 */
class RegistryPortConnectionH2Test {

    HikariDataSource ds;
    TxSupport tx;

    @BeforeEach
    void pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:h2:mem:regportconn" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionTimeout(250);
        ds = new HikariDataSource(cfg);
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        try (Connection c = ds.getConnection()) {
            Schema.create(c, Dialect.POSTGRESQL);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void close() {
        ds.close();
    }

    static RegistryContext ctx() {
        return new RegistryContext("t1", "system:bulk", null, null, null, java.time.ZoneOffset.UTC);
    }

    @Test
    void visibilityUsesTheCallersConnection() throws Exception {
        SangamSecurity security = mock(SangamSecurity.class);
        AuthorizationService authz = mock(AuthorizationService.class);
        when(security.authorization()).thenReturn(authz);
        AtomicReference<Connection> seen = new AtomicReference<>();
        when(authz.visible(any(Connection.class), any(), eq("registry-object"), eq("READ"), eq("o"))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return new SqlPredicate("o.tenant_id = ?", List.of("t1"));
        });
        RegistryPorts.VisibilityPort port = RegistryPortBridges.visibility(security, tx);
        try (Connection caller = ds.getConnection()) {
            caller.setAutoCommit(false);
            SqlPredicate p = port.visible(caller, ctx(), "registry-object", "o");
            assertEquals("o.tenant_id = ?", p.sql());
            assertSame(caller, seen.get(), "the security read runs on the caller's connection");
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(), "no second connection was borrowed");
            caller.rollback();
        }
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "nothing leaked");
    }

    @Test
    void privacyUsesTheCallersConnection() throws Exception {
        SangamSecurity security = mock(SangamSecurity.class);
        FieldMaskService masks = mock(FieldMaskService.class);
        when(security.masks()).thenReturn(masks);
        AtomicReference<Connection> seen = new AtomicReference<>();
        when(masks.mask(any(Connection.class), any(), anyString(), any(), any(), any())).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Map.of("id", "p1", "name", "XXXX");
        });
        RegistryPorts.PrivacyPort port = RegistryPortBridges.privacy(security, tx);
        try (Connection caller = ds.getConnection()) {
            Map<String, Object> out = port.mask(caller, ctx(), "registry-party", Map.of("id", "p1", "name", "Asha"),
                    List.of("name"));
            assertEquals("XXXX", out.get("name"));
            assertSame(caller, seen.get());
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections());
        }
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void aPortCallWithoutTheCallersConnectionIsRefusedInsteadOfBorrowingOne() {
        SangamSecurity security = mock(SangamSecurity.class);
        when(security.authorization()).thenReturn(mock(AuthorizationService.class));
        when(security.masks()).thenReturn(mock(FieldMaskService.class));
        RegistryPorts.VisibilityPort visibility = RegistryPortBridges.visibility(security, tx);
        RegistryPorts.PrivacyPort privacy = RegistryPortBridges.privacy(security, tx);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> visibility.visible(null, ctx(), "registry-object", "o"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> privacy.mask(null, ctx(), "registry-party", Map.of("id", "p1"), List.of("name")));
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "no pool connection was borrowed");
    }

    @Test
    void theDefaultsIgnoreTheConnectionAndKeepTheirBehaviour() {
        SqlPredicate p = RegistryPorts.VisibilityPort.TENANT_ONLY.visible(null, ctx(), "registry-object", "o");
        assertEquals("o.tenant_id = ?", p.sql());
        assertEquals(List.of("t1"), p.params());
        Map<String, Object> view = Map.of("id", "p1", "name", "Asha");
        assertSame(view, RegistryPorts.PrivacyPort.NONE.mask(null, ctx(), "registry-party", view, List.of("name")));
        org.junit.jupiter.api.Assertions.assertTrue(RegistryPorts.ConsentPort.PERMIT_ALL
                .check(null, ctx(), "p1", "purpose", "READ").permitted());
    }

    static int outboxRows(Connection c, String eventType) throws java.sql.SQLException {
        try (var ps = c.prepareStatement("SELECT COUNT(*) FROM OUTBOX WHERE event_type = ?")) {
            ps.setString(1, eventType);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** SL-169 part 2: the publish row is written on the caller's connection and commits or rolls back with it. */
    @Test
    void publishWritesItsOutboxRowOnTheCallersConnection() throws Exception {
        RegistryPorts.PublishPort port = RegistryPortBridges.publisher(tx, "locker-1");
        try (Connection caller = ds.getConnection()) {
            caller.setAutoCommit(false);
            assertEquals(java.util.Optional.empty(),
                    port.publish(caller, ctx(), "channel-1", "object-1", "doc-1", Map.of("k", "v")));
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(), "no second connection was borrowed");
            assertEquals(1, outboxRows(caller, RegistryPortBridges.EVT_PUBLISH),
                    "the row is visible on the caller's own uncommitted connection");
            caller.rollback();
            assertEquals(0, outboxRows(caller, RegistryPortBridges.EVT_PUBLISH), "the row rolled back with the caller");
        }
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "nothing leaked");
    }

    /** SL-169 part 2: the system-of-record push is queued on the caller's connection. */
    @Test
    void systemOfRecordPushWritesItsOutboxRowOnTheCallersConnection() throws Exception {
        RegistryPorts.SystemOfRecordPort port = RegistryPortBridges.systemOfRecord(null, typeKey -> "sor-1", tx);
        try (Connection caller = ds.getConnection()) {
            caller.setAutoCommit(false);
            port.push(caller, ctx(), "vehicle", "object-1", "ext-1", "CREATE", Map.of("id", "object-1"));
            assertEquals(1, ds.getHikariPoolMXBean().getActiveConnections(), "no second connection was borrowed");
            assertEquals(1, outboxRows(caller, RegistryPortBridges.EVT_SOR_PUSH));
            caller.commit();
            assertEquals(1, outboxRows(caller, RegistryPortBridges.EVT_SOR_PUSH), "the row committed with the caller");
        }
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "nothing leaked");
    }

    @Test
    void publishAndPushWithoutTheCallersConnectionAreRefused() throws Exception {
        RegistryPorts.PublishPort publish = RegistryPortBridges.publisher(tx, "locker-1");
        RegistryPorts.SystemOfRecordPort sor = RegistryPortBridges.systemOfRecord(null, typeKey -> "sor-1", tx);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> publish.publish(null, ctx(), "channel-1", "object-1", "doc-1", Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> sor.push(null, ctx(), "vehicle", "object-1", "ext-1", "CREATE", Map.of()));
        assertEquals(0, ds.getHikariPoolMXBean().getActiveConnections(), "no pool connection was borrowed");
        try (Connection c = ds.getConnection()) {
            assertEquals(0, outboxRows(c, RegistryPortBridges.EVT_PUBLISH));
            assertEquals(0, outboxRows(c, RegistryPortBridges.EVT_SOR_PUSH));
        }
    }

    @Test
    void thePublishAndRecordDefaultsIgnoreTheConnection() {
        assertEquals(java.util.Optional.empty(),
                RegistryPorts.PublishPort.NONE.publish(null, ctx(), "channel-1", "object-1", "doc-1", Map.of()));
        RegistryPorts.SystemOfRecordPort.NONE.push(null, ctx(), "vehicle", "object-1", "ext-1", "CREATE", Map.of());
    }
}
