// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Inbox;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Schema;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.schema.IntegrationSchema;

/**
 * SL-55 on H2 (PostgreSQL mode): a deterministic business refusal raised while an inbox message is processed (the
 * pack's {@code 422 REASONS_REQUIRED} from CaseRecordDelegate, thrown inside the engine correlation, rolls the
 * processing transaction back) used to leave the row BUFFERED and retry it every {@code bufferRetry} until the TTL:
 * 14 identical failures in one IfmsPacksIT run and the case never left EXECUTION. Now the poison message is parked
 * after the first failure (EXPIRED, dead letter with the code, {@code INBOX_MESSAGE_PARKED} on the tenant's security
 * chain) and never handed to the handler again; a transient failure still stays BUFFERED for a retry.
 */
class InboxServiceBusinessRefusalTest {

    static final Dialect D = Dialect.POSTGRESQL;
    static final String T = "T1";
    static final String SOURCE = "sangam:packs";
    static final String EVENT = "sangam.operationResult";

    String url;
    Connection keepAlive;

    @BeforeEach
    void schema() throws Exception {
        url = "jdbc:h2:mem:inboxrefusal" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
        Schema.create(keepAlive, D);
        IntegrationSchema.create(keepAlive, D);
    }

    @AfterEach
    void close() throws Exception {
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
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                try (Connection c = DriverManager.getConnection(url, "sa", "")) {
                    c.setAutoCommit(false);
                    try {
                        T r = work.read(c);
                        c.commit();
                        return r;
                    } catch (RuntimeException e) {
                        c.rollback();
                        throw e;
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** An inbox service whose only handler throws what {@code failure} supplies, counting its invocations. */
    InboxService service(AtomicInteger calls, Supplier<RuntimeException> failure) {
        Transactions tx = tx();
        InboxHandler handler = (c, m) -> {
            calls.incrementAndGet();
            // a write in the processing transaction, as the engine correlation makes: it must roll back
            Jdbc.update(c, "UPDATE INBOX SET correlation_key='touched' WHERE message_id=?", m.messageId());
            throw failure.get();
        };
        // bufferRetry 0: a buffered row is due again at once, so a retry loop would show up on the next cycle
        InboxService.Settings settings = new InboxService.Settings("node-1", 10, Duration.ofMinutes(1), Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(30));
        return new InboxService(D, tx, settings, new AuditChain(D), new EventDefinitionPolicy.Registry(), handler,
                BackpressureGate.open(), new DeadLetterStore(D, tx));
    }

    void receive(InboxService s, String messageId) {
        s.receive(ChannelBinding.of(SOURCE, T, "system"),
                new InboundMessage(messageId, EVENT, "op-1", null, "{\"resultStatus\":\"FAILED\"}"));
    }

    Inbox.State state(String messageId) {
        return tx().inTransaction(c -> Inbox.state(c, T, SOURCE, messageId));
    }

    List<String> deadLetterErrors() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT error FROM INTEGRATION_DEAD_LETTER WHERE kind='INBOX' AND state='OPEN'",
                rs -> rs.getString(1)));
    }

    List<String> securityAudit() {
        return tx().inTransaction(c -> Jdbc.query(c, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq",
                rs -> rs.getString(1), T, "sec:" + T));
    }

    @Test
    void aPoisonMessageWithABusinessRefusalIsParkedOnceAndNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        // the engine wraps the delegate's refusal: the code is found in the cause chain
        InboxService s = service(calls, () -> new IllegalStateException("correlation failed",
                new ProcessEngineException("422 REASONS_REQUIRED: a rejection needs at least one coded reason")));
        receive(s, "m-poison");

        assertEquals(0, s.processOnce());
        assertEquals(1, calls.get());
        assertEquals(Inbox.State.EXPIRED, state("m-poison"));
        List<String> dead = deadLetterErrors();
        assertEquals(1, dead.size(), dead.toString());
        assertTrue(dead.get(0).startsWith("PERMANENT REASONS_REQUIRED"), dead.get(0));
        assertEquals(List.of("INBOX_MESSAGE_PARKED"), securityAudit());
        // the handler's own write rolled back with the processing transaction
        assertEquals("op-1", tx().inTransaction(c -> Jdbc.one(c, "SELECT correlation_key FROM INBOX WHERE message_id=?",
                rs -> rs.getString(1), "m-poison").orElseThrow()));

        for (int i = 0; i < 5; i++) {
            assertEquals(0, s.processOnce());
        }
        assertEquals(1, calls.get(), "a parked message is never handed to the handler again");
        // the maintenance pass does not dead-letter it a second time
        s.maintain();
        assertEquals(1, deadLetterErrors().size());
        assertEquals(List.of("INBOX_MESSAGE_PARKED"), securityAudit());
    }

    @Test
    void aTransientFailureStaysBufferedForRetry() {
        AtomicInteger calls = new AtomicInteger();
        InboxService s = service(calls, () -> new IllegalStateException("connection reset by peer"));
        receive(s, "m-transient");

        s.processOnce();
        assertEquals(Inbox.State.BUFFERED, state("m-transient"));
        s.processOnce();
        assertEquals(2, calls.get(), "a transient failure is retried");
        assertEquals(Inbox.State.BUFFERED, state("m-transient"));
        assertTrue(deadLetterErrors().isEmpty());
        assertTrue(securityAudit().isEmpty());
    }

    @Test
    void aConcurrencyConflictIsNotABusinessRefusal() {
        AtomicInteger calls = new AtomicInteger();
        InboxService s = service(calls, () -> new IllegalStateException("409 STALE_LEASE: lease moved on"));
        receive(s, "m-409");

        s.processOnce();
        assertEquals(Inbox.State.BUFFERED, state("m-409"));
        assertTrue(deadLetterErrors().isEmpty());
    }

    @Test
    void onlyA422CodeAnywhereInTheCauseChainClassifiesAsABusinessRefusal() {
        assertEquals("REASONS_REQUIRED", InboxService.businessRefusal(
                new ProcessEngineException("422 REASONS_REQUIRED: a rejection needs at least one coded reason")));
        assertEquals("SOD_VIOLATION", InboxService.businessRefusal(new RuntimeException("wrapped",
                new RuntimeException("inner", new ProcessEngineException("422 SOD_VIOLATION: p / seat")))));
        assertEquals("RETRY_NOT_ALLOWED", InboxService.businessRefusal(
                new IllegalArgumentException("422 RETRY_NOT_ALLOWED: class (c) operations are never repeated")));
        assertNull(InboxService.businessRefusal(new RuntimeException("409 STALE_LEASE: lease moved on")));
        assertNull(InboxService.businessRefusal(new RuntimeException("429 QUOTA_EXCEEDED: try later")));
        assertNull(InboxService.businessRefusal(new RuntimeException("deadlock detected")));
        assertNull(InboxService.businessRefusal(new RuntimeException((String) null)));
        assertNull(InboxService.businessRefusal(new RuntimeException("4220 NOT_A_STATUS")));
    }
}
