// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.sangam.integration.sidecar.SidecarLogging;

/**
 * Entry point of the simulator image ({@code deploy/docker/simulators.Dockerfile}, owner decision 184; packaged by the
 * {@code simulators-app} profile of this module as {@code sangam-integration-*-simulators.jar}). One simulator per
 * container, chosen by {@value #KIND_ENV}. Demo and test only: never deploy it in production, and never point a
 * production connector at it.
 *
 * <p>Runtime contract (environment):
 * <ul>
 *   <li>{@value #KIND_ENV}: {@code ifms | pfms | esign | dlt | digilocker | paygov | smtp} (required);</li>
 *   <li>{@value #BIND_ENV}: bind address, default {@code 0.0.0.0}; {@value #PORT_ENV}: HTTP port, default
 *       {@value #DEFAULT_PORT};</li>
 *   <li>{@value #DATA_ENV}: working folder, default {@code /data} (PFMS {@code outbound}, {@code inbound},
 *       {@code processed}, {@code rejected}; optional {@code dlt-templates.json} for the DLT gateway);</li>
 *   <li>{@value #FAULTS_ENV}: optional fault script ({@link FaultScript}) applied before the port opens; an invalid or
 *       missing file stops the start;</li>
 *   <li>{@value #SMTP_PORT_ENV}: SMTP port of kind {@code smtp}, default {@value #DEFAULT_SMTP_PORT};</li>
 *   <li>{@value #PG_CALLBACK_ENV}: optional callback URL of kind {@code paygov}, signed with the simulator's
 *       development secret.</li>
 * </ul>
 * {@code GET /__sim/health}, {@code GET /__sim/ledger} and {@code POST /__sim/faults} are the control endpoints
 * ({@link SimFront}); every other path goes to the simulator (kinds with an HTTP interface).
 */
public final class SimulatorLauncher implements AutoCloseable {

    /** The {@code service.name} of every log line (decisions 324 and 378: the console log is ECS JSON). */
    public static final String SERVICE = "sangam-simulator";
    private static final System.Logger LOG = System.getLogger(SimulatorLauncher.class.getName());

    public static final String KIND_ENV = "SANGAM_SIM_KIND";
    public static final String BIND_ENV = "SANGAM_SIM_BIND";
    public static final String PORT_ENV = "SANGAM_SIM_PORT";
    public static final String DATA_ENV = "SANGAM_SIM_DATA";
    public static final String FAULTS_ENV = "SANGAM_SIM_FAULTS";
    public static final String SMTP_PORT_ENV = "SANGAM_SIM_SMTP_PORT";
    public static final String PG_CALLBACK_ENV = "SANGAM_SIM_PG_CALLBACK_URL";
    public static final int DEFAULT_PORT = 9000;
    public static final int DEFAULT_SMTP_PORT = 2525;

    /** The launcher settings; invalid values throw {@link IllegalArgumentException}. */
    record Settings(String kind, String bind, int port, Path data, Path faults, int smtpPort, String callbackUrl) {
        static Settings from(Map<String, String> env) {
            String kind = env.getOrDefault(KIND_ENV, "").strip().toLowerCase(Locale.ROOT);
            if (!HostedSimulator.KINDS.contains(kind)) {
                throw new IllegalArgumentException(KIND_ENV + " must be one of " + HostedSimulator.KINDS);
            }
            String bind = env.getOrDefault(BIND_ENV, "0.0.0.0").strip();
            if (bind.isEmpty()) {
                throw new IllegalArgumentException(BIND_ENV + " is blank");
            }
            int port = port(env, PORT_ENV, DEFAULT_PORT);
            int smtp = port(env, SMTP_PORT_ENV, DEFAULT_SMTP_PORT);
            if (kind.equals("smtp") && smtp == port && port != 0) {
                throw new IllegalArgumentException(SMTP_PORT_ENV + " must differ from " + PORT_ENV);
            }
            String data = env.getOrDefault(DATA_ENV, "/data").strip();
            if (data.isEmpty()) {
                throw new IllegalArgumentException(DATA_ENV + " is blank");
            }
            String faults = env.get(FAULTS_ENV);
            return new Settings(kind, bind, port, Path.of(data),
                    faults == null || faults.isBlank() ? null : Path.of(faults.strip()), smtp, env.get(PG_CALLBACK_ENV));
        }

        private static int port(Map<String, String> env, String key, int fallback) {
            String v = env.get(key);
            if (v == null || v.isBlank()) {
                return fallback;
            }
            try {
                int p = Integer.parseInt(v.strip());
                if (p < 0 || p > 65_535) {
                    throw new IllegalArgumentException(key + " must be 0..65535");
                }
                return p;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " is not a number");
            }
        }
    }

    private final HostedSimulator sim;
    private final SimFront front;
    private final TcpRelay relay;

    private SimulatorLauncher(HostedSimulator sim, SimFront front, TcpRelay relay) {
        this.sim = sim;
        this.front = front;
        this.relay = relay;
    }

    /** Starts the simulator, applies the fault file, then opens the ports. Port 0 picks a free port (tests). */
    static SimulatorLauncher start(Settings s) throws IOException {
        Files.createDirectories(s.data());
        FaultScript script = null;
        if (s.faults() != null) {
            if (!Files.isRegularFile(s.faults())) {
                throw new IllegalArgumentException(FAULTS_ENV + " names no file: " + s.faults());
            }
            script = FaultScript.parse(Files.readAllBytes(s.faults()));
        }
        HostedSimulator sim = HostedSimulator.start(s.kind(), s.data(), s.callbackUrl());
        SimFront front = null;
        TcpRelay relay = null;
        try {
            if (script != null) {
                script.applyTo(sim.faults());
            }
            if (sim.tcpTarget() != null) {
                relay = new TcpRelay(new InetSocketAddress(s.bind(), s.smtpPort()), sim.tcpTarget()).start();
            }
            front = new SimFront(sim, new InetSocketAddress(s.bind(), s.port())).start();
            return new SimulatorLauncher(sim, front, relay);
        } catch (IOException | RuntimeException e) {
            if (relay != null) {
                relay.close();
            }
            closeQuietly(sim);
            throw e;
        }
    }

    int httpPort() {
        return front.port();
    }

    /** The SMTP port, or -1 for other kinds. */
    int smtpPort() {
        return relay == null ? -1 : relay.port();
    }

    HostedSimulator simulator() {
        return sim;
    }

    @Override
    public void close() {
        front.close();
        if (relay != null) {
            relay.close();
        }
        closeQuietly(sim);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            // best effort at shutdown
        }
    }

    /** Installs the JSON console logging of the launcher on {@code out} (decisions 324, 378); the first step of main. */
    static void logging(OutputStream out) {
        SidecarLogging.install(SERVICE, out);
    }

    /** The start-up line, logged as one JSON object; every value in it is escaped. */
    static void logStarted(String kind, String bind, int httpPort, int smtpPort, Path data) {
        LOG.log(System.Logger.Level.INFO, "sangam simulator " + kind + " (DEMO AND TEST ONLY, never production) on "
                + bind + ":" + httpPort + (smtpPort > 0 ? ", SMTP on " + bind + ":" + smtpPort : "") + ", data "
                + data.toAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        logging(System.out);
        Settings s = Settings.from(System.getenv());
        SimulatorLauncher launcher = start(s);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            launcher.close();
            stopped.countDown();
        }, "simulator-shutdown"));
        logStarted(s.kind(), s.bind(), launcher.httpPort(), launcher.smtpPort(), s.data());
        stopped.await();
    }
}
