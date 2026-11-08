// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import org.sangam.integration.connector.file.FileTransport;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.pfms.PfmsFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads PFMS status files (R58 "status files in", IX.4 P1/P3) and turns every status line into a
 * {@link PaymentAcknowledgement} for the inbox.
 *
 * <p>{@link #pollOnce(Consumer)} takes each {@code STS_*.csv} in {@code inbound} in name order, emits one
 * acknowledgement per line, and only then moves the file to {@code archive}. If the consumer throws, the file stays in
 * {@code inbound} and is delivered again on the next poll: delivery is at-least-once, and consumers de-duplicate by
 * {@link PaymentAcknowledgement#messageId()} = {@code <statusFileName>#<operationId>}, which is stable across polls.
 * Files that do not parse are moved to {@code inbound-error} for an operator.
 *
 * <p>Special cases:
 * <ul>
 *   <li>A file-level {@code REJECTED} status carries no lines; when the rejected payment file is readable in
 *       {@code rejected}, a {@code FAILED} acknowledgement is emitted for each of its lines with the file's reason.
 *       {@code DUPLICATE_BATCH} rejections only refuse a re-delivered file and produce no acknowledgement.</li>
 *   <li>A line {@code FAILED / DUPLICATE_OPERATION_ID} means the operation was paid in an earlier batch; it is not
 *       a failure of the payment and produces no acknowledgement.</li>
 *   <li>The amount is taken from the payment file in {@code processed} or {@code rejected} when it is readable.</li>
 * </ul>
 * The tenant is the one the PFMS exchange (SSID) belongs to (VI.4 rule 6), not anything in the file.
 */
public final class PfmsStatusPoller {

    public static final String ERROR_DIR = "inbound-error";

    private static final Logger LOG = LoggerFactory.getLogger(PfmsStatusPoller.class);

    private final FileTransport transport;
    private final String tenantId;
    private final Clock clock;

    public PfmsStatusPoller(FileTransport transport, String tenantId) {
        this(transport, tenantId, Clock.systemUTC());
    }

    public PfmsStatusPoller(FileTransport transport, String tenantId, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Poller over the PFMS simulator's exchange directory. */
    public static PfmsStatusPoller simulator(SimulatorProfile sims, String tenantId) {
        sims.pfms();
        return new PfmsStatusPoller(sims.pfmsTransport(), tenantId);
    }

    public String tenantId() {
        return tenantId;
    }

    /**
     * Processes every status file currently in {@code inbound}. Returns the number of acknowledgements emitted.
     *
     * @throws UncheckedIOException when the exchange directory cannot be read or a file cannot be archived
     */
    public synchronized int pollOnce(Consumer<PaymentAcknowledgement> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int emitted = 0;
        try {
            for (String name : transport.list(PfmsFileConnector.INBOUND)) {
                if (!name.startsWith("STS_") || !name.endsWith(".csv")) {
                    continue;
                }
                byte[] raw;
                try {
                    raw = transport.read(PfmsFileConnector.INBOUND, name);
                } catch (IOException e) {
                    LOG.warn("PFMS status file {} vanished before it could be read: {}", name, e.getMessage());
                    continue;
                }
                PfmsFiles.StatusFile sf;
                try {
                    sf = PfmsFiles.parseStatus(new String(raw, StandardCharsets.UTF_8));
                } catch (PfmsFiles.FormatException e) {
                    LOG.error("PFMS status file {} is malformed, moved to {}: {}", name, ERROR_DIR, e.getMessage());
                    transport.move(PfmsFileConnector.INBOUND, name, ERROR_DIR);
                    continue;
                }
                List<PaymentAcknowledgement> acks = acknowledgements(name, sf);
                for (PaymentAcknowledgement a : acks) {
                    consumer.accept(a);
                    emitted++;
                }
                transport.move(PfmsFileConnector.INBOUND, name, PfmsFileConnector.ARCHIVE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return emitted;
    }

    private List<PaymentAcknowledgement> acknowledgements(String fileName, PfmsFiles.StatusFile sf) {
        List<PaymentAcknowledgement> out = new ArrayList<>();
        if ("ACCEPTED".equals(sf.fileStatus())) {
            Map<String, String> amounts = amounts(PfmsFileConnector.PROCESSED, sf.paymentFileName());
            for (PfmsFiles.StatusLine l : sf.lines()) {
                if ("FAILED".equals(l.status()) && PfmsFileConnector.DUPLICATE_OPERATION_ID.equals(l.reason())) {
                    LOG.warn("PFMS {}: operation {} was already paid in an earlier batch; no acknowledgement",
                            fileName, l.operationId());
                    continue;
                }
                PaymentAcknowledgement.Status st = PaymentAcknowledgement.Status.from(l.status());
                out.add(new PaymentAcknowledgement(PaymentAcknowledgement.SOURCE_PFMS, fileName + "#" + l.operationId(),
                        tenantId, l.operationId(), l.pfmsRef(), st,
                        st == PaymentAcknowledgement.Status.SUCCESS ? null : l.reason(), amounts.get(l.operationId()),
                        clock.instant()));
            }
            return out;
        }
        if (PfmsFileConnector.DUPLICATE_BATCH.equals(sf.reason())) {
            LOG.info("PFMS {}: re-delivered batch {} refused as duplicate; no acknowledgement", fileName, sf.batchId());
            return out;
        }
        PfmsFiles.PaymentFile pf = paymentFile(PfmsFileConnector.REJECTED, sf.paymentFileName());
        if (pf == null) {
            LOG.warn("PFMS {}: batch {} rejected ({}) and its payment file is not readable; the operations resolve "
                    + "through lookup", fileName, sf.batchId(), sf.reason());
            return out;
        }
        String reason = sf.reason() == null ? "FILE_REJECTED" : sf.reason();
        for (PfmsFiles.PaymentLine l : pf.lines()) {
            out.add(new PaymentAcknowledgement(PaymentAcknowledgement.SOURCE_PFMS, fileName + "#" + l.operationId(),
                    tenantId, l.operationId(), null, PaymentAcknowledgement.Status.FAILED, reason,
                    PfmsFiles.money(l.amount()), clock.instant()));
        }
        return out;
    }

    private Map<String, String> amounts(String dir, String paymentFileName) {
        Map<String, String> m = new HashMap<>();
        PfmsFiles.PaymentFile pf = paymentFile(dir, paymentFileName);
        if (pf != null) {
            for (PfmsFiles.PaymentLine l : pf.lines()) {
                m.put(l.operationId(), PfmsFiles.money(l.amount()));
            }
        }
        return m;
    }

    private PfmsFiles.PaymentFile paymentFile(String dir, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            if (!transport.exists(dir, name)) {
                return null;
            }
            return PfmsFiles.parsePayment(new String(transport.read(dir, name), StandardCharsets.UTF_8));
        } catch (IOException | PfmsFiles.FormatException | IllegalArgumentException e) {
            return null;
        }
    }
}
