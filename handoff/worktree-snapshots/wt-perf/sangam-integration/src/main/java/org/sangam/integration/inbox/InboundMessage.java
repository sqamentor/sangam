// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.util.Objects;

/**
 * A message as received from a channel, before it is stored in the inbox.
 *
 * @param messageId      the source's message id (dedup key within {@code (tenant, source)}); Kafka: topic-partition-offset
 *                       or the CloudEvents id; JMS: JMSMessageID or a business id; webhook: the event id
 * @param eventType      event definition key, e.g. {@code PaymentAcknowledgement}
 * @param correlationKey business correlation (business key, operationId); may be null for start events
 * @param claimedTenant  tenant claimed by the message (header/payload), checked against the {@link ChannelBinding}
 * @param payloadJson    payload JSON
 */
public record InboundMessage(String messageId, String eventType, String correlationKey, String claimedTenant,
                             String payloadJson) {
    public InboundMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(eventType, "eventType");
        if (messageId.isEmpty() || messageId.length() > 255) {
            throw new IllegalArgumentException("messageId length must be 1..255");
        }
    }
}
