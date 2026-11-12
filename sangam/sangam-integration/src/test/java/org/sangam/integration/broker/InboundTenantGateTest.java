// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.relay.TenantAdmission;
import org.sangam.integration.schema.IntegrationSchema;
import org.sangam.ports.events.InboundTenantAdmission;
import org.sangam.ports.events.InboundTenantAdmission.Verdict;

/**
 * C3-06 / SL-142 for the event-gateway channels: the gate maps the one cell decision onto the port and parks through the
 * one park store. ACTIVE admitted; FROZEN/IMPORTING redelivered; MOVED/PURGED parked whole (dead letter + parked
 * reconciliation item + INBOUND_PARKED audit) and only then acknowledged; a failed park, a missing park store, a
 * failing check or a message without id is redelivered (never dropped).
 */
class InboundTenantGateTest {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String TENANT = "T1";

    String url;
    Connection keepAlive;
    PoisonInbound poison;
    final Map<String, String> states = new ConcurrentHashMap<>();
    final TenantAdmission cells = new TenantAdmission() {
        @Override
        public boolean admits(String tenant) {
            return decide(tenant).action() == Action.ADMIT;
        }

        @Override
        public Decision decide(String tenant) {
            String s = states.getOrDefault(tenant, "ACTIVE");
            return switch (s) {
                case "ACTIVE" -> Decision.ADMIT;
                case "MOVED", "PURGED" -> Decision.park(s, "cell-b", "mv-1");
                case "BROKEN" -> throw new IllegalStateException("state store down");
                default -> Decision.REDELIVER;
            };
        }
    };

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:gate" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
        poison = new PoisonInbound(D, tx(), new DeadLetterStore(D, tx()), new AuditChain(D));
    }

    @AfterEach
    void tearDown() throws SQLException {
        keepAlive.close();
    }

    Transactions tx() {
        return new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.apply(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    static InboundTenantAdmission.Message message(String id, String stored) {
        return new InboundTenantAdmission.Message("payments", "evt:payments", id, "PaymentAck", "BK-1", stored);
    }

    InboundTenantGate gate() {
        return new InboundTenantGate(() -> cells, () -> poison);
    }

    <T> List<T> query(String sql, Jdbc.RowMapper<T> m, Object... args) {
        return tx().inTransaction(c -> Jdbc.query(c, sql, m, args));
    }

    @Test
    void anActiveTenantIsAdmitted() {
        assertEquals(Verdict.ADMIT, gate().admit(TENANT, message("m-1", "{}")));
    }

    @Test
    void frozenAndImportingTenantsAreRedeliveredAndNothingIsWritten() {
        for (String s : List.of("FROZEN", "IMPORTING")) {
            states.put(TENANT, s);
            assertEquals(Verdict.REDELIVER, gate().admit(TENANT, message("m-" + s, "{}")), s);
        }
        assertTrue(query("SELECT id FROM RECONCILIATION_ITEM WHERE tenant_id=?", rs -> rs.getLong(1), TENANT).isEmpty());
    }

    @Test
    void aMovedTenantsMessageIsParkedWholeThenAcknowledged() {
        states.put(TENANT, "MOVED");
        String stored = "{\"channel\":\"payments\",\"payload\":{\"note\":\"" + "x".repeat(70 * 1024) + "\"}}";
        assertEquals(Verdict.PARKED, gate().admit(TENANT, message("m-9", stored)));

        List<PoisonInbound.Item> open = poison.listOpen(TENANT);
        assertEquals(1, open.size());
        assertTrue(open.get(0).parked());
        PoisonInbound.Parked parked = poison.parked(open.get(0).itemId());
        assertEquals("MOVED", parked.tenantState());
        assertEquals("cell-b", parked.targetCell());
        assertEquals("PaymentAck", parked.eventType());
        assertEquals("BK-1", parked.correlationKey());
        assertEquals("m-9", parked.messageId());
        assertEquals("evt:payments", parked.source());
        assertEquals(stored, parked.message().payloadJson(), "the stored-event JSON is kept whole for the replay");
        assertEquals(1, query("SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getInt(1), TENANT, PoisonInbound.PARKED_EVENT).get(0));

        assertEquals(Verdict.PARKED, gate().admit(TENANT, message("m-9", stored)), "a redelivery after a lost ack parks again idempotently");
        assertEquals(1, query("SELECT COUNT(*) FROM RECONCILIATION_ITEM WHERE tenant_id=?", rs -> rs.getInt(1), TENANT).get(0));
    }

    @Test
    void aFailedParkIsRedelivered() {
        states.put(TENANT, "PURGED");
        Transactions broken = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new IllegalStateException("database down");
            }
        };
        PoisonInbound failing = new PoisonInbound(D, broken, new DeadLetterStore(D, broken), null);
        assertEquals(Verdict.REDELIVER, new InboundTenantGate(() -> cells, () -> failing).admit(TENANT, message("m-2", "{}")));
    }

    @Test
    void noParkStoreAFailingCheckOrNoMessageIdIsRedelivered() {
        states.put(TENANT, "MOVED");
        assertEquals(Verdict.REDELIVER, new InboundTenantGate(() -> cells, () -> null).admit(TENANT, message("m-3", "{}")));
        assertEquals(Verdict.REDELIVER, gate().admit(TENANT, message(null, "{}")));
        states.put(TENANT, "BROKEN");
        assertEquals(Verdict.REDELIVER, gate().admit(TENANT, message("m-4", "{}")));
    }

    @Test
    void withoutCellsEveryTenantIsAdmitted() {
        states.put(TENANT, "MOVED");
        assertEquals(Verdict.ADMIT, new InboundTenantGate(() -> null, () -> poison).admit(TENANT, message("m-5", "{}")));
    }
}
