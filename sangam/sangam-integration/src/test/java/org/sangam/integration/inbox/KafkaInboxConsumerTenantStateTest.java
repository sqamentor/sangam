// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.relay.TenantAdmission;

/**
 * M-008: a Kafka record of a tenant the admission answers REDELIVER for (a yes/no admission that says no; the cell's
 * FROZEN or IMPORTING) is not stored and not committed; the partition is rewound and paused (the existing back-pressure
 * path), so it is re-read later. Never dropped, never acked. An admitted tenant is stored and committed as before.
 * MOVED and PURGED tenants are parked and then acked (SL-142): see {@link InboxParkThenAckTest}.
 */
class KafkaInboxConsumerTenantStateTest {

    static final String TENANT = "T1";
    static final String TOPIC = "payments";
    static final TopicPartition TP = new TopicPartition(TOPIC, 0);
    static final ChannelBinding BINDING = ChannelBinding.of("kafka:payments", TENANT, "broker");

    H2InboxFixture db;
    final Map<String, Boolean> frozen = new ConcurrentHashMap<>();
    final AtomicInteger checks = new AtomicInteger();
    final TenantAdmission admission = tenant -> {
        checks.incrementAndGet();
        return !frozen.getOrDefault(tenant, false);
    };

    @BeforeEach
    void setUp() throws Exception {
        db = new H2InboxFixture("kafkaadmission");
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    KafkaInboxConsumer consumer(MockConsumer<String, String> mock, TenantAdmission a) {
        return new KafkaInboxConsumer(mock, List.of(TOPIC), BINDING, KafkaInboxConsumer.cloudEventsOrOffset("PaymentAck"),
                db.inbox, null, a);
    }

    static ConsumerRecord<String, String> record(long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "k" + offset, "{\"amount\":" + offset + "}");
    }

    @Test
    void aFrozenTenantsRecordIsNotStoredAndNotAcked() {
        frozen.put(TENANT, true);
        KafkaInboxConsumer c = consumer(new MockConsumer<>(OffsetResetStrategy.EARLIEST), admission);
        assertFalse(c.store(record(7)), "not admitted: the offset must not be committed");
        assertEquals(1, checks.get());
        assertTrue(db.stored(TENANT).isEmpty(), "nothing stored for a frozen tenant");

        frozen.put(TENANT, false);
        assertTrue(c.store(record(7)), "admitted again: the re-read record is stored");
        assertEquals(List.of("payments-0-7"), db.stored(TENANT));
    }

    @Test
    void aFailingAdmissionCheckRedeliversInsteadOfDropping() {
        KafkaInboxConsumer c = consumer(new MockConsumer<>(OffsetResetStrategy.EARLIEST), tenant -> {
            throw new IllegalStateException("tenant state store unavailable");
        });
        assertFalse(c.store(record(1)));
        assertTrue(db.stored(TENANT).isEmpty());
    }

    @Test
    void withoutAnAdmissionEveryTenantIsStoredAsBefore() {
        KafkaInboxConsumer c = consumer(new MockConsumer<>(OffsetResetStrategy.EARLIEST), null);
        assertTrue(c.store(record(3)));
        assertEquals(List.of("payments-0-3"), db.stored(TENANT));
    }

    @Test
    void aForeignTenantClaimIsStillRefusedByTheInboxAndDroppedAsBefore() {
        // the record claims another tenant (CloudEvents extension sangamtenant): receive() refuses it
        // (SecurityException) and the consumer drops it with a warning, as before M-008; admission is not asked
        KafkaInboxConsumer c = consumer(new MockConsumer<>(OffsetResetStrategy.EARLIEST), admission);
        ConsumerRecord<String, String> foreign = record(4);
        foreign.headers().add("ce_id", "evt-4".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        foreign.headers().add("ce_type", "PaymentAck".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        foreign.headers().add("ce_sangamtenant", "T2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(c.store(foreign), "a foreign-tenant record is dropped (acked) as before");
        assertEquals(0, checks.get(), "the admission is not asked for a claim the binding refuses");
        assertTrue(db.stored("T2").isEmpty());
        assertTrue(db.stored(TENANT).isEmpty());
    }

    @Test
    void theConsumerLoopRewindsAndPausesThePartitionAndCommitsNothing() throws Exception {
        frozen.put(TENANT, true);
        MockConsumer<String, String> mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.schedulePollTask(() -> {
            mock.rebalance(List.of(TP));
            mock.updateBeginningOffsets(Map.of(TP, 0L));
            mock.addRecord(record(0));
            mock.addRecord(record(1));
        });
        KafkaInboxConsumer c = consumer(mock, admission);
        Thread t = new Thread(c, "kafka-inbox-test");
        t.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (checks.get() == 0 && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, checks.get(), "the first record was checked, the second not reached");
        Thread.sleep(50);
        synchronized (mock) {
            assertEquals(Set.of(TP), mock.paused(), "the partition is paused (back-pressure path)");
            assertEquals(0L, mock.position(TP), "rewound to the refused record");
            assertNull(mock.committed(Set.of(TP)).get(TP), "nothing committed for a frozen tenant");
        }
        c.close();
        t.join(5_000);
        assertTrue(db.stored(TENANT).isEmpty());
    }
}
