// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim;

import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.integration.sim.digilocker.DigiLockerPushSimulator;
import org.sangam.integration.sim.dlt.DltSmsSimulator;
import org.sangam.integration.sim.esign.EsignSimulator;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.sangam.integration.sim.mail.SmtpSinkSimulator;
import org.sangam.integration.sim.pay.PaymentGatewaySimulator;
import org.sangam.integration.sim.pfms.PfmsSftpSimulator;
import org.sangam.integration.sim.rest.RestEchoSimulator;
import org.sangam.integration.sim.soap.SoapEchoSimulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.stream.Stream;

/**
 * The simulators behind the {@link org.sangam.core.integration.ConnectorProfile#SIMULATOR SIMULATOR} connector profile
 * (ARCHITECTURE.md §1.9: air-gapped development, no outbound calls). Each simulator is created and started lazily on
 * first use, bound to the loopback interface on a free port, and closed by {@link #close()} in reverse start order.
 *
 * <p>The PFMS exchange uses a {@link LocalDirectoryTransport} over a fresh temporary directory (the stand-in for the
 * PFMS SFTP server, R58) and a started {@link PfmsSftpSimulator} that polls it; the temporary directory is deleted on
 * close unless it was supplied by the caller.
 *
 * <p>Credentials of the simulated systems are fixed development literals ({@link #IFMS_CLIENT_ID} etc.); they are never
 * valid against a live system. Thread-safe.
 */
public final class SimulatorProfile implements AutoCloseable {

    /** IFMS JIT simulator client id / secret (the simulator defaults). */
    public static final String IFMS_CLIENT_ID = "sim-client";
    public static final String IFMS_CLIENT_SECRET = "sim-secret";
    /** ASP id used against the eSign simulator. */
    public static final String ESIGN_ASP_ID = "SIM-ASP-001";
    /** Issuer org id used against the DigiLocker simulator. */
    public static final String DIGILOCKER_ORG_ID = "in.gov.sangam.sim";
    /** Shared secret the payment gateway simulator signs callbacks with. */
    public static final String PG_CALLBACK_SECRET = "sim-pg-callback-secret";
    /** PFMS source system id and scheme used against the PFMS simulator. */
    public static final String PFMS_SSID = "SIMSSID";
    public static final String PFMS_SCHEME = "SIMSCHEME";
    /** HMAC key for detached PFMS file signatures in the simulator profile. */
    public static final String PFMS_SIGNING_KEY = "sim-pfms-signing-key";

    private final Path suppliedPfmsRoot;
    private final Deque<AutoCloseable> started = new ArrayDeque<>();
    private IfmsJitSimulator ifms;
    private EsignSimulator esign;
    private DigiLockerPushSimulator digiLocker;
    private DltSmsSimulator dlt;
    private RestEchoSimulator rest;
    private SoapEchoSimulator soap;
    private PaymentGatewaySimulator paymentGateway;
    private SmtpSinkSimulator smtp;
    private Path pfmsRoot;
    private LocalDirectoryTransport pfmsTransport;
    private PfmsSftpSimulator pfms;
    private boolean closed;

    /** Profile with a temporary PFMS directory. */
    public SimulatorProfile() {
        this(null);
    }

    /** Profile whose PFMS exchange lives under {@code pfmsRoot} (kept on close); null = a temporary directory. */
    public SimulatorProfile(Path pfmsRoot) {
        this.suppliedPfmsRoot = pfmsRoot;
    }

    // ---- HTTP simulators -----------------------------------------------------------------------

    public synchronized IfmsJitSimulator ifms() {
        open();
        if (ifms == null) {
            ifms = track(new IfmsJitSimulator().credentials(IFMS_CLIENT_ID, IFMS_CLIENT_SECRET)).start();
        }
        return ifms;
    }

    public synchronized EsignSimulator esign() {
        open();
        if (esign == null) {
            esign = track(new EsignSimulator()).start();
        }
        return esign;
    }

    public synchronized DigiLockerPushSimulator digiLocker() {
        open();
        if (digiLocker == null) {
            digiLocker = track(new DigiLockerPushSimulator()).start();
        }
        return digiLocker;
    }

    /** The DLT SMS gateway; templates are registered by the DLT connector factory from its registry (R60). */
    public synchronized DltSmsSimulator dlt() {
        open();
        if (dlt == null) {
            dlt = track(new DltSmsSimulator()).start();
        }
        return dlt;
    }

    public synchronized RestEchoSimulator rest() {
        open();
        if (rest == null) {
            rest = track(new RestEchoSimulator()).start();
        }
        return rest;
    }

    public synchronized SoapEchoSimulator soap() {
        open();
        if (soap == null) {
            soap = track(new SoapEchoSimulator()).start();
        }
        return soap;
    }

    /** The payment gateway; callbacks are off until {@code callback(url, PG_CALLBACK_SECRET)} is configured. */
    public synchronized PaymentGatewaySimulator paymentGateway() {
        open();
        if (paymentGateway == null) {
            paymentGateway = track(new PaymentGatewaySimulator()).start();
        }
        return paymentGateway;
    }

    public synchronized SmtpSinkSimulator smtp() {
        open();
        if (smtp == null) {
            smtp = track(new SmtpSinkSimulator()).start();
        }
        return smtp;
    }

    public URI ifmsBaseUri() {
        return ifms().baseUri();
    }

    public URI esignBaseUri() {
        return esign().baseUri();
    }

    public URI digiLockerBaseUri() {
        return digiLocker().baseUri();
    }

    public URI dltBaseUri() {
        return dlt().baseUri();
    }

    public URI restBaseUri() {
        return rest().baseUri();
    }

    public URI soapBaseUri() {
        return soap().baseUri();
    }

    public URI paymentGatewayBaseUri() {
        return paymentGateway().baseUri();
    }

    /** {@code smtp://127.0.0.1:<port>} of the SMTP sink. */
    public URI smtpUri() {
        SmtpSinkSimulator s = smtp();
        return URI.create("smtp://" + s.host() + ":" + s.port());
    }

    // ---- PFMS file exchange --------------------------------------------------------------------

    /** The file transport the PFMS connector writes to and the simulator reads from. */
    public synchronized LocalDirectoryTransport pfmsTransport() {
        open();
        if (pfmsTransport == null) {
            try {
                pfmsRoot = suppliedPfmsRoot != null ? Files.createDirectories(suppliedPfmsRoot)
                        : Files.createTempDirectory("sangam-pfms-sim-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            pfmsTransport = new LocalDirectoryTransport(pfmsRoot);
            if (suppliedPfmsRoot == null) {
                Path root = pfmsRoot;
                started.push(() -> deleteTree(root));
            }
        }
        return pfmsTransport;
    }

    /** The started PFMS simulator polling {@link #pfmsTransport()}. */
    public synchronized PfmsSftpSimulator pfms() {
        open();
        if (pfms == null) {
            pfms = track(new PfmsSftpSimulator(pfmsTransport())).start();
        }
        return pfms;
    }

    public synchronized Path pfmsRoot() {
        pfmsTransport();
        return pfmsRoot;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!started.isEmpty()) {
            try {
                started.pop().close();
            } catch (Exception e) {
                // best effort: keep closing the others
            }
        }
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("simulator profile is closed");
        }
    }

    private <T extends AutoCloseable> T track(T sim) {
        started.push(sim);
        return sim;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
