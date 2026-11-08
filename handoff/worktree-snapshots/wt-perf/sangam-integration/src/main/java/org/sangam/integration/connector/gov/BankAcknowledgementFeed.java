// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One poll point for the asynchronous bank / treasury acknowledgements of payments (R58, IX.4 P1 bill/payment and
 * P3 disbursement): PFMS status files ({@link PfmsStatusPoller}) and the IFMS JIT failure feed
 * ({@link IfmsJitConnector#failures(String, int)}), normalised to {@link PaymentAcknowledgement}s. The integrator
 * routes them into the inbox, which de-duplicates by {@link PaymentAcknowledgement#messageId()}.
 *
 * <p>Delivery is at-least-once. The IFMS FD feed is a sliding window ({@code sinceHours}), so every poll re-emits the
 * failures still inside it; their messageId {@code ifms#<paymentRef>#<operationId>} is stable, so the inbox applies
 * each once. A failing source is logged and skipped; the other sources are still polled.
 */
public final class BankAcknowledgementFeed {

    private static final Logger LOG = LoggerFactory.getLogger(BankAcknowledgementFeed.class);

    /** One IFMS site whose FD feed is polled for a tenant. */
    public record IfmsSource(IfmsJitConnector connector, String tenantId, int sinceHours) {
        public IfmsSource {
            Objects.requireNonNull(connector, "connector");
            Objects.requireNonNull(tenantId, "tenantId");
            sinceHours = sinceHours <= 0 ? 72 : sinceHours;
        }
    }

    /** Result of one poll. */
    public record PollSummary(int emitted, List<String> failedSources) {
        public PollSummary {
            failedSources = List.copyOf(failedSources);
        }
    }

    private final List<PfmsStatusPoller> pfms;
    private final List<IfmsSource> ifms;
    private final Clock clock;

    public BankAcknowledgementFeed(List<PfmsStatusPoller> pfms, List<IfmsSource> ifms) {
        this(pfms, ifms, Clock.systemUTC());
    }

    public BankAcknowledgementFeed(List<PfmsStatusPoller> pfms, List<IfmsSource> ifms, Clock clock) {
        this.pfms = pfms == null ? List.of() : List.copyOf(pfms);
        this.ifms = ifms == null ? List.of() : List.copyOf(ifms);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Polls every source once; returns the number of acknowledgements passed to {@code consumer}. */
    public int poll(Consumer<PaymentAcknowledgement> consumer) {
        return pollWithSummary(consumer).emitted();
    }

    /** Polls every source once and reports which sources failed. A throwing consumer stops the poll (re-delivered). */
    public PollSummary pollWithSummary(Consumer<PaymentAcknowledgement> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int emitted = 0;
        List<String> failed = new ArrayList<>();
        for (PfmsStatusPoller p : pfms) {
            try {
                emitted += p.pollOnce(consumer);
            } catch (java.io.UncheckedIOException e) {
                LOG.warn("PFMS status poll for tenant {} failed: {}", p.tenantId(), e.getMessage());
                failed.add("pfms:" + p.tenantId());
            }
        }
        for (IfmsSource s : ifms) {
            List<IfmsJitConnector.FailureRecord> records;
            try {
                records = s.connector().failures(s.tenantId(), s.sinceHours());
            } catch (IfmsJitConnector.FeedUnavailableException e) {
                LOG.warn("IFMS failure feed {} for tenant {} unavailable: {}", s.connector().descriptor().id(),
                        s.tenantId(), e.getMessage());
                failed.add("ifms:" + s.connector().descriptor().id() + ":" + s.tenantId());
                continue;
            }
            for (IfmsJitConnector.FailureRecord f : records) {
                if (f.operationId() == null || f.operationId().isBlank()) {
                    LOG.warn("IFMS failure {} without operationId skipped", f.paymentRef());
                    continue;
                }
                consumer.accept(new PaymentAcknowledgement(PaymentAcknowledgement.SOURCE_IFMS,
                        "ifms#" + f.paymentRef() + "#" + f.operationId(), s.tenantId(), f.operationId(),
                        f.paymentRef(), PaymentAcknowledgement.Status.FAILED,
                        f.reason() == null ? "PAYMENT_FAILED" : f.reason(), f.amount(), clock.instant()));
                emitted++;
            }
        }
        return new PollSummary(emitted, failed);
    }
}
