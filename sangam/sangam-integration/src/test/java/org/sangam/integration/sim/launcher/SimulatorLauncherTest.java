// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.FaultInjector;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.esign.EsignSimulator;
import org.sangam.integration.sim.ifms.IfmsJitSimulator;
import org.sangam.integration.sim.pfms.PfmsSftpSimulator;

/**
 * Owner decision 184: the simulator launcher implements the simulators.Dockerfile runtime contract (kind, bind, port,
 * data folder, fault file, {@code /__sim/health}, {@code /__sim/ledger}, {@code /__sim/faults}) around the unchanged
 * in-process simulators, on the loopback address and free ports.
 */
class SimulatorLauncherTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    private SimulatorLauncher start(String kind, Map<String, String> extra) throws IOException {
        Map<String, String> env = new HashMap<>(Map.of(SimulatorLauncher.KIND_ENV, kind,
                SimulatorLauncher.BIND_ENV, "127.0.0.1", SimulatorLauncher.PORT_ENV, "0",
                SimulatorLauncher.SMTP_PORT_ENV, "0", SimulatorLauncher.DATA_ENV, dir.resolve("data").toString()));
        env.putAll(extra);
        return SimulatorLauncher.start(SimulatorLauncher.Settings.from(env));
    }

    private HttpResponse<String> get(SimulatorLauncher l, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.httpPort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(SimulatorLauncher l, String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + l.httpPort() + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String auth(String secret) {
        return "{\"clientId\":\"" + SimulatorProfile.IFMS_CLIENT_ID + "\",\"clientSecret\":\"" + secret + "\"}";
    }

    @Test
    void theControlEndpointsAnswerAndOtherPathsReachTheSimulator() throws Exception {
        try (SimulatorLauncher l = start("ifms", Map.of())) {
            HttpResponse<String> health = get(l, "/__sim/health");
            assertEquals(200, health.statusCode());
            assertEquals("ifms", JSON.readTree(health.body()).get("kind").asText());
            assertEquals("UP", JSON.readTree(health.body()).get("status").asText());

            HttpResponse<String> ok = post(l, IfmsJitSimulator.AUTH_PATH, auth(SimulatorProfile.IFMS_CLIENT_SECRET));
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("SUCCESS", JSON.readTree(ok.body()).get("status").asText());
            assertEquals(401, post(l, IfmsJitSimulator.AUTH_PATH, auth("wrong")).statusCode());

            JsonNode ledger = JSON.readTree(get(l, "/__sim/ledger").body());
            assertEquals("ifms", ledger.get("kind").asText());
            assertTrue(ledger.get("effects").isArray());

            assertEquals(405, post(l, "/__sim/health", "{}").statusCode());
            assertEquals(405, get(l, "/__sim/faults").statusCode());
            assertEquals(404, get(l, "/__sim/other").statusCode());
        }
    }

    @Test
    void faultsPostedAtRunTimeApplyToTheSimulatorAndADroppedAnswerIsMirrored() throws Exception {
        try (SimulatorLauncher l = start("ifms", Map.of())) {
            HttpResponse<String> set = post(l, "/__sim/faults", "{\"faults\":[{\"mode\":\"once\",\"kind\":\"ERROR\","
                    + "\"status\":503,\"route\":\"authenticate\"},{\"kind\":\"DROP_RESPONSE\",\"route\":\"authenticate\"}]}");
            assertEquals(200, set.statusCode(), set.body());
            assertEquals(2, JSON.readTree(set.body()).get("pendingOnce").asInt());
            assertEquals(503, post(l, IfmsJitSimulator.AUTH_PATH, auth(SimulatorProfile.IFMS_CLIENT_SECRET)).statusCode());
            assertThrows(IOException.class,
                    () -> post(l, IfmsJitSimulator.AUTH_PATH, auth(SimulatorProfile.IFMS_CLIENT_SECRET)),
                    "a dropped answer closes the connection without a status line");
            assertEquals(200, post(l, IfmsJitSimulator.AUTH_PATH, auth(SimulatorProfile.IFMS_CLIENT_SECRET)).statusCode());

            assertEquals(400, post(l, "/__sim/faults", "{\"faults\":[{\"kind\":\"ERROR\"}]}").statusCode());
            assertEquals(400, post(l, "/__sim/faults", "{\"falts\":[]}").statusCode());
            assertEquals(400, post(l, "/__sim/faults", "not json").statusCode());
            assertEquals(0, l.simulator().faults().pendingOnce(), "an invalid script applies nothing");
        }
    }

    @Test
    void theFaultFileIsAppliedBeforeThePortOpensAndABadOneStopsTheStart() throws Exception {
        Path script = Files.writeString(dir.resolve("faults.json"),
                "{\"faults\":[{\"mode\":\"always\",\"kind\":\"ERROR\",\"status\":502}]}");
        try (SimulatorLauncher l = start("esign", Map.of(SimulatorLauncher.FAULTS_ENV, script.toString()))) {
            assertEquals(502, post(l, EsignSimulator.SIGN_PATH, "{}").statusCode());
            assertEquals(200, get(l, "/__sim/health").statusCode(), "control endpoints are never faulted");
            assertEquals(200, post(l, "/__sim/faults", "{\"clear\":true}").statusCode());
            assertFalse(post(l, EsignSimulator.SIGN_PATH, "{}").statusCode() == 502);
        }
        Path bad = Files.writeString(dir.resolve("bad.json"), "{\"faults\":[{\"kind\":\"SLOW\"}]}");
        assertThrows(IllegalArgumentException.class,
                () -> start("esign", Map.of(SimulatorLauncher.FAULTS_ENV, bad.toString())));
        assertThrows(IllegalArgumentException.class,
                () -> start("esign", Map.of(SimulatorLauncher.FAULTS_ENV, dir.resolve("absent.json").toString())));
    }

    @Test
    void pfmsUsesTheDataFolderAndHasOnlyTheControlEndpoints() throws Exception {
        try (SimulatorLauncher l = start("pfms", Map.of())) {
            assertEquals("pfms", JSON.readTree(get(l, "/__sim/health").body()).get("kind").asText());
            assertEquals(404, get(l, "/anything").statusCode());
            assertNull(l.simulator().httpTarget());
            Path outbound = dir.resolve("data").resolve(PfmsSftpSimulator.OUTBOUND);
            Files.createDirectories(outbound);
            assertTrue(Files.isDirectory(outbound));
        }
    }

    @Test
    void smtpIsRelayedFromTheContainerPortToTheSink() throws Exception {
        try (SimulatorLauncher l = start("smtp", Map.of())) {
            assertTrue(l.smtpPort() > 0);
            try (Socket s = new Socket("127.0.0.1", l.smtpPort())) {
                s.setSoTimeout(10_000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = s.getOutputStream();
                assertTrue(in.readLine().startsWith("220"));
                out.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                assertTrue(in.readLine().startsWith("221"));
            }
            assertEquals("smtp", JSON.readTree(get(l, "/__sim/health").body()).get("kind").asText());
        }
    }

    @Test
    void dltTemplatesComeFromTheDataFolder() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve(HostedSimulator.DLT_TEMPLATES), "{\"templates\":[{\"id\":\"T1\",\"header\":"
                + "\"SANGAM\",\"body\":\"Your file {#var#} is ready\"}],\"urlWhitelist\":[\"https://example.org/\"]}");
        assertNull(HostedSimulator.dlt(data).validate("SANGAM", "T1", "9876543210", "Your file F-1 is ready"));
        Files.writeString(data.resolve(HostedSimulator.DLT_TEMPLATES), "{\"templates\":[{\"id\":\"T1\"}]}");
        assertThrows(IllegalArgumentException.class, () -> HostedSimulator.dlt(data));
    }

    @Test
    void settingsAreCheckedAndTheFaultScriptIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> SimulatorLauncher.Settings.from(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SimulatorLauncher.Settings.from(Map.of(SimulatorLauncher.KIND_ENV, "bank")));
        SimulatorLauncher.Settings d = SimulatorLauncher.Settings.from(Map.of(SimulatorLauncher.KIND_ENV, "IFMS"));
        assertEquals("ifms", d.kind());
        assertEquals("0.0.0.0", d.bind());
        assertEquals(SimulatorLauncher.DEFAULT_PORT, d.port());
        assertEquals(Path.of("/data"), d.data());
        assertEquals(SimulatorLauncher.DEFAULT_SMTP_PORT, d.smtpPort());
        assertNull(d.faults());
        for (String[] bad : List.of(new String[] {SimulatorLauncher.PORT_ENV, "70000"},
                new String[] {SimulatorLauncher.PORT_ENV, "x"}, new String[] {SimulatorLauncher.BIND_ENV, " "},
                new String[] {SimulatorLauncher.DATA_ENV, " "})) {
            assertThrows(IllegalArgumentException.class, () -> SimulatorLauncher.Settings.from(
                    Map.of(SimulatorLauncher.KIND_ENV, "ifms", bad[0], bad[1])), bad[0]);
        }
        assertThrows(IllegalArgumentException.class, () -> SimulatorLauncher.Settings.from(Map.of(
                SimulatorLauncher.KIND_ENV, "smtp", SimulatorLauncher.PORT_ENV, "2525")));

        FaultScript s = FaultScript.parse(("{\"clear\":true,\"faults\":[{\"kind\":\"DELAY\",\"delayMillis\":5,"
                + "\"times\":3},{\"mode\":\"always\",\"kind\":\"DUPLICATE\",\"route\":\"x\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        FaultInjector injector = new FaultInjector().once(Fault.dropResponse());
        s.applyTo(injector);
        assertEquals(3, injector.pendingOnce(), "clear removed the earlier fault, then three DELAY copies");
        assertEquals(1, injector.next("x").faults().stream().filter(f -> f.kind() == Fault.Kind.DUPLICATE).count());
        for (String bad : new String[] {"[]", "{\"faults\":{}}", "{\"clear\":\"yes\"}",
                "{\"faults\":[{\"kind\":\"DELAY\"}]}", "{\"faults\":[{\"kind\":\"DELAY\",\"delayMillis\":0}]}",
                "{\"faults\":[{\"kind\":\"ERROR\",\"status\":200}]}", "{\"faults\":[{\"kind\":\"DROP_RESPONSE\",\"status\":500}]}",
                "{\"faults\":[{\"mode\":\"twice\",\"kind\":\"DUPLICATE\"}]}",
                "{\"faults\":[{\"mode\":\"always\",\"kind\":\"DUPLICATE\",\"times\":2}]}",
                "{\"faults\":[{\"kind\":\"DUPLICATE\",\"times\":0}]}", "{\"faults\":[{\"kind\":\"DUPLICATE\",\"route\":\" \"}]}",
                "{\"faults\":[{\"kind\":\"DUPLICATE\",\"extra\":1}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> FaultScript.parse(bad.getBytes(StandardCharsets.UTF_8)),
                    bad);
        }
    }
}
