// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import java.sql.Connection;

/**
 * Processes one inbox message inside the processing transaction (VI.2.6 rule 2). WS-PORTS' event registry registers
 * handlers per event type; the default is {@link CorrelatingInboxHandler}.
 */
@FunctionalInterface
public interface InboxHandler {

    enum Disposition {
        /** The effect is done; the message becomes PROCESSED in the same transaction. */
        PROCESSED,
        /** No matching subscription yet: BUFFERED, re-polled until the TTL. */
        NO_SUBSCRIPTION,
        /** Policy {@code reject}: expire now (audited, dead-lettered). */
        REJECTED
    }

    /** The stored message plus its decoded envelope. */
    record Envelope(String tenantId, String source, String messageId, String eventType, String correlationKey,
                    String principal, String dataJson) {
    }

    Disposition handle(Connection c, Envelope message);
}
