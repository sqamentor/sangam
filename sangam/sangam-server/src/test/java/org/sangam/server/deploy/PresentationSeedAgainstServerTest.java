// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EV-M-0923 (IFMS go-live hold): the presentation seed (deploy/presentation/seed_presentation.py, the Sangam part) runs
 * unchanged, as IFMS runs it, against a Sangam server built from this code in production mode with the demo fragment's
 * settings (the {@link DemoComposeFragmentStartupTest} harness): tenant GOI.FIN created by FirstAdminBootstrap, the
 * administrator Rajendra Prasad Sharma's token (realm oneifms shape, no sub), every seat holder signed in once (JIT
 * person). It must define the pack roles, create the 10 seats of the IFMS pack seed and the 8 SUBSTANTIVE postings, all
 * through the product's REST API, and a second run must change nothing.
 * <p>
 * Fail-first: the seed of 78d350bb (feat/demo-data) sent {@code PUT /admin/roles/DDO}, which no controller maps (the
 * role id is a query parameter since decision 393: {@code PUT /admin/roles/by-role-id?roleId=}), so the run stopped
 * with 404 after the org unit and created 0 seats and 0 postings, exactly as on demo-021498ea60d1.
 * <p>
 * Needs a Python 3 interpreter on the PATH ({@code python3}, else {@code python}), as the seed itself does.
 */
class PresentationSeedAgainstServerTest {

    private static final String TENANT = "GOI.FIN";
    private static final String OFFICE = "PAO-OFFICE";
    private static final Path SEED = Path.of("..", "deploy", "presentation", "seed_presentation.py").toAbsolutePath().normalize();
    private static final Path PACK_SEED = Path.of("..", "sangam-packs", "src", "main", "resources", "packs", "ifms-gpf", "seed.json")
            .toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @Test
    void theSeedCreatesRolesSeatsAndPostingsThroughTheApiAndARerunChangesNothing() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap)) {
            String api = "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
            String admin = token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));

            // the plan as the seed itself prints it (one source: generate_realm.py PRESENTATION_USERS, ifms-gpf seed)
            Seed plan = seed(List.of("--dry-run"), Map.of());
            assertEquals(0, plan.exit, plan.out);
            Map<String, String> postings = new TreeMap<>();   // person -> seat
            Matcher m = Pattern.compile("sangam: assignment (\\S+) -> (\\S+) SUBSTANTIVE").matcher(plan.out);
            while (m.find()) {
                postings.put(m.group(1), m.group(2));
            }
            assertEquals(8, postings.size(), "the presentation has 8 seat holders: " + plan.out);

            // /me of the tenant administrator: tenant GOI.FIN, the tenant-admin group, the ADMIN persona
            JsonNode me = get(api + "/me", admin);
            assertEquals(TENANT, me.path("tenantId").asText(), me.toString());
            assertTrue(texts(me.path("groups")).contains("sangam-tenant-admin"), me.toString());
            assertTrue(texts(me.path("roles")).contains("ADMIN"), me.toString());
            assertFalse(texts(me.path("roles")).contains("PLATFORM_ADMIN"), me.toString());
            assertTrue(texts(me.path("permissions")).containsAll(List.of("admin.write", "policy.admin", "tenant.admin")),
                    me.toString());
            JsonNode tenant = get(api + "/admin/tenants/" + TENANT, admin);
            assertEquals("CREATED", tenant.path("status").asText(), tenant.toString());

            // every seat holder signs in once (realm oneifms token: pack role + sangam-user); JIT creates the person
            for (Map.Entry<String, String> p : postings.entrySet()) {
                get(api + "/me", token(p.getKey(), List.of("sangam-user")));
            }

            Map<String, String> env = Map.of("SANGAM_API_BASE", api, "SANGAM_ADMIN_TOKEN", admin);
            Seed first = seed(List.of(), env);
            assertEquals(0, first.exit, "the seed stopped:\n" + first.out);
            assertFalse(first.out.contains("not yet signed in"), first.out);
            String afterFirst = state(api, admin, postings);
            // every pack role is defined (no role listing endpoint exists: read-only check of SEC_ROLE)
            TreeSet<String> roles = new TreeSet<>();
            JSON.readTree(PACK_SEED.toFile()).path("roles").forEach(r -> roles.add(r.path("code").asText()));
            assertFalse(roles.isEmpty());
            try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
                 var ps = c.prepareStatement("SELECT role_id FROM SEC_ROLE WHERE tenant_id=?")) {
                ps.setString(1, TENANT);
                TreeSet<String> stored = new TreeSet<>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stored.add(rs.getString(1));
                    }
                }
                assertTrue(stored.containsAll(roles), "pack roles " + roles + " defined: " + stored);
            }

            Seed second = seed(List.of(), env);
            assertEquals(0, second.exit, "the rerun stopped:\n" + second.out);
            assertFalse(second.out.contains(": created"), "a rerun creates nothing:\n" + second.out);
            assertEquals(afterFirst, state(api, admin, postings), "a rerun changes nothing");
        }
    }

    /**
     * The seats of the office (each with its pack role and reporting line) and each seat's active SUBSTANTIVE
     * holders with assignment ids, read through the API; asserts the 10 seats and the 8 postings.
     */
    private static String state(String api, String admin, Map<String, String> postings) throws Exception {
        // a seat's pack role is its designation (as onboarding's installPackSeeds creates pack seats)
        Map<String, String> packRole = new TreeMap<>();
        for (JsonNode s : JSON.readTree(PACK_SEED.toFile()).path("seats")) {
            packRole.put(s.path("id").asText(), s.path("role").asText());
        }
        JsonNode seats = get(api + "/admin/seats?orgUnitId=" + OFFICE + "&size=100", admin);
        List<String> rows = new ArrayList<>();
        Map<String, String> found = new TreeMap<>();
        for (JsonNode s : items(seats)) {
            found.put(s.path("id").asText(), s.path("designation").asText());
            rows.add("seat " + s.path("id").asText() + " designation=" + s.path("designation").asText() + " reportsTo="
                    + s.path("reportsToSeatId").asText(null) + " active=" + s.path("active").asText());
            assertTrue(s.path("active").asBoolean(), "seat " + s.path("id").asText() + " is active");
        }
        assertEquals(10, rows.size(), "the 10 seats of the IFMS pack seed: " + rows);
        assertEquals(packRole, found, "every seat of the pack seed, each with its pack role as designation");
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        int held = 0;
        for (Map.Entry<String, String> p : postings.entrySet()) {
            JsonNode a = get(api + "/admin/assignments?seatId=" + enc(p.getValue()) + "&kind=SUBSTANTIVE&activeOn=" + today, admin);
            List<String> holders = new ArrayList<>();
            for (JsonNode x : items(a)) {
                holders.add(x.path("personId").asText() + "#" + x.path("id").asText());
            }
            assertEquals(1, holders.size(), p.getValue() + " has one substantive holder: " + holders);
            assertTrue(holders.get(0).startsWith(p.getKey() + "#"), p.getValue() + " is held by " + p.getKey() + ": " + holders);
            held++;
            rows.add("posting " + p.getValue() + " " + holders);
        }
        assertEquals(8, held);
        rows.sort(null);
        return String.join("\n", rows);
    }

    private static String token(String user, List<String> roles) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles);
    }

    private static JsonNode get(String url, String token) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), "GET " + url + ": " + r.body());
        return JSON.readTree(r.body());
    }

    private static List<JsonNode> items(JsonNode body) {
        JsonNode list = body.isArray() ? body : body.path("items");
        List<JsonNode> out = new ArrayList<>();
        list.forEach(out::add);
        return out;
    }

    private static TreeSet<String> texts(JsonNode array) {
        TreeSet<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record Seed(int exit, String out) {
    }

    /** Runs the seed as IFMS does ({@code python -I}); no Keycloak settings, so only the Sangam part runs. */
    private static Seed seed(List<String> args, Map<String, String> env) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(python(), "-I", SEED.toString()));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("KEYCLOAK_") || k.startsWith("SANGAM_"));
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(5, TimeUnit.MINUTES), "the seed finished");
        return new Seed(p.exitValue(), out);
    }

    private static String python() {
        for (String c : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(c, "-I", "-c", "import sys; assert sys.version_info >= (3, 8)")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return c;
                }
            } catch (IOException | InterruptedException ignored) {
                // try the next name
            }
        }
        throw new AssertionError("no Python 3 interpreter (python3 or python) on the PATH; the seed needs one");
    }
}
