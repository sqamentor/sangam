// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka channel adapters (optional dependency {@code org.apache.kafka:kafka-clients}, Apache-2.0).
 *
 * <p>Inbound: manual offset commit after the event is in the inbox (at-least-once into an idempotent inbox; message
 * id = {@code message-id} header, else {@code topic-partition-offset}). A failure seeks back to the failed record and
 * backs off. Outbound: idempotent producer, {@code acks=all}, synchronous send so the relay marks the delivery done
 * only after the broker acknowledged it. Channel properties starting with {@code kafka.} are passed to the client
 * (e.g. {@code kafka.security.protocol}); secrets come from {@code secretRef}-style references only.
 */
final class KafkaAdapters {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdapters.class);

    private KafkaAdapters() {
    }

    static Properties base(ChannelDefinition ch) {
        Properties p = new Properties();
        String servers = ch.properties().get("bootstrapServers");
        if (servers == null) {
            throw new IllegalArgumentException("kafka channel " + ch.key() + " needs properties.bootstrapServers");
        }
        p.put("bootstrap.servers", servers);
        ch.properties().forEach((k, v) -> {
            if (k.startsWith("kafka.")) {
                p.put(k.substring("kafka.".length()), v);
            }
        });
        return p;
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final KafkaProducer<String, String> producer;
        private final String topic;

        Outbound(ChannelDefinition ch) {
            this.ch = ch;
            Properties p = base(ch);
            p.put(ProducerConfig.ACKS_CONFIG, "all");
            p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
            p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            this.producer = new KafkaProducer<>(p);
            this.topic = ch.destination() != null ? ch.destination() : ch.topics().isEmpty() ? null : ch.topics().get(0);
            if (topic == null) {
                throw new IllegalArgumentException("kafka channel " + ch.key() + " needs destination or topics[0]");
            }
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) throws Exception {
            String key = ch.properties().getOrDefault("recordKey", "messageId").equals("tenant") ? tenantId : messageId;
            ProducerRecord<String, String> r = new ProducerRecord<>(topic, key, body);
            r.headers().add("message-id", messageId.getBytes(StandardCharsets.UTF_8));
            r.headers().add("event-key", eventKey.getBytes(StandardCharsets.UTF_8));
            if (tenantId != null) {
                r.headers().add("tenant-id", tenantId.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(r).get(Long.parseLong(ch.property("sendTimeoutMs", "30000")), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            producer.close(Duration.ofSeconds(10));
        }
    }

    static final class Inbound implements ChannelAdapters.InboundChannelAdapter, Runnable {
        private final ChannelDefinition ch;
        private final String tenant;
        private final InboundEventProcessor processor;
        private final AtomicBoolean running = new AtomicBoolean();
        private final Properties props;
        private Thread thread;
        private final java.util.function.Function<Properties, Consumer<String, String>> consumerFactory;
        private volatile Consumer<String, String> consumer;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env) {
            this(ch, tenant, processor, env, KafkaConsumer::new);
        }

        /** {@code consumerFactory}: the Kafka client to use (a real {@link KafkaConsumer} in production). */
        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env,
                java.util.function.Function<Properties, Consumer<String, String>> consumerFactory) {
            this.consumerFactory = consumerFactory;
            this.ch = ch;
            this.tenant = tenant;
            this.processor = processor;
            this.props = base(ch);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, ch.property("groupId", "sangam-" + tenant + "-" + ch.key()));
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, ch.property("autoOffsetReset", "earliest"));
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, ch.property("maxPollRecords", "100"));
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, env.nodeId() + "-" + ch.key());
        }

        @Override
        public void start() {
            if (running.compareAndSet(false, true)) {
                thread = new Thread(this, "sangam-kafka-" + ch.key());
                thread.setDaemon(true);
                thread.start();
            }
        }

        @Override
        public void run() {
            try (Consumer<String, String> c = consumerFactory.apply(props)) {
                consumer = c;
                c.subscribe(ch.topics());
                while (running.get()) {
                    for (ConsumerRecord<String, String> rec : c.poll(Duration.ofSeconds(1))) {
                        if (!running.get()) {
                            break;
                        }
                        TopicPartition tp = new TopicPartition(rec.topic(), rec.partition());
                        try {
                            Map<String, String> headers = new HashMap<>();
                            for (Header h : rec.headers()) {
                                headers.put(h.key(), h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8));
                            }
                            String id = headers.getOrDefault("message-id", rec.topic() + "-" + rec.partition() + "-" + rec.offset());
                            processor.receive(tenant, ch.key(), rec.value(), headers, id);
                            c.commitSync(Map.of(tp, new OffsetAndMetadata(rec.offset() + 1)));
                        } catch (RuntimeException e) {
                            LOG.warn("kafka channel {}: record {}-{}@{} not stored, retrying: {}", ch.key(), rec.topic(),
                                    rec.partition(), rec.offset(), e.toString());
                            c.seek(tp, rec.offset());
                            sleep(Long.parseLong(ch.property("retryBackoffMs", "5000")));
                            break;
                        }
                    }
                }
            } catch (org.apache.kafka.common.errors.WakeupException e) {
                // closing
            } finally {
                consumer = null;
            }
        }

        @Override
        public void close() {
            running.set(false);
            Consumer<String, String> c = consumer;
            if (c != null) {
                c.wakeup();
            }
            if (thread != null) {
                try {
                    thread.join(15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
