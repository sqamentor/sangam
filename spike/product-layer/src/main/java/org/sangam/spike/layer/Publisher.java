package org.sangam.spike.layer;

/**
 * Pluggable transport for the relay (Kafka, webhook, history projector, wake-up ...). Called outside any database
 * transaction. Delivery is at-least-once: a publisher may see the same {@code eventId} more than once and consumers
 * must dedup on it (VI.2.3 rule 4). Throwing marks the attempt failed.
 */
@FunctionalInterface
public interface Publisher {
    void publish(String destination, Outbox.OutboxEvent event) throws Exception;
}
