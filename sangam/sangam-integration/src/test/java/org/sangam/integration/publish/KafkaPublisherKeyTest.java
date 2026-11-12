// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Outbox;

/**
 * M-057: the history topic keys each record by its outbox event id (one record per event under compaction or an
 * upserting consumer, whatever the redeliveries); every other Kafka destination keeps the per-root key and so its
 * per-root partition order.
 */
class KafkaPublisherKeyTest {

    static final UUID ID = UUID.fromString("5f0c2b9e-1d3a-4c11-8e7a-2b6f4d9c0e01");
    static final Outbox.OutboxEvent E = new Outbox.OutboxEvent(1L, ID, "T1", "pi-9", 4L, "sangam.history.task.create", "{}");

    @Test
    void theDefaultKeyIsTheRootAndTheHistoryKeyIsTheEventId() {
        assertEquals("T1:pi-9", KafkaPublisher.recordKey(KafkaPublisher.Key.ROOT, E));
        assertEquals(ID.toString(), KafkaPublisher.recordKey(KafkaPublisher.Key.EVENT_ID, E));
    }
}
