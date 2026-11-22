// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.jms.TextMessage;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.relay.TenantAdmission;

/**
 * SL-142 (owner decision 2026-11-12): for a tenant that left the cell for good (MOVED or PURGED) the Kafka and JMS
 * inbox consumers park the message (dead letter with the full message plus a reconciliation item, so an operator can
 * replay it to the tenant's new cell) and only then ack. FROZEN and IMPORTING messages stay not acked and are
 * redelivered. A park that cannot be written is never acked.
 */
class InboxParkThenAckTest {

    static final String TENANT = "T1";
    static final String TOPIC = "payments";
    static final TopicPartition TP = new TopicPartition(TOPIC, 0);
    static final ChannelBinding KAFKA = ChannelBinding.of("kafka:payments", TENANT, "broker");
    static final ChannelBinding JMS = ChannelBinding.of("jms:payments", TENANT, "broker");

    H2InboxFixture db;
    PoisonInbound poison;
    /** tenant → cell tenant state name, as CellTenantStateStore.State would give it; absent = ACTIVE. */
    final Map<String, String> states = new ConcurrentHashMap<>();
    /** Mirrors the cell rule: ACTIVE admitted, MOVED/PURGED parked, every other state redelivered. */
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
                default -> Decision.REDELIVER;
            };
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        db = new H2InboxFixture("inboxpark");
        poison = new PoisonInbound(H2InboxFixture.D, db.tx(), new DeadLetterStore(H2InboxFixture.D, db.tx()),
                new AuditChain(H2InboxFixture.D));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    KafkaInboxConsumer kafka(MockConsumer<String, String> mock, PoisonInbound p) {
        return new KafkaInboxConsumer(mock, List.of(TOPIC), KAFKA, KafkaInboxConsumer.cloudEventsOrOffset("PaymentAck"),
                db.inbox, p, cells);
    }

    JmsInboxListener jms(PoisonInbound p) {
        return new JmsInboxListener(null, s -> null, JMS, JmsInboxListener.cloudEventsOrJmsId("PaymentAck"), db.inbox, p, cells);
    }

    static ConsumerRecord<String, String> record(long offset, String body) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "k" + offset, body);
    }

    static TextMessage text(String id, String body) {
        return (TextMessage) Proxy.newProxyInstance(InboxParkThenAckTest.class.getClassLoader(),
                new Class<?>[]{TextMessage.class}, (p, m, args) -> switch (m.getName()) {
                    case "getPropertyNames" -> Collections.emptyEnumeration();
                    case "getJMSMessageID" -> id;
                    case "getJMSCorrelationID" -> "corr-" + id;
                    case "getText" -> body;
                    case "toString" -> "TextMessage(" + id + ")";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> null;
                });
    }

    /** A body larger than the 64 KiB poison truncation, to prove a parked message is kept whole. */
    static String largeBody() {
        return "{\"note\":\"" + "x".repeat(70 * 1024) + "\"}";
    }

    int auditCount(String eventType) {
        return db.tx().inTransaction(c -> Jdbc.one(c, "SELECT COUNT(*) FROM AUDIT_EVENT WHERE tenant_id=? AND event_type=?",
                rs -> rs.getInt(1), TENANT, eventType).orElse(0));
    }

    String deadLetterState(long id) {
        return db.tx().inTransaction(c -> Jdbc.one(c, "SELECT state FROM INTEGRATION_DEAD_LETTER WHERE id=?",
                rs -> rs.getString(1), id).orElseThrow());
    }

    // ---------------------------------------------------------------- Kafka

    @Test
    void aMovedTenantsKafkaRecordIsParkedWholeAndThenAcked() {
        states.put(TENANT, "MOVED");
        String body = largeBody();
        KafkaInboxConsumer c = kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison);
        assertTrue(c.store(record(5, body)), "parked: the offset may be committed");
        assertTrue(db.stored(TENANT).isEmpty(), "a parked record is not stored in this cell's inbox");

        List<PoisonInbound.Item> open = poison.listOpen(TENANT);
        assertEquals(1, open.size());
        assertTrue(open.get(0).parked());
        assertTrue(open.get(0).error().startsWith(PoisonInbound.PARKED_PREFIX + "MOVED"));
        PoisonInbound.Parked p = poison.parked(open.get(0).itemId());
        assertEquals(TENANT, p.tenantId());
        assertEquals("kafka:payments", p.source());
        assertEquals("payments-0-5", p.messageId());
        assertEquals("PaymentAck", p.eventType());
        assertEquals("k5", p.correlationKey());
        assertEquals(body, p.payloadJson(), "the full message is kept (the broker no longer holds it)");
        assertEquals("MOVED", p.tenantState());
        assertEquals("cell-b", p.targetCell());
        assertEquals("mv-1", p.moveId());
        assertEquals(1, auditCount(PoisonInbound.PARKED_EVENT));
    }

    @Test
    void aPurgedTenantsRecordRedeliveredAfterALostAckIsParkedOnce() {
        states.put(TENANT, "PURGED");
        KafkaInboxConsumer c = kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison);
        assertTrue(c.store(record(2, "{\"a\":1}")));
        assertTrue(c.store(record(2, "{\"a\":1}")), "the redelivered record is acked again");
        assertEquals(1, poison.listOpen(TENANT).size(), "one dead letter and one item per message");
        assertEquals("PURGED", poison.parked(poison.listOpen(TENANT).get(0).itemId()).tenantState());
        assertEquals(1, auditCount(PoisonInbound.PARKED_EVENT), "audited once");
    }

    @Test
    void frozenAndImportingKafkaRecordsAreStillNotAckedAndNotParked() {
        KafkaInboxConsumer c = kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison);
        for (String s : List.of("FROZEN", "IMPORTING")) {
            states.put(TENANT, s);
            assertFalse(c.store(record(1, "{\"a\":1}")), s + ": not acked, re-read later");
        }
        assertTrue(poison.listOpen(TENANT).isEmpty(), "nothing parked for a tenant that will come back");
        assertTrue(db.stored(TENANT).isEmpty());
        states.put(TENANT, "ACTIVE");
        assertTrue(c.store(record(1, "{\"a\":1}")));
        assertEquals(List.of("payments-0-1"), db.stored(TENANT));
    }

    @Test
    void withoutADeadLetterStoreAMovedRecordIsNotAcked() {
        states.put(TENANT, "MOVED");
        KafkaInboxConsumer c = kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), null);
        assertFalse(c.store(record(3, "{\"a\":1}")), "no park possible: never ack, never drop");
        assertTrue(db.stored(TENANT).isEmpty());
    }

    @Test
    void aFailedParkWriteIsNotAcked() {
        states.put(TENANT, "MOVED");
        Transactions broken = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new IllegalStateException("database down");
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                throw new IllegalStateException("database down");
            }
        };
        PoisonInbound failing = new PoisonInbound(H2InboxFixture.D, broken, new DeadLetterStore(H2InboxFixture.D, broken), null);
        KafkaInboxConsumer c = kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), failing);
        assertFalse(c.store(record(4, "{\"a\":1}")), "the park did not commit: the record must be re-read");
        assertTrue(poison.listOpen(TENANT).isEmpty());
    }

    @Test
    void theConsumerLoopCommitsTheParkedRecordAndDoesNotPause() throws Exception {
        states.put(TENANT, "MOVED");
        MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.schedulePollTask(() -> {
            mock.rebalance(List.of(TP));
            mock.updateBeginningOffsets(Map.of(TP, 0L));
            mock.addRecord(record(0, "{\"a\":0}"));
            mock.addRecord(record(1, "{\"a\":1}"));
        });
        KafkaInboxConsumer c = kafka(mock, poison);
        Thread t = new Thread(c, "kafka-park-test");
        t.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (poison.listOpen(TENANT).size() < 2 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        Thread.sleep(50);
        synchronized (mock) {
            assertEquals(2L, mock.committed(Set.of(TP)).get(TP).offset(), "both parked records committed");
            assertTrue(mock.paused().isEmpty(), "parking is not back-pressure");
        }
        c.close();
        t.join(5_000);
        assertEquals(2, poison.listOpen(TENANT).size());
        assertTrue(db.stored(TENANT).isEmpty());
    }

    // ---------------------------------------------------------------- JMS

    @Test
    void aMovedTenantsJmsMessageIsParkedAndTheSessionCommits() throws Exception {
        states.put(TENANT, "MOVED");
        String body = largeBody();
        JmsInboxListener l = jms(poison);
        assertTrue(l.store(text("ID:7", body)), "parked: the session may commit (ack)");
        assertTrue(db.stored(TENANT).isEmpty());
        PoisonInbound.Parked p = poison.parked(poison.listOpen(TENANT).get(0).itemId());
        assertEquals("jms:payments", p.source());
        assertEquals("ID:7", p.messageId());
        assertEquals("corr-ID:7", p.correlationKey());
        assertEquals(body, p.payloadJson());
        assertEquals("cell-b", p.targetCell());
    }

    @Test
    void frozenAndImportingJmsMessagesRollBackAndAreNotParked() throws Exception {
        JmsInboxListener l = jms(poison);
        for (String s : List.of("FROZEN", "IMPORTING")) {
            states.put(TENANT, s);
            assertFalse(l.store(text("ID:8", "{\"a\":1}")), s + ": rolled back for redelivery");
        }
        assertTrue(poison.listOpen(TENANT).isEmpty());
        assertTrue(db.stored(TENANT).isEmpty());
    }

    @Test
    void withoutADeadLetterStoreAMovedJmsMessageRollsBack() throws Exception {
        states.put(TENANT, "PURGED");
        assertFalse(jms(null).store(text("ID:9", "{\"a\":1}")));
        assertTrue(db.stored(TENANT).isEmpty());
    }

    // ---------------------------------------------------------------- operator replay

    @Test
    void anOperatorReplaysAParkedMessageToTheNewCellAndTheItemCloses() {
        states.put(TENANT, "MOVED");
        assertTrue(kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison).store(record(6, "{\"a\":6}")));
        long item = poison.listOpen(TENANT).get(0).itemId();
        long deadLetter = poison.listOpen(TENANT).get(0).deadLetterId();
        List<PoisonInbound.Parked> sent = new CopyOnWriteArrayList<>();
        poison.replayParked(item, sent::add, "ops-1", "tenant now in cell-b");

        assertEquals(1, sent.size());
        InboundMessage m = sent.get(0).message();
        assertEquals("payments-0-6", m.messageId(), "the original id: the new cell's inbox deduplicates a repeat");
        assertEquals("PaymentAck", m.eventType());
        assertEquals(TENANT, m.claimedTenant());
        assertEquals("{\"a\":6}", m.payloadJson());
        assertEquals("cell-b", sent.get(0).targetCell());
        assertTrue(poison.listOpen(TENANT).isEmpty(), "the item is resolved");
        assertEquals("REQUEUED", deadLetterState(deadLetter));
        assertEquals(1, auditCount("POISON_INBOUND_RESOLVED"));
        assertThrows(IllegalStateException.class, () -> poison.parked(item), "a resolved item cannot be replayed again");
    }

    @Test
    void aFailedReplayLeavesTheItemOpen() {
        states.put(TENANT, "MOVED");
        assertTrue(kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison).store(record(7, "{\"a\":7}")));
        long item = poison.listOpen(TENANT).get(0).itemId();
        assertThrows(IllegalStateException.class, () -> poison.replayParked(item, p -> {
            throw new java.io.IOException("cell-b unreachable");
        }, "ops-1", "retry"));
        assertEquals(1, poison.listOpen(TENANT).size(), "still open for another replay");
        assertThrows(IllegalArgumentException.class, () -> poison.replayParked(item, p -> { }, "ops-1", " "),
                "a reason is required");
    }

    @Test
    void aParkedItemIsNeverReprocessedIntoThisCellsInbox() {
        states.put(TENANT, "MOVED");
        assertTrue(kafka(new MockConsumer<>(OffsetResetStrategy.EARLIEST), poison).store(record(8, "{\"a\":8}")));
        long item = poison.listOpen(TENANT).get(0).itemId();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> poison.reprocess(db.inbox, item, "PaymentAck", null, "{\"a\":8}", "ops-1", "try here"));
        assertTrue(e.getMessage().startsWith("409 PARKED"));
        assertTrue(db.stored(TENANT).isEmpty());
        poison.discard(item, "ops-1", "sender notified");
        assertTrue(poison.listOpen(TENANT).isEmpty(), "discard still closes a parked item");
    }

    @Test
    void aPoisonItemIsNotAParkedItem() {
        long item = poison.record(KAFKA, "m-1", null, "not json", "unmappable record");
        assertFalse(poison.listOpen(TENANT).get(0).parked());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> poison.parked(item));
        assertTrue(e.getMessage().startsWith("409 NOT_PARKED"));
    }

    // ---------------------------------------------------------------- the decision type

    @Test
    void anAdmissionThatOnlyAnswersAdmitsNeverParks() {
        TenantAdmission legacy = tenant -> false;
        assertSame(TenantAdmission.Decision.REDELIVER, legacy.decide("T9"));
        assertSame(TenantAdmission.Decision.ADMIT, TenantAdmission.ALL.decide("T9"));
        assertThrows(IllegalArgumentException.class, () -> TenantAdmission.Decision.park(" ", null, null),
                "a park names the tenant state");
        TenantAdmission.Decision d = TenantAdmission.Decision.park("MOVED", null, null);
        assertEquals(TenantAdmission.Action.PARK, d.action());
        assertNull(d.targetCellOrNull());
        assertThrows(IllegalArgumentException.class, () -> poison.park(KAFKA, TENANT,
                new InboundMessage("m-2", "PaymentAck", null, null, "{}"), TenantAdmission.Decision.REDELIVER));
    }
}
