package org.sangam.spike.sim.pfms;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.spike.sim.Fault;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PfmsSftpSimulatorTest {

    @TempDir
    Path root;
    private LocalDirectoryTransport transport;
    private PfmsSftpSimulator sim;

    @BeforeEach
    void setUp() {
        transport = new LocalDirectoryTransport(root);
        sim = new PfmsSftpSimulator(transport).failAccount("BADACC");
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    private static PfmsFiles.PaymentFile batch(String batchId, String... opIdAndAccount) {
        List<PfmsFiles.PaymentLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < opIdAndAccount.length; i += 2) {
            lines.add(new PfmsFiles.PaymentLine(opIdAndAccount[i], "BILL-" + i, "Beneficiary " + i,
                    opIdAndAccount[i + 1], "SBIN0000001", new BigDecimal("1000.00")));
        }
        return new PfmsFiles.PaymentFile("SSID01", "SCH01", batchId, lines);
    }

    private void drop(PfmsFiles.PaymentFile f) throws IOException {
        transport.write(PfmsSftpSimulator.OUTBOUND, f.fileName(), f.toCsv().getBytes(StandardCharsets.UTF_8));
    }

    private List<String> statusFiles() throws IOException {
        return transport.list(PfmsSftpSimulator.INBOUND);
    }

    private PfmsFiles.StatusFile status(String name) throws Exception {
        return PfmsFiles.parseStatus(new String(transport.read(PfmsSftpSimulator.INBOUND, name), StandardCharsets.UTF_8));
    }

    private void awaitStatusFiles(int n, Duration max) throws Exception {
        long until = System.nanoTime() + max.toNanos();
        while (statusFiles().size() < n) {
            if (System.nanoTime() > until) {
                throw new AssertionError("expected " + n + " status files, have " + statusFiles());
            }
            Thread.sleep(25);
        }
    }

    // ---- format --------------------------------------------------------------------------------

    @Test
    void paymentFileRoundTripsAndValidates() throws Exception {
        PfmsFiles.PaymentFile f = batch("B1", "op-1", "ACC1", "op-2", "ACC2");
        assertEquals(f, PfmsFiles.parsePayment(f.toCsv()));
        String bad = f.toCsv().replace("H,SSID01,SCH01,B1,2,", "H,SSID01,SCH01,B1,3,");
        assertThrows(PfmsFiles.FormatException.class, () -> PfmsFiles.parsePayment(bad));
        assertThrows(IllegalArgumentException.class, () -> new PfmsFiles.PaymentFile("S", "C", "B",
                List.of(new PfmsFiles.PaymentLine("op", "b", "Doe, John", "A", "I", BigDecimal.ONE))).toCsv());
    }

    // ---- normal path ---------------------------------------------------------------------------

    @Test
    void processesPaymentFileAndWritesStatus() throws Exception {
        drop(batch("B1", "op-1", "ACC1", "op-2", "BADACC"));
        assertEquals(1, sim.pollOnce());
        assertEquals(List.of("STS_B1_1.csv"), statusFiles());
        PfmsFiles.StatusFile st = status("STS_B1_1.csv");
        assertEquals("ACCEPTED", st.fileStatus());
        assertEquals("SUCCESS", st.lines().get(0).status());
        assertEquals("FAILED", st.lines().get(1).status());
        assertEquals("ACCOUNT_INVALID", st.lines().get(1).reason());
        assertTrue(transport.exists(PfmsSftpSimulator.PROCESSED, "PAY_SSID01_B1.csv"));
        assertTrue(transport.list(PfmsSftpSimulator.OUTBOUND).isEmpty());
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-1"));
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT_FAILED, "op-2"));
    }

    @Test
    void watchesDirectoryAndHonoursStatusDelay() throws Exception {
        sim.statusDelay(Duration.ofMillis(500)).pollInterval(Duration.ofMillis(50)).start();
        drop(batch("B1", "op-1", "ACC1"));
        Thread.sleep(250);
        assertTrue(statusFiles().isEmpty(), "status not yet written");
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT), "but already paid");
        awaitStatusFiles(1, Duration.ofSeconds(5));
    }

    @Test
    void malformedFileIsRejectedWithoutEffect() throws Exception {
        transport.write(PfmsSftpSimulator.OUTBOUND, "PAY_SSID01_BX.csv",
                "H,SSID01,SCH01,BX,1,5.00\nD,op-1,b,n,ACC1,IFSC,6.00\n".getBytes(StandardCharsets.UTF_8));
        sim.pollOnce();
        PfmsFiles.StatusFile st = status("STS_BX_1.csv");
        assertEquals("REJECTED", st.fileStatus());
        assertTrue(st.reason().startsWith("FORMAT_ERROR"));
        assertTrue(transport.exists(PfmsSftpSimulator.REJECTED, "PAY_SSID01_BX.csv"));
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void idempotentByBatchAndOperationId() throws Exception {
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        drop(batch("B1", "op-1", "ACC1")); // resend of the same batch
        sim.pollOnce();
        assertEquals("DUPLICATE_BATCH", status("STS_B1_2.csv").reason());
        drop(batch("B2", "op-1", "ACC1", "op-3", "ACC3")); // op-1 again in a new batch
        sim.pollOnce();
        PfmsFiles.StatusFile st = status("STS_B2_1.csv");
        assertEquals("DUPLICATE_OPERATION_ID", st.lines().get(0).reason());
        assertEquals("SUCCESS", st.lines().get(1).status());
        assertFalse(sim.ledger().hasDuplicates(PfmsSftpSimulator.EFFECT_PAYMENT));
    }

    @Test
    void nonIdempotentModePaysResentBatchAgain() throws Exception {
        sim.idempotent(false);
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        assertEquals(2, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-1"));
    }

    // ---- faults --------------------------------------------------------------------------------

    @Test
    void faultLostStatusFile() throws Exception {
        sim.faults().once(Fault.dropResponse());
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        assertTrue(statusFiles().isEmpty(), "no status file: outcome unknown to the product");
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-1"), "but the payment happened");
        // reconciliation by resubmitting under a new batch with the same operationId is safe
        drop(batch("B1R", "op-1", "ACC1"));
        sim.pollOnce();
        assertEquals("DUPLICATE_OPERATION_ID", status("STS_B1R_1.csv").lines().get(0).reason());
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-1"));
    }

    @Test
    void faultDuplicateDelivery() throws Exception {
        sim.faults().once(Fault.duplicate());
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        assertEquals(List.of("STS_B1_1.csv", "STS_B1_2.csv"), statusFiles());
        assertEquals("ACCEPTED", status("STS_B1_1.csv").fileStatus());
        assertEquals("DUPLICATE_BATCH", status("STS_B1_2.csv").reason());
        assertEquals(1, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-1"));

        sim.idempotent(false).faults().once(Fault.duplicate());
        drop(batch("B2", "op-2", "ACC2"));
        sim.pollOnce();
        assertEquals(2, sim.ledger().count(PfmsSftpSimulator.EFFECT_PAYMENT, "op-2"));
    }

    @Test
    void faultErrorRejectsWithoutEffect() throws Exception {
        sim.faults().once(Fault.error(500));
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        PfmsFiles.StatusFile st = status("STS_B1_1.csv");
        assertEquals("REJECTED", st.fileStatus());
        assertEquals("SIM_ERROR_500", st.reason());
        assertEquals(0, sim.ledger().all().size());
    }

    @Test
    void faultDelayPostponesStatusFile() throws Exception {
        sim.faults().once(Fault.delay(Duration.ofMillis(400)));
        drop(batch("B1", "op-1", "ACC1"));
        sim.pollOnce();
        assertTrue(statusFiles().isEmpty());
        assertEquals(1, sim.pendingStatusFiles());
        awaitStatusFiles(1, Duration.ofSeconds(5));
        assertEquals("ACCEPTED", status("STS_B1_1.csv").fileStatus());
    }
}
