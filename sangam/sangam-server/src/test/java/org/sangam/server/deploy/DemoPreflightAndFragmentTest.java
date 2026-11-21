// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-27, the points of the loader lane that are settled by the scripts and the fragment themselves.
 * <ul>
 *   <li>{@code demo_preflight.py money} read the tenant's environment bindings named {@code pfms.payment}. A binding
 *       is a named endpoint of a package manifest and is read only when a package is activated
 *       ({@code JdbcPackageAdapter}); no connector call is routed by it. What keeps a payment inside Sangam on the
 *       hosted server is the sandbox tenant list ({@code sangam.demo.sandbox-tenants}, {@code DemoSandbox.routing}),
 *       which writes no binding, so the check stopped on the very server it was written for. The fact a tenant
 *       administrator can read is {@code GET /me}: {@code sandbox: true} (the value the UI's Sandbox badge shows).</li>
 *   <li>{@code SANGAM_ENV_LABEL} of sangam-front becomes {@code "environment"} in {@code /sangam/config.json}, which
 *       any browser can read; the UI shows it nowhere and uses it only to choose the sign-in mode ({@code dev} or
 *       not, config.ts). deploy/presentation/README.md says to leave it empty.</li>
 *   <li>The compliance log archive: the fragment does not name it and does not have to. The server image sets
 *       {@code SANGAM_COMPLIANCE_LOG_ARCHIVE} to a directory it creates for its own user under /var/lib/sangam, and
 *       the fragment mounts its state volume there.</li>
 * </ul>
 */
class DemoPreflightAndFragmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DEPLOY = Path.of("..", "deploy").toAbsolutePath().normalize();
    private static final Path PREFLIGHT = DEPLOY.resolve("presentation").resolve("demo_preflight.py");
    private static final Path FRAGMENT = DEPLOY.resolve("demo").resolve("sangam-compose-fragment.yml");

    @TempDir
    Path dir;

    private HttpServer api;
    private volatile Map<String, Object> me = Map.of();
    private volatile List<Map<String, Object>> bindings = List.of();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startApi() throws Exception {
        api = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        api.createContext("/api/v1", x -> {
            try {
                String path = x.getRequestURI().getPath().substring("/api/v1".length());
                asked.add(path);
                Object out = path.equals("/me") ? me : path.equals("/env-bindings") ? bindings : Map.of("code", "NOT_FOUND");
                byte[] body = JSON.writeValueAsBytes(out);
                x.getResponseHeaders().set("Content-Type", "application/json");
                x.sendResponseHeaders(path.equals("/me") || path.equals("/env-bindings") ? 200 : 404, body.length);
                x.getResponseBody().write(body);
            } finally {
                x.close();
            }
        });
        api.start();
    }

    @AfterEach
    void stopApi() {
        api.stop(0);
    }

    /** The hosted server as it is: GOI.FIN is a sandbox tenant and has no environment binding at all. */
    @Test
    void moneyPassesOnASandboxTenantThatHasNoEnvironmentBinding() throws Exception {
        me = Map.of("tenantId", "GOI.FIN", "sandbox", true);
        PresentationSeedAgainstServerTest.Seed run = money(Map.of());
        assertEquals(0, run.exit(), run.out());
        assertTrue(run.out().contains("OK: tenant GOI.FIN is a sandbox tenant"), run.out());
        assertTrue(asked.contains("/me"), "the check reads GET /me: " + asked);
    }

    /** A binding that carries the word "sandbox" proves nothing: the tenant is not on the server's sandbox list. */
    @Test
    void moneyStopsOnATenantThatIsNotASandboxTenantWhateverItsBindingsSay() throws Exception {
        me = Map.of("tenantId", "GOI.FIN", "sandbox", false);
        bindings = List.of(Map.of("name", "pfms.payment", "env", "sandbox", "state", "APPROVED"));
        PresentationSeedAgainstServerTest.Seed run = money(Map.of());
        assertEquals(1, run.exit(), run.out());
        assertTrue(run.out().contains("STOP: tenant GOI.FIN is not a sandbox tenant"), run.out());
    }

    /** An answer without the field (an older server) is not a yes. */
    @Test
    void moneyStopsWhenTheServerDoesNotSayTheTenantIsASandboxTenant() throws Exception {
        me = Map.of("tenantId", "GOI.FIN");
        PresentationSeedAgainstServerTest.Seed run = money(Map.of());
        assertEquals(1, run.exit(), run.out());
        assertTrue(run.out().contains("STOP:"), run.out());
    }

    /** Unchanged: a payment connector URL set on the server stops the load, sandbox tenant or not. */
    @Test
    void moneyStopsWhenAPaymentConnectorUrlIsSetOnTheServer() throws Exception {
        me = Map.of("tenantId", "GOI.FIN", "sandbox", true);
        Path env = dir.resolve("server-env");
        Files.writeString(env, "TZ=UTC\nSANGAM_CONNECTORS_PAYGOV_BASE_URL=\nSANGAM_CONNECTORS_PFMS_DIRECTORY=/srv/pfms\n", StandardCharsets.UTF_8);
        PresentationSeedAgainstServerTest.Seed run = money(Map.of("SANGAM_CONNECTORS_ENV", env.toString()));
        assertEquals(1, run.exit(), run.out());
        assertTrue(run.out().contains("STOP: SANGAM_CONNECTORS_PFMS_DIRECTORY is set on the server"), run.out());
    }

    private PresentationSeedAgainstServerTest.Seed money(Map<String, String> more) throws Exception {
        Map<String, String> env = new LinkedHashMap<>(more);
        env.put("SANGAM_API_BASE", "http://localhost:" + api.getAddress().getPort() + "/api/v1");
        env.put("SANGAM_ADMIN_TOKEN", "token-of-the-test");
        return PresentationSeedAgainstServerTest.script(PREFLIGHT, List.of("money"), env);
    }

    /**
     * The label reaches every browser in /sangam/config.json and is shown nowhere; the README of the front says to
     * leave it empty, and the image's own default is empty. No word is chosen for it here.
     */
    @Test
    void theFrontOfTheFragmentCarriesNoEnvironmentLabel() throws Exception {
        Object label = service("sangam-front").get("SANGAM_ENV_LABEL");
        assertEquals("", label == null ? "" : String.valueOf(label),
                "SANGAM_ENV_LABEL of sangam-front (config.json \"environment\"): empty, as deploy/presentation/README.md says");
        String readme = Files.readString(DEPLOY.resolve("presentation").resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("| `SANGAM_ENV_LABEL` | empty |"), "the README still documents the label as empty by default");
    }

    /** Green before and after: the register's point (logs in a relative data/compliance-logs) does not hold for the image. */
    @Test
    void theComplianceLogArchiveOfTheImageIsOnTheStateVolumeOfTheFragment() throws Exception {
        String dockerfile = Files.readString(DEPLOY.resolve("docker").resolve("server.Dockerfile"), StandardCharsets.UTF_8);
        String root = "/var/lib/sangam/compliance-logs";
        long stages = dockerfile.lines().filter(l -> l.strip().startsWith("SANGAM_COMPLIANCE_LOG_ARCHIVE=" + root)).count();
        assertEquals(2, stages, "both runtime stages of server.Dockerfile set SANGAM_COMPLIANCE_LOG_ARCHIVE=" + root);
        assertTrue(dockerfile.contains("mkdir -p /layout/opt/sangam/config /layout" + root), "the image creates the directory");
        assertTrue(dockerfile.contains("chown -R 10001:0 /layout/opt/sangam /layout/var/lib/sangam"), "for its own user");
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) services().get("sangam-server");
        @SuppressWarnings("unchecked")
        List<String> volumes = (List<String>) server.get("volumes");
        assertTrue(volumes.contains("sangam-server-state:/var/lib/sangam"), "the fragment mounts the state volume there: " + volumes);
        Object named = service("sangam-server").get("SANGAM_COMPLIANCE_LOG_ARCHIVE");
        assertTrue(named == null || String.valueOf(named).startsWith("/var/lib/sangam/"),
                "a root the fragment names must be on the state volume too: " + named);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws Exception {
        Map<String, Object> doc = new Yaml().load(Files.readString(FRAGMENT, StandardCharsets.UTF_8));
        return (Map<String, Object>) doc.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) services().get(name)).get("environment");
    }
}
