// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.sangam.integration.connector.file.LocalDirectoryTransport;
import org.sangam.integration.sim.EffectsLedger;
import org.sangam.integration.sim.FaultInjector;
import org.sangam.integration.sim.SimHttpServer;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.digilocker.DigiLockerPushSimulator;
import org.sangam.integration.sim.dlt.DltSmsSimulator;
import org.sangam.integration.sim.esign.EsignSimulator;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.sangam.integration.sim.mail.SmtpSinkSimulator;
import org.sangam.integration.sim.pay.PaymentGatewaySimulator;
import org.sangam.integration.sim.pfms.PfmsSftpSimulator;

/**
 * One running simulator of the image (owner decision 184): its faults, its ledger, where its HTTP interface listens on
 * the loopback interface (or {@code null}), where its TCP interface listens (SMTP, or {@code null}), and how to stop it.
 * The simulators are the in-process ones of {@code org.sangam.integration.sim}, unchanged, with the development
 * literals of {@link SimulatorProfile}; nothing here is valid against a live system.
 */
record HostedSimulator(String kind, FaultInjector faults, EffectsLedger ledger, URI httpTarget,
                       InetSocketAddress tcpTarget, AutoCloseable closer) implements AutoCloseable {

    /** The kinds of {@code SANGAM_SIM_KIND}. */
    static final java.util.List<String> KINDS = java.util.List.of("ifms", "pfms", "esign", "dlt", "digilocker",
            "paygov", "smtp");

    /** The DLT templates file in the data folder (the DLT connector registers templates in process only). */
    static final String DLT_TEMPLATES = "dlt-templates.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void close() throws Exception {
        closer.close();
    }

    /**
     * Starts the simulator of {@code kind}.
     *
     * @param data        the data folder (PFMS exchange; optional DLT templates)
     * @param callbackUrl the payment-gateway callback URL, or {@code null} for none
     */
    static HostedSimulator start(String kind, Path data, String callbackUrl) throws IOException {
        String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
        return switch (k) {
            case "ifms" -> http(k, new IfmsJitSimulator().credentials(SimulatorProfile.IFMS_CLIENT_ID,
                    SimulatorProfile.IFMS_CLIENT_SECRET));
            case "esign" -> http(k, new EsignSimulator());
            case "dlt" -> http(k, dlt(data));
            case "digilocker" -> http(k, new DigiLockerPushSimulator());
            case "paygov" -> {
                PaymentGatewaySimulator pg = new PaymentGatewaySimulator();
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    pg.callback(URI.create(callbackUrl.strip()), SimulatorProfile.PG_CALLBACK_SECRET);
                }
                yield http(k, pg);
            }
            case "pfms" -> {
                PfmsSftpSimulator pfms = new PfmsSftpSimulator(new LocalDirectoryTransport(data)).start();
                yield new HostedSimulator(k, pfms.faults(), pfms.ledger(), null, null, pfms);
            }
            case "smtp" -> {
                SmtpSinkSimulator smtp = new SmtpSinkSimulator().start();
                yield new HostedSimulator(k, smtp.faults(), smtp.ledger(), null,
                        new InetSocketAddress(smtp.host(), smtp.port()), smtp);
            }
            default -> throw new IllegalArgumentException("SANGAM_SIM_KIND must be one of " + KINDS);
        };
    }

    private static HostedSimulator http(String kind, SimHttpServer sim) {
        sim.start();
        return new HostedSimulator(kind, sim.faults(), sim.ledger(), sim.baseUri(), null, sim);
    }

    /**
     * The DLT gateway with the templates of {@code <data>/dlt-templates.json} when present:
     * {@code {"templates":[{"id":"...","header":"...","body":"... {#var#} ..."}],"urlWhitelist":["https://..."]}}.
     */
    static DltSmsSimulator dlt(Path data) {
        DltSmsSimulator dlt = new DltSmsSimulator();
        Path file = data.resolve(DLT_TEMPLATES);
        if (!Files.isRegularFile(file)) {
            return dlt;
        }
        JsonNode root;
        try {
            root = JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("the DLT templates file " + file + " is not JSON", e);
        }
        for (JsonNode t : root.path("templates")) {
            String id = t.path("id").asText(null);
            String header = t.path("header").asText(null);
            String body = t.path("body").asText(null);
            if (id == null || header == null || body == null) {
                throw new IllegalArgumentException("every DLT template needs id, header and body (" + file + ")");
            }
            dlt.registerTemplate(id, header, body);
        }
        for (JsonNode u : root.path("urlWhitelist")) {
            dlt.whitelistUrl(u.asText());
        }
        return dlt;
    }
}
