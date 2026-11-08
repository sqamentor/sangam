// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import org.sangam.core.layer.Inbox;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.gov.BankAcknowledgementFeed;
import org.sangam.integration.connector.gov.PaymentAcknowledgement;
import org.sangam.integration.relay.BackpressureGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bank / treasury acknowledgements into the inbox (IX.4 P1 "payment instruction → bank/treasury acknowledgement →
 * reconciliation", P3 DBT, P4 fee callbacks; R58). Each {@link PaymentAcknowledgement} from the PFMS status files,
 * the IFMS failure feed (FD) or a verified payment-gateway callback is stored with source = the feed, messageId = the
 * feed's stable id and correlation key = the operationId, as event type {@value #EVENT_TYPE}. The default
 * correlating handler delivers it to the instance waiting on message {@value #EVENT_TYPE} for that operation (the
 * BPMN waits with correlation variable {@code sangamCorrelationKey = operationId}); an early acknowledgement is
 * buffered (FLOWABLE-INTO-OPERATON D-33).
 */
public final class PaymentAcknowledgementIngestor {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentAcknowledgementIngestor.class);

    public static final String EVENT_TYPE = "PaymentAcknowledgement";

    private final InboxService inbox;

    public PaymentAcknowledgementIngestor(InboxService inbox) {
        this.inbox = inbox;
    }

    /** Stores one acknowledgement; the tenant is the feed's configured tenant (channel binding, R38 rule 5). */
    public Inbox.ReceiveResult ingest(PaymentAcknowledgement ack) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", ack.source());
        data.put("operationId", ack.operationId());
        data.put("providerRef", ack.providerRef());
        data.put("paymentStatus", ack.status().name());
        data.put("reason", ack.reason());
        data.put("amount", ack.amount());
        data.put("receivedAt", ack.receivedAt().toString());
        ChannelBinding binding = ChannelBinding.of(ack.source(), ack.tenantId(), "feed:" + ack.source());
        return inbox.receive(binding, new InboundMessage(ack.messageId(), EVENT_TYPE, ack.operationId(), ack.tenantId(),
                Json.write(data)));
    }

    /**
     * One polling pass over a feed. A message that cannot be stored (back-pressure) stays in the feed's source and is
     * read again on the next pass, because the feed archives a file only after the consumer returned.
     */
    public int pollOnce(BankAcknowledgementFeed feed) {
        int[] stored = {0};
        feed.poll(ack -> {
            try {
                if (ingest(ack) == Inbox.ReceiveResult.ACCEPTED) {
                    stored[0]++;
                }
            } catch (BackpressureGate.BackpressureException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.warn("cannot store acknowledgement {} from {}", ack.messageId(), ack.source(), e);
                throw e;
            }
        });
        return stored[0];
    }
}
