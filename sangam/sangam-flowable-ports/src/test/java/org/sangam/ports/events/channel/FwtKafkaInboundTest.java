// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.sangam.ports.fwt.events.FwtEventsFixture;

/**
 * FW-28 / FW-02 scenarios on the Kafka inbound adapter's real consume loop ({@link KafkaAdapters.Inbound}), driven by
 * kafka-clients' {@link MockConsumer} (no broker): offsets, seek-back and the processor's REJECTED results.
 */
class FwtKafkaInboundTest {

    private static final String TOPIC = "orders";
    private static final TopicPartition TP = new TopicPartition(TOPIC, 0);

    private FwtEventsFixture f;
    private ReplayingConsumer mock;
    private final List<ConsumerRecord<String, String>> partitionLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * MockConsumer drops a record once polled; a broker re-reads from the position after {@code seek}. This consumer
     * re-offers the partition log from the sought offset, as Kafka does.
     */
    private final class ReplayingConsumer extends MockConsumer<String, String> {
        ReplayingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void seek(TopicPartition partition, long offset) {
            super.seek(partition, offset);
            for (ConsumerRecord<String, String> r : partitionLog) {
                if (r.offset() >= offset) {
                    addRecord(r);
                }
            }
        }
    }
    private KafkaAdapters.Inbound inbound;

    private static final ChannelAdapters.Environment ENV = new ChannelAdapters.Environment() {
        @Override
        public Optional<String> secret(String ref) {
            return Optional.of("s3cret");
        }

        @Override
        public Set<String> egressHosts() {
            return Set.of();
        }
    };

    @BeforeEach
    void setUp() {
        f = new FwtEventsFixture();
        f.registry.deployEvent("acc", "{\"key\":\"order\",\"payload\":[{\"name\":\"qty\",\"type\":\"integer\",\"required\":true}]}", null);
        mock = new ReplayingConsumer();
        mock.updateBeginningOffsets(Map.of(TP, 0L));
    }

    @AfterEach
    void tearDown() {
        if (inbound != null) {
            inbound.close();
        }
        f.close();
    }

    private void start() {
        ChannelDefinition ch = f.registry.deployChannel("acc", "{\"key\":\"kin\",\"channelType\":\"kafka\",\"type\":\"inbound\","
                + "\"topics\":[\"" + TOPIC + "\"],\"channelEventKeyDetection\":{\"fixedValue\":\"order\"},"
                + "\"properties\":{\"bootstrapServers\":\"mock:9092\",\"retryBackoffMs\":\"20\"}}", null).definition();
        inbound = new KafkaAdapters.Inbound(ch, "acc", f.processor, ENV, props -> mock);
        inbound.start();
    }

    private void produce(List<String> values) {
        mock.schedulePollTask(() -> {
            mock.rebalance(List.of(TP));
            long off = 0;
            for (String v : values) {
                ConsumerRecord<String, String> r = new ConsumerRecord<>(TOPIC, 0, off++, null, v);
                partitionLog.add(r);
                mock.addRecord(r);
            }
        });
    }

    private long committed() {
        Map<TopicPartition, OffsetAndMetadata> c = mock.committed(Set.of(TP));
        OffsetAndMetadata o = c.get(TP);
        return o == null ? -1 : o.offset();
    }

    private static void await(BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    // FWT-023
    @Test
    void fwt023_poisonRecordsAreRejectedCommittedAndTheNextRecordIsProcessed() throws Exception {
        produce(java.util.Arrays.asList(null, "[1,2,3]", "{\"qty\":\"abc\"}", "{\"qty\":5}"));
        start();
        await(() -> committed() == 4, 10_000, "offset 4 committed");
        assertEquals(3, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE channel_key = 'kin' AND outcome = 'REJECTED'"),
                "tombstone, array payload and wrong-type field: REJECTED");
        assertEquals(List.of(TOPIC + "-0-3"), f.strings("SELECT message_id FROM INBOX"), "the next record was processed");
    }

    // FWT-024
    @Test
    void fwt024_databaseDownDuringReceiveThrowsAndKafkaSeeksBackAndRetries() throws Exception {
        // the processor itself: an infrastructure failure is an exception, never a REJECTED result
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("probe", "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"}"), null);
        f.registry.channel("acc", "probe");   // cached before the outage, like a warm node
        f.productDs.down = true;
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> f.processor.receive("acc", "probe", "{\"id\":\"p1\",\"qty\":1}", Map.of(), null));
        assertTrue(hasSqlCause(e), "infrastructure failure surfaces: " + e);

        produce(List.of("{\"qty\":1}", "{\"qty\":2}"));
        start();
        Thread.sleep(500);
        assertEquals(-1, committed(), "nothing committed while the database is down");
        assertEquals(0, f.count("SELECT COUNT(*) FROM INBOX"));
        assertEquals(0, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE outcome = 'REJECTED'"), "not a poison message");
        f.productDs.down = false;
        await(() -> committed() == 2, 10_000, "offset 2 committed after recovery");
        assertEquals(List.of(TOPIC + "-0-0", TOPIC + "-0-1"), f.strings("SELECT message_id FROM INBOX ORDER BY message_id"),
                "the failed record was re-read (seek back) and stored once");
    }

    private static boolean hasSqlCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException) {
                return true;
            }
        }
        return false;
    }

    // FWT-052 (in-process part: Kafka consume loop with MockConsumer + webhook endpoint; no real Kafka/JMS broker)
    @Test
    void fwt052_soakOf10kMixedEventsLeavesNoStuckPartitionAndOneRejectedPerPoison() throws Exception {
        f.registry.deployChannel("acc", FwtEventsFixture.webhookChannel("hook", "\"channelEventKeyDetection\":{\"fixedValue\":\"order\"}"), null);
        WebhookInboundEndpoint webhook = new WebhookInboundEndpoint(f.registry, f.processor, ENV);
        String[] poison = {null, "[1]", "{\"qty\":\"x\"}", "{", "{\"qty\":" + "9".repeat(1000) + "}"};
        int kafkaN = 5_000;
        int kafkaPoison = 0;
        List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < kafkaN; i++) {
            if (i % 5 == 0) {
                values.add(poison[(i / 5) % poison.length]);
                kafkaPoison++;
            } else {
                values.add("{\"qty\":" + i + "}");
            }
        }
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapBefore = rt.totalMemory() - rt.freeMemory();
        produce(values);
        start();

        int hookPoison = 0;
        for (int i = 0; i < 5_000; i++) {
            Map<String, String> h = new HashMap<>();
            h.put("X-Sangam-Message-Id", "w" + i);
            boolean bad = i % 5 == 0;
            String body = bad ? poison[1 + (i / 5) % (poison.length - 1)] : "{\"qty\":" + i + "}";
            InboundEventProcessor.Result r = webhook.receive("acc", "hook", h, body);
            assertEquals(bad ? InboundEventProcessor.Outcome.REJECTED : InboundEventProcessor.Outcome.ACCEPTED, r.outcome(),
                    "webhook " + i + ": " + r);
            if (bad) {
                hookPoison++;
            }
        }
        await(() -> committed() == kafkaN, 120_000, "kafka partition drained (no stuck partition)");
        assertEquals(kafkaPoison, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE channel_key = 'kin' AND outcome = 'REJECTED'"));
        assertEquals(hookPoison, f.count("SELECT COUNT(*) FROM EVT_LOG WHERE channel_key = 'hook' AND outcome = 'REJECTED'"));
        assertEquals(kafkaN - kafkaPoison + 5_000 - hookPoison, f.count("SELECT COUNT(*) FROM INBOX"));
        System.gc();
        long heapAfter = rt.totalMemory() - rt.freeMemory();
        long growthMb = (heapAfter - heapBefore) / (1024 * 1024);
        // the in-memory H2 database holds the 10k inbox and log rows on this heap; beyond that nothing may accumulate
        assertTrue(growthMb < 256, "heap growth " + growthMb + " MiB (" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage() + ")");
    }
}
