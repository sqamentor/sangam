// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PermanentPublishException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Kafka publisher (R71 "Kafka"; Flowable FC-03 outbound channel). Optional: needs {@code kafka-clients} (Apache-2.0)
 * on the class path.
 *
 * <ul>
 *   <li>Idempotent producer ({@code enable.idempotence=true}, {@code acks=all}), so broker-side retries do not
 *       duplicate; relay-level redelivery can, and consumers dedup on the {@code ce_id} header (VI.2.3 rule 4).</li>
 *   <li>Record key {@code tenant:rootId}: all events of one root go to one partition, preserving per-root order for
 *       ordered destinations.</li>
 *   <li>CloudEvents Kafka binding, binary mode: {@code ce_*} headers, value = payload JSON.</li>
 *   <li>The send is synchronous with a timeout (the relay marks DONE only after the broker acknowledged).</li>
 *   <li>Unknown topic, authorization, too large, serialization → {@link PermanentPublishException}.</li>
 * </ul>
 */
public final class KafkaPublisher implements Publisher, AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final Function<String, String> topicOf;
    private final String source;
    private final Duration sendTimeout;

    /**
     * @param config  producer properties ({@code bootstrap.servers}, security settings); idempotence and acks are forced
     * @param topicOf destination → topic (e.g. {@code kafka:payments} → {@code payments})
     */
    public KafkaPublisher(Map<String, Object> config, Function<String, String> topicOf, String source, Duration sendTimeout) {
        Properties p = new Properties();
        p.putAll(config);
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.putIfAbsent(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Math.max(sendTimeout.toMillis(), 30_000));
        p.putIfAbsent(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(sendTimeout.toMillis(), 30_000));
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(p);
        this.topicOf = topicOf;
        this.source = source;
        this.sendTimeout = sendTimeout;
    }

    /** Default mapping: strips a {@code kafka:} prefix. */
    public static Function<String, String> stripPrefix() {
        return d -> d.startsWith("kafka:") ? d.substring("kafka:".length()) : d;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        String topic = topicOf.apply(destination);
        if (topic == null || topic.isBlank()) {
            throw new PermanentPublishException("no Kafka topic for destination " + destination);
        }
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, event.tenantId() + ":" + event.rootId(), event.payload());
        CloudEvents.attributes(source, destination, event)
                .forEach((k, v) -> rec.headers().add(new RecordHeader("ce_" + k, v.getBytes(StandardCharsets.UTF_8))));
        try {
            producer.send(rec).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UnknownTopicOrPartitionException || c instanceof InvalidTopicException
                    || c instanceof AuthorizationException || c instanceof RecordTooLargeException
                    || c instanceof SerializationException) {
                throw new PermanentPublishException("Kafka rejected event for " + topic + ": " + c.getMessage(), c);
            }
            throw new IllegalStateException("Kafka send failed: " + (c == null ? e.getMessage() : c.getMessage()), c);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(10));
    }
}
