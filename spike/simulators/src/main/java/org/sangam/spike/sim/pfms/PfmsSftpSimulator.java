package org.sangam.spike.sim.pfms;

import org.sangam.spike.sim.EffectsLedger;
import org.sangam.spike.sim.Fault;
import org.sangam.spike.sim.FaultInjector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PFMS file-exchange simulator (SANGAM.md IX.7d: SFTP model, SSID, status files). It watches
 * {@value #OUTBOUND} for {@code PAY_*.csv} payment files ({@link PfmsFiles}), executes the payments,
 * moves the file to {@value #PROCESSED} (or {@value #REJECTED}) and writes a status file to
 * {@value #INBOUND} after {@link #statusDelay(Duration)}.
 *
 * <p>Faults (route name {@value #ROUTE}): DELAY adds to the status delay; DROP_RESPONSE pays but
 * writes no status file; DUPLICATE processes the same file twice (two status files); ERROR rejects
 * the file unprocessed with reason {@code SIM_ERROR_<status>}.
 *
 * <p>With {@link #idempotent(boolean)} on (default) an operationId is paid at most once, and a
 * repeated batchId is rejected as {@code DUPLICATE_BATCH}. Ledger types: {@code PAYMENT},
 * {@code PAYMENT_FAILED} (key = operationId).
 */
public final class PfmsSftpSimulator implements AutoCloseable {

    public static final String OUTBOUND = "outbound";
    public static final String INBOUND = "inbound";
    public static final String PROCESSED = "processed";
    public static final String REJECTED = "rejected";
    public static final String ROUTE = "payment-file";
    public static final String EFFECT_PAYMENT = "PAYMENT";
    public static final String EFFECT_PAYMENT_FAILED = "PAYMENT_FAILED";

    private final FileTransport transport;
    private final FaultInjector faults = new FaultInjector();
    private final EffectsLedger ledger = new EffectsLedger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pfms-sim");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> paidOperations = new HashSet<>();
    private final Set<String> seenBatches = new HashSet<>();
    private final Set<String> failingAccounts = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> statusSeq = new ConcurrentHashMap<>();
    private final AtomicLong refSeq = new AtomicLong();
    private final AtomicInteger pendingStatus = new AtomicInteger();
    private volatile Duration statusDelay = Duration.ZERO;
    private volatile Duration pollInterval = Duration.ofMillis(200);
    private volatile boolean idempotent = true;
    private ScheduledFuture<?> poller;

    public PfmsSftpSimulator(FileTransport transport) {
        this.transport = transport;
    }

    public PfmsSftpSimulator statusDelay(Duration d) {
        this.statusDelay = d;
        return this;
    }

    public PfmsSftpSimulator pollInterval(Duration d) {
        this.pollInterval = d;
        return this;
    }

    public PfmsSftpSimulator idempotent(boolean on) {
        this.idempotent = on;
        return this;
    }

    public PfmsSftpSimulator failAccount(String accountNo) {
        failingAccounts.add(accountNo);
        return this;
    }

    public FaultInjector faults() {
        return faults;
    }

    public EffectsLedger ledger() {
        return ledger;
    }

    public FileTransport transport() {
        return transport;
    }

    /** Status files scheduled but not yet written. */
    public int pendingStatusFiles() {
        return pendingStatus.get();
    }

    /** Start polling {@value #OUTBOUND} every {@link #pollInterval(Duration)}. */
    public synchronized PfmsSftpSimulator start() {
        if (poller == null) {
            long ms = Math.max(1, pollInterval.toMillis());
            poller = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    pollOnce();
                } catch (RuntimeException e) {
                    // keep polling; a broken file must not stop the simulator
                }
            }, 0, ms, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    /** Process every payment file currently in {@value #OUTBOUND}; returns how many were taken. */
    public synchronized int pollOnce() {
        try {
            int n = 0;
            for (String name : transport.list(OUTBOUND)) {
                if (name.startsWith("PAY_") && name.endsWith(".csv")) {
                    handleFile(name);
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handleFile(String name) throws IOException {
        String csv = new String(transport.read(OUTBOUND, name), StandardCharsets.UTF_8);
        FaultInjector.Active active = faults.next(ROUTE);
        Duration delay = statusDelay.plusMillis(active.totalDelayMillis());
        Fault error = active.error();
        if (error != null) {
            transport.move(OUTBOUND, name, REJECTED);
            emit(new PfmsFiles.StatusFile(batchIdOf(name, csv), name, "REJECTED", "SIM_ERROR_" + error.status(),
                    List.of()), delay);
            return;
        }
        PfmsFiles.PaymentFile file;
        try {
            file = PfmsFiles.parsePayment(csv);
        } catch (PfmsFiles.FormatException e) {
            transport.move(OUTBOUND, name, REJECTED);
            emit(new PfmsFiles.StatusFile(batchIdOf(name, csv), name, "REJECTED",
                    "FORMAT_ERROR: " + e.getMessage().replace(',', ';'), List.of()), delay);
            return;
        }
        transport.move(OUTBOUND, name, PROCESSED);
        int deliveries = active.duplicate() ? 2 : 1;
        for (int i = 0; i < deliveries; i++) {
            PfmsFiles.StatusFile status = execute(name, file);
            if (!active.dropResponse()) {
                emit(status, delay);
            }
        }
    }

    private PfmsFiles.StatusFile execute(String name, PfmsFiles.PaymentFile file) {
        if (idempotent && !seenBatches.add(file.batchId())) {
            return new PfmsFiles.StatusFile(file.batchId(), name, "REJECTED", "DUPLICATE_BATCH", List.of());
        }
        seenBatches.add(file.batchId());
        List<PfmsFiles.StatusLine> out = new ArrayList<>();
        for (PfmsFiles.PaymentLine l : file.lines()) {
            if (idempotent && paidOperations.contains(l.operationId())) {
                out.add(new PfmsFiles.StatusLine(l.operationId(), null, "FAILED", "DUPLICATE_OPERATION_ID"));
                continue;
            }
            String ref = "PFMS" + String.format("%08d", refSeq.incrementAndGet());
            Map<String, String> attrs = Map.of("pfmsRef", ref, "batchId", file.batchId(), "billId", l.billId(),
                    "amount", PfmsFiles.money(l.amount()), "accountNo", l.accountNo(), "ssid", file.ssid());
            paidOperations.add(l.operationId());
            if (failingAccounts.contains(l.accountNo())) {
                ledger.record(EFFECT_PAYMENT_FAILED, l.operationId(), attrs);
                out.add(new PfmsFiles.StatusLine(l.operationId(), ref, "FAILED", "ACCOUNT_INVALID"));
            } else {
                ledger.record(EFFECT_PAYMENT, l.operationId(), attrs);
                out.add(new PfmsFiles.StatusLine(l.operationId(), ref, "SUCCESS", null));
            }
        }
        return new PfmsFiles.StatusFile(file.batchId(), name, "ACCEPTED", null, out);
    }

    private void emit(PfmsFiles.StatusFile status, Duration delay) {
        int seq = statusSeq.computeIfAbsent(status.batchId(), k -> new AtomicInteger()).incrementAndGet();
        String fileName = "STS_" + status.batchId() + "_" + seq + ".csv";
        byte[] content = status.toCsv().getBytes(StandardCharsets.UTF_8);
        pendingStatus.incrementAndGet();
        Runnable write = () -> {
            try {
                transport.write(INBOUND, fileName, content);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                pendingStatus.decrementAndGet();
            }
        };
        if (delay.isZero() || delay.isNegative()) {
            write.run();
        } else {
            scheduler.schedule(write, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static String batchIdOf(String fileName, String csv) {
        String first = csv.lines().findFirst().orElse("");
        String[] h = first.split(",", -1);
        if (h.length >= 4 && h[0].equals("H") && !h[3].isBlank() && !h[3].contains("_")) {
            return h[3];
        }
        return fileName.substring(0, fileName.length() - 4).replace('_', '-');
    }

    @Override
    public synchronized void close() {
        scheduler.shutdownNow();
    }
}
