// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.sangam.server.acceptance.AbstractAcceptanceIT;
import org.sangam.server.acceptance.AcceptanceDatabase;
import org.sangam.server.acceptance.AcceptanceIdp;
import org.sangam.server.acceptance.plugandplay.AccHttp;
import org.testcontainers.containers.Container;

/**
 * T35 (Part III §16, VI.2.6, R38, Stage C amendment "operationId derived from the business key"): restore the
 * database to an older snapshot while the broker still holds newer messages. PostgreSQL only.
 *
 * <p>The server under test is a <b>separate process</b> started from the built Boot jar on its own database
 * {@code acc_t35_*} in the acceptance container (one engine per JVM, FLOWABLE-INTO-OPERATON D-10), authenticated with
 * production-path OIDC tokens from {@link AcceptanceIdp}, and driven only over its public API:
 * deployment over {@code /engine-rest}; event definition (START_NEW with uniqueStart on the business key) and inbound
 * channel over {@code /api/v1/events/*}; bills as CloudEvents on {@code POST /api/v1/events/{channel}} (inbox dedup by
 * tenant/source/id); payment by an SDK-style worker over {@code /api/v1/work/*}, forwarding the operationId to the
 * downstream. Snapshot = {@code pg_dump -Fc}; restore = process killed, {@code DROP DATABASE WITH (FORCE)},
 * {@code CREATE DATABASE}, {@code pg_restore}, process started again. The broker log and the downstream payment
 * system (capability class a, idempotent by operationId) live in this test and keep what happened after the snapshot.
 * The database is read directly only to observe results.
 *
 * <p>Pass: one business start per bill; no operationId paid twice; no bill paid twice; every instance ended; the
 * operation ledger holds one SUCCEEDED operation per bill with the downstream's paymentRef; no open reconciliation
 * item; a second full replay is all DUPLICATE and starts nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T35RestoreIT {

    static final String SOURCE = "acc-t35-bill-portal";
    static final String TOPIC = "acc-t35-pay";
    static final long LOCK_MS = 20_000;

    final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final String tenant = "acct35" + run;
    final String db = "acc_t35_" + run;
    final String dump = "/tmp/acc-t35-" + run + ".dump";
    final String process = "acc-t35-bill-" + run;
    final String definition = "acc.t35.bill." + run;
    final String channel = "acc-t35-in-" + run;

    Process server;
    int port;
    AccHttp http;
    Path serverLog;

    record BrokerMessage(int offset, String messageId, String billId) {
    }

    final List<BrokerMessage> broker = new ArrayList<>();
    int consumerOffset;

    /** Downstream payment system, capability class (a): idempotent by operationId. Not restored. */
    static final class Downstream {
        final Map<String, String> refByOp = new ConcurrentHashMap<>();
        final List<String[]> effects = java.util.Collections.synchronizedList(new ArrayList<>());

        synchronized String pay(String operationId, String bill) {
            return refByOp.computeIfAbsent(operationId, op -> {
                String ref = "PAY-" + (effects.size() + 1);
                effects.add(new String[] {bill, op, ref});
                return ref;
            });
        }

        Map<String, Long> paymentsByBill() {
            Map<String, Long> out = new TreeMap<>();
            synchronized (effects) {
                for (String[] e : effects) {
                    out.merge(e[0], 1L, Long::sum);
                }
            }
            return out;
        }
    }

    final Downstream downstream = new Downstream();

    // ------------------------------------------------------------------ keys of the server under test

    private static String randomBase64(int n) {
        byte[] k = new byte[n];
        new java.security.SecureRandom().nextBytes(k);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    private static String[] ed25519() {
        try {
            java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private final String kek = randomBase64(32);
    private final String pepper = randomBase64(32);
    private final String[] signer = ed25519();
    private final String[] bundle = ed25519();

    // ------------------------------------------------------------------ server process lifecycle

    private static Path bootJar() throws Exception {
        // the jar this build just packaged: the newest sangam-server-*.jar in the module's build folders (target*)
        Path module = Path.of(System.getProperty("user.dir"));
        List<Path> jars = new ArrayList<>();
        try (var dirs = Files.list(module)) {
            for (Path d : dirs.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("target")).toList()) {
                try (var s = Files.list(d)) {
                    s.filter(p -> p.getFileName().toString().matches("sangam-server-.*\\.jar")
                            && !p.getFileName().toString().endsWith("-plain.jar")).forEach(jars::add);
                }
            }
        }
        return jars.stream().max(java.util.Comparator.comparing(p -> p.toFile().lastModified()))
                .orElseThrow(() -> new IllegalStateException("no built sangam-server jar under " + module));
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String jdbcUrl() {
        var c = AcceptanceDatabase.container();
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + db;
    }

    private void startServer() throws Exception {
        port = freePort();
        // actuator runs on its own management port (management.server.port, SANGAM_MANAGEMENT_PORT in the deploy docs)
        int managementPort = freePort();
        while (managementPort == port) {
            managementPort = freePort();
        }
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // the documented production launch options (deploy/README.md "JAVA_TOOL_OPTIONS"): both temp folders on a
        // folder the process owns (the JDK's Windows selector opens an AF_UNIX socket there), UTC, UTF-8
        Path owned = bootJar().getParent();
        String unixTmp = System.getProperty("jdk.net.unixdomain.tmpdir", owned.toString());
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-Duser.timezone=UTC", "-Dfile.encoding=UTF-8",
                "-Djava.io.tmpdir=" + owned, "-Djdk.net.unixdomain.tmpdir=" + unixTmp, "-jar", bootJar().toString(),
                "--spring.profiles.active=postgres",
                "--server.port=" + port,
                "--management.server.port=" + managementPort,
                "--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=" + AcceptanceDatabase.username(),
                "--spring.datasource.password=" + AcceptanceDatabase.password(),
                "--operaton.bpm.database.schema-update=true",
                "--sangam.security.kek.keys.t35=" + kek,
                "--sangam.security.kek.current-id=t35",
                "--sangam.registry.signer.private-key=" + signer[0],
                "--sangam.registry.signer.public-key=" + signer[1],
                "--sangam.registry.vault.hmac-pepper=" + pepper,
                "--sangam.onboarding.bundle.key-id=acc-t35-bundle",
                "--sangam.onboarding.bundle.private-key=" + bundle[0],
                "--sangam.onboarding.bundle.trusted-keys.acc-t35-bundle=" + bundle[1],
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + AcceptanceIdp.get().issuer(),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + AcceptanceIdp.get().jwksUri(),
                "--sangam.security.jit-provisioning-tenants=" + tenant,
                "--sangam.security.bootstrap.enabled=true",
                // the server serves only tenants that exist (TenantAdminService.requireActive); without a dev token
                // the bootstrap creates 'dev', not this run's tenant, so every call was 401 TENANT_UNAVAILABLE
                "--sangam.security.bootstrap.tenants=" + tenant,
                "--sangam.security.bootstrap.admin-groups=sangam-admin",
                "--sangam.integration.simulators.enabled=true"));
        serverLog = Path.of(System.getProperty("user.dir"), System.getProperty("sangam.target", "target"),
                "acc-t35-" + run + "-" + System.currentTimeMillis() + ".log");
        server = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        http = AccHttp.local(port, AcceptanceIdp.get().token(tenant, "acc-t35-admin", AbstractAcceptanceIT.ADMIN_GROUPS));
        HttpClient probe = HttpClient.newHttpClient();
        long end = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < end) {
            if (!server.isAlive()) {
                throw new IllegalStateException("server process exited " + server.exitValue() + "; log " + serverLog);
            }
            try {
                HttpResponse<String> r = probe.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort
                        + "/actuator/health")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                // still starting
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("server not healthy within 5 min; log " + serverLog);
    }

    /** Kills the process: a restore never waits for a graceful shutdown. */
    private void stopServer() throws Exception {
        if (server != null) {
            server.destroyForcibly();
            server.waitFor();
            server = null;
        }
    }

    @AfterAll
    void cleanUp() throws Exception {
        stopServer();
        try {
            psql("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ container tools

    private Container.ExecResult exec(String... cmd) throws Exception {
        String[] full = new String[cmd.length + 2];
        full[0] = "env";
        full[1] = "PGPASSWORD=" + AcceptanceDatabase.password();
        System.arraycopy(cmd, 0, full, 2, cmd.length);
        Container.ExecResult r = AcceptanceDatabase.container().execInContainer(full);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " -> exit " + r.getExitCode() + ": " + r.getStderr());
        }
        return r;
    }

    private void psql(String sql) throws Exception {
        exec("psql", "-U", AcceptanceDatabase.username(), "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
    }

    /**
     * Observation only: (operation_id, state, outcome) of the ledger rows of one root, read with psql inside the
     * container (ids are generated UUIDs and the tenant is ours, so the literals are safe).
     */
    private List<String[]> ledgerRows(String rootId) throws Exception {
        String out = exec("psql", "-U", AcceptanceDatabase.username(), "-d", db, "-v", "ON_ERROR_STOP=1", "-t", "-A",
                "-F", "\u001f", "-c", "SELECT operation_id, state, COALESCE(outcome,'') FROM OPERATION_LEDGER WHERE tenant_id='"
                        + tenant + "' AND root_id='" + rootId.replace("'", "") + "'").getStdout();
        List<String[]> rows = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.isBlank()) {
                rows.add(line.split("\u001f", -1));
            }
        }
        return rows;
    }

    private void snapshot() throws Exception {
        exec("pg_dump", "-U", AcceptanceDatabase.username(), "-d", db, "-Fc", "-f", dump);
    }

    private void restore() throws Exception {
        stopServer();
        psql("DROP DATABASE " + db + " WITH (FORCE)");
        psql("CREATE DATABASE " + db);
        exec("pg_restore", "-U", AcceptanceDatabase.username(), "-d", db, "--exit-on-error", "--no-owner", dump);
        startServer();
    }

    // ------------------------------------------------------------------ setup over the public API

    private void deploy() throws Exception {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                    id="acc-t35-defs" targetNamespace="http://sangam.org/acceptance">
                  <process id="%s" isExecutable="true" operaton:historyTimeToLive="30">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s" targetRef="pay"/>
                    <serviceTask id="pay" name="Payment" operaton:type="external" operaton:topic="%s">
                      <extensionElements><operaton:properties>
                        <operaton:property name="sangam:capabilityClass" value="a"/>
                      </operaton:properties></extensionElements>
                    </serviceTask>
                    <sequenceFlow id="f2" sourceRef="pay" targetRef="e"/>
                    <endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(process, TOPIC).strip();
        String boundary = "acc-t35-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        for (String[] part : new String[][] {{"deployment-name", "acc-t35"}, {"tenant-id", tenant}}) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(part[0])
                    .append("\"\r\n\r\n").append(part[1]).append("\r\n");
        }
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"data\"; filename=\"")
                .append(process).append(".bpmn\"\r\nContent-Type: application/xml\r\n\r\n").append(bpmn).append("\r\n--")
                .append(boundary).append("--\r\n");
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/engine-rest/deployment/create"))
                .header("Authorization", "Bearer " + AcceptanceIdp.get().token(tenant, "acc-t35-admin",
                        AbstractAcceptanceIT.ADMIN_GROUPS))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("deployment: " + r.body()).isEqualTo(200);
    }

    private void defineInbound() throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("key", definition);
        def.put("name", "Bill submitted (T35)");
        def.put("startProcessKey", process);
        def.put("uniqueStart", true);
        def.put("businessKeyPointer", "/billId");
        http.call("POST", "/api/v1/events/definitions", def);
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("key", channel);
        ch.put("name", "Bill portal (T35)");
        ch.put("direction", "INBOUND");
        ch.put("transport", "REST");
        ch.put("eventDefinitionKeys", List.of(definition));
        ch.put("active", true);
        http.call("POST", "/api/v1/events/channels", ch);
    }

    // ------------------------------------------------------------------ broker, worker

    private void publish(String messageId, String bill) {
        broker.add(new BrokerMessage(broker.size(), messageId, bill));
    }

    /** Delivers the log from {@code from}; returns the count per PublishResult status. */
    private Map<String, Integer> consume(int from) throws Exception {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int o = from; o < broker.size(); o++) {
            BrokerMessage m = broker.get(o);
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("id", m.messageId());
            ce.put("source", SOURCE);
            ce.put("type", definition);
            ce.put("specversion", "1.0");
            ce.put("data", Map.of("billId", m.billId(), "amount", 50_000));
            JsonNode r = http.call("POST", "/api/v1/events/" + channel, ce);
            result.merge(r.path("status").asText(), 1, Integer::sum);
            consumerOffset = o + 1;
        }
        return result;
    }

    private JsonNode historic(String bill) throws Exception {
        return http.call("GET", "/engine-rest/history/process-instance?processDefinitionKey=" + process
                + "&processInstanceBusinessKey=" + bill + "&tenantIdIn=" + tenant, null);
    }

    private void awaitStarted(String... bills) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end) {
            boolean all = true;
            for (String b : bills) {
                all &= historic(b).size() >= 1;
            }
            if (all) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("bills " + List.of(bills) + " not started within 60 s");
    }

    private List<JsonNode> fetch(int max) throws Exception {
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("name", TOPIC);
        topic.put("lockDurationMs", LOCK_MS);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", "acc-t35-worker");
        body.put("topics", List.of(topic));
        body.put("maxTasks", max);
        List<JsonNode> out = new ArrayList<>();
        http.call("POST", "/api/v1/work/fetch-and-lock", body).forEach(out::add);
        return out;
    }

    /** Pays downstream under the work's operationId (as an SDK worker forwards it) and completes. Returns the bill. */
    private String payAndComplete(JsonNode w) throws Exception {
        String bill = w.path("businessKey").asText();
        String ref = downstream.pay(w.path("operationId").asText(), bill);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("workerId", "acc-t35-worker");
        done.put("lockVersion", w.path("lockVersion").asLong());
        done.put("attemptId", w.path("attemptId").asText());
        done.put("variables", Map.of("paymentRef", ref));
        http.call("POST", "/api/v1/work/" + w.path("id").asText() + "/complete", done);
        return bill;
    }

    // ------------------------------------------------------------------ the scenario

    @Test
    void restoreWithNewerBrokerMessages() throws Exception {
        assumeTrue("postgres".equals(AcceptanceDatabase.name()), "T35 snapshot/restore tooling is PostgreSQL only");
        psql("CREATE DATABASE " + db);
        startServer();
        deploy();
        defineInbound();

        // ---------------- phase 1: before the snapshot
        publish("msg-b1", "b1");
        publish("msg-b2", "b2");
        publish("msg-b3", "b3");
        assertThat(consume(consumerOffset)).isEqualTo(Map.of("ACCEPTED", 3));
        awaitStarted("b1", "b2", "b3");
        String paidBefore = payAndComplete(fetch(1).get(0));
        JsonNode inFlight = fetch(1).get(0);
        downstream.pay(inFlight.path("operationId").asText(), inFlight.path("businessKey").asText());

        // ---------------- snapshot (the payment of inFlight happened, its completion did not)
        int snapshotOffset = consumerOffset;
        snapshot();

        // ---------------- phase 2: after the snapshot (lost by the restore, except downstream + broker)
        payAndComplete(inFlight);
        payAndComplete(fetch(1).get(0));
        publish("msg-b2", "b2");   // broker redelivery of an old message
        publish("msg-b4", "b4");
        publish("msg-b5", "b5");
        assertThat(consume(consumerOffset)).as("redelivery dropped before the restore as well")
                .isEqualTo(Map.of("ACCEPTED", 2, "DUPLICATE", 1));
        awaitStarted("b4", "b5");
        payAndComplete(fetch(1).get(0));
        assertThat(downstream.paymentsByBill().keySet()).hasSize(4).contains(paidBefore);

        // ---------------- restore
        restore();
        Thread.sleep(LOCK_MS + 2_000);   // the lease held at the snapshot expires on the restored database

        // ---------------- replay + worker
        Map<String, Integer> replay1 = consume(Math.max(0, snapshotOffset - 1));
        awaitStarted("b4", "b5");
        Map<String, Integer> replay2 = consume(0);
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<JsonNode> w = fetch(5);
            if (w.isEmpty()) {
                if (runs.size() >= 4) {
                    break;
                }
                Thread.sleep(1_000);   // the lease sweeper may still be releasing the restored lease
                continue;
            }
            for (JsonNode x : w) {
                runs.add(payAndComplete(x));
            }
        }

        // ---------------- checks
        assertThat(replay1.getOrDefault("DUPLICATE", 0)).as("pre-snapshot message in the overlap is a duplicate")
                .isGreaterThanOrEqualTo(1);
        assertThat(replay2).as("second replay: every message is a duplicate").isEqualTo(Map.of("DUPLICATE", broker.size()));
        Map<String, String> pidByBill = new TreeMap<>();
        for (String b : List.of("b1", "b2", "b3", "b4", "b5")) {
            JsonNode h = historic(b);
            assertThat(h.size()).as("exactly one business start for " + b).isEqualTo(1);
            assertThat(h.get(0).path("state").asText()).as(b + " ended (worker runs " + runs + ")").isEqualTo("COMPLETED");
            pidByBill.put(b, h.get(0).path("id").asText());
        }
        Map<String, Long> paid = downstream.paymentsByBill();
        assertThat(paid).as("no bill paid twice (operationId from the business key)").hasSize(5)
                .allSatisfy((bill, n) -> assertThat(n).isEqualTo(1L));
        JsonNode open = http.call("GET", "/api/v1/ops/reconciliation?state=OPEN", null);
        assertThat(open.size()).as("no open reconciliation item: " + open).isZero();
        // observation only: the operation ledger must hold one SUCCEEDED operation per bill with the downstream's ref
        for (Map.Entry<String, String> e : pidByBill.entrySet()) {
            List<String[]> ops = ledgerRows(e.getValue());
            assertThat(ops).as("one ledger operation for " + e.getKey()).hasSize(1);
            assertThat(ops.get(0)[1]).as(e.getKey() + " ledger state").isEqualTo("SUCCEEDED");
            assertThat(ops.get(0)[2]).as(e.getKey() + " ledger outcome carries the downstream paymentRef")
                    .contains(downstream.refByOp.get(ops.get(0)[0]));
        }
    }
}
