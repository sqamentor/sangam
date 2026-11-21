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
            seedAndVerify(ctx, Earlier.NOTHING);
        }
    }

    /**
     * EV-M-0932, the state of demo.ifms.dev: an earlier loader run (demo-021498ea60d1, EV-M-0923) sent
     * {@code POST /admin/org-units} for PAO-OFFICE while GOI.FIN had no level types and got 409 UNKNOWN_LEVEL. That
     * answer was stored (before E-28) as FAILED_FINAL under the request's Idempotency-Key and replayed to every
     * later request with the same key and body. The loader derived the key from tenant and object only, so after
     * {@code PUT /admin/org-levels} succeeded on demo-0c3ef79140fa its org unit request still got the stored 409.
     * A new run of the seed is a new command: it must load the tenant whatever an earlier run was answered.
     */
    @Test
    void theSeedLoadsAfterAnEarlierLoaderRunWasRefusedTheOrgUnit() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap)) {
            seedAndVerify(ctx, Earlier.ORG_UNIT_REFUSED);
        }
    }

    /**
     * EV-M-0932, demo.ifms.dev exactly as IFMS read it on 2026-11-20T18:30Z: level OFFICE defined (the bac3d46b
     * loader's own row, COMPLETED 200), no org unit, and the org unit refusal of 2026-11-19 past its response
     * retention (COMPACTED, 409: the old key now gets a bare 409 without body). The seed must load this tenant.
     */
    @Test
    void theSeedLoadsTheTenantAsItIsOnDemoIfmsDev() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap)) {
            seedAndVerify(ctx, Earlier.AS_ON_LIVE);
        }
    }

    /**
     * EV-M-0932 (c): a run that stops half way (here the connection answers 503 from the fourth seat on) leaves the
     * level types, the org unit, the roles and three seats. The next run is a new command with new keys: it finds
     * what is there (UNIT_EXISTS, SEAT_EXISTS), creates the rest, and a third run changes nothing.
     */
    @Test
    void aHalfFinishedRunIsResumedByTheNextRun() throws Exception {
        DemoComposeFragmentStartupTest.Keys keys = new DemoComposeFragmentStartupTest.Keys(dir);
        Map<String, String> bootstrap = Map.of("SANGAM_KEYS_BOOTSTRAP", "true",
                "SANGAM_KEYS_FINGERPRINT_EXPORT", dir.resolve("key-fingerprints.txt").toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        try (ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, bootstrap)) {
            String port = ctx.getEnvironment().getRequiredProperty("local.server.port");
            String api = "http://localhost:" + port + "/api/v1";
            String admin = token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
            Seed plan = seed(List.of("--dry-run"), Map.of());
            Matcher m = Pattern.compile("sangam: assignment (\\S+) -> (\\S+) SUBSTANTIVE").matcher(plan.out);
            while (m.find()) {
                get(api + "/me", token(m.group(1), List.of("sangam-user")));
            }
            com.sun.net.httpserver.HttpServer cut = cutAfterThreeSeats("http://localhost:" + port);
            try {
                Seed broken = seed(List.of(), Map.of("SANGAM_API_BASE",
                        "http://localhost:" + cut.getAddress().getPort() + "/api/v1", "SANGAM_ADMIN_TOKEN", admin));
                assertTrue(broken.exit != 0, "the cut run stops:\n" + broken.out);
                assertTrue(broken.out.contains("/admin/seats -> 503"), broken.out);
            } finally {
                cut.stop(0);
            }
            assertEquals(3, items(get(api + "/admin/seats?orgUnitId=" + OFFICE + "&size=100", admin)).size(),
                    "the half-finished run left three seats");
            seedAndVerify(ctx, Earlier.NOTHING);
            long existing = seedRuns.get(0).out.lines().filter(l -> l.matches("  (org unit|seat) \\S+: exists")).count();
            long created = seedRuns.get(0).out.lines().filter(l -> l.matches("  seat \\S+: created")).count();
            assertEquals(4, existing, "the org unit and three seats were found:\n" + seedRuns.get(0).out);
            assertEquals(7, created, "the other seven seats were created:\n" + seedRuns.get(0).out);
        }
    }

    /** The two runs of the last {@link #seedAndVerify} (first, rerun), for a test that looks at what they printed. */
    private static final List<Seed> seedRuns = new ArrayList<>();

    /**
     * A plain forwarder to the server that answers 503 to the fourth and every later {@code POST /admin/seats}
     * without forwarding it: a connection that drops in the middle of a seed run.
     */
    private static com.sun.net.httpserver.HttpServer cutAfterThreeSeats(String server) throws IOException {
        java.util.concurrent.atomic.AtomicInteger seats = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer proxy = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        proxy.createContext("/", x -> {
            try {
                byte[] in = x.getRequestBody().readAllBytes();
                if (x.getRequestMethod().equals("POST") && x.getRequestURI().getPath().endsWith("/admin/seats")
                        && seats.incrementAndGet() > 3) {
                    x.sendResponseHeaders(503, -1);
                    return;
                }
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server + x.getRequestURI()))
                        .method(x.getRequestMethod(), in.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(in));
                for (String h : List.of("Authorization", "Content-Type", "Idempotency-Key")) {
                    if (x.getRequestHeaders().getFirst(h) != null) {
                        b.header(h, x.getRequestHeaders().getFirst(h));
                    }
                }
                HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                for (String h : List.of("Content-Type", "Idempotent-Replayed")) {
                    r.headers().firstValue(h).ifPresent(v -> x.getResponseHeaders().set(h, v));
                }
                x.sendResponseHeaders(r.statusCode(), r.body().length == 0 ? -1 : r.body().length);
                if (r.body().length > 0) {
                    x.getResponseBody().write(r.body());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                x.sendResponseHeaders(502, -1);
            } finally {
                x.close();
            }
        });
        proxy.start();
        return proxy;
    }

    /**
     * The whole check against a started server (this class: H2 file database; {@code PresentationSeedOnLaneDatabaseIT}:
     * the IT lane's PostgreSQL, migrated and then started in validate mode, as on demo.ifms.dev).
     *
     * @param earlier what an earlier loader run left before the seed runs (EV-M-0932)
     */
    static void seedAndVerify(ConfigurableApplicationContext ctx, Earlier earlier) throws Exception {
        {
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
            // SANGAM_DEMO_SANDBOXTENANTS=GOI.FIN (the fragment): /me says sandbox=true for this tenant
            assertTrue(me.path("sandbox").asBoolean(false), "sandbox=true for GOI.FIN: " + me);
            JsonNode tenant = get(api + "/admin/tenants/" + TENANT, admin);
            assertEquals("CREATED", tenant.path("status").asText(), tenant.toString());

            // every seat holder signs in once (realm oneifms token: pack role + sangam-user); JIT creates the person
            for (Map.Entry<String, String> p : postings.entrySet()) {
                get(api + "/me", token(p.getKey(), List.of("sangam-user")));
            }

            if (earlier != Earlier.NOTHING) {
                earlierLoader(ctx, api, admin, earlier);
            }

            Map<String, String> env = Map.of("SANGAM_API_BASE", api, "SANGAM_ADMIN_TOKEN", admin);
            Seed first = seed(List.of(), env);
            seedRuns.clear();
            seedRuns.add(first);
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
            seedRuns.add(second);
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

    /** What an earlier loader run left on the tenant before the seed under test runs. */
    enum Earlier {
        /** A tenant no loader has touched. */
        NOTHING,
        /** The org unit request of the loader up to bac3d46b was refused (409 UNKNOWN_LEVEL); since E-28 it is not stored. */
        ORG_UNIT_REFUSED,
        /**
         * demo.ifms.dev as read on 2026-11-20 (IFMS check 3): the refusal stored, level OFFICE defined by the bac3d46b
         * loader under its fixed key (COMPLETED 200), and the refusal past its response retention: COMPACTED, 409.
         */
        AS_ON_LIVE
    }

    /**
     * The requests exactly as the loader sent them up to bac3d46b (seed_presentation.py: fixed keys
     * {@code idem(tenant, "unit", office)} = UUIDv5(NAMESPACE_URL, "sangam-presentation/GOI.FIN/unit/PAO-OFFICE") and
     * {@code idem(tenant, "org-levels", "OFFICE")}), in the order demo.ifms.dev saw them: the org unit on a tenant with
     * no level types (409 UNKNOWN_LEVEL, since E-28 not stored, so the same key runs again); for
     * {@link Earlier#AS_ON_LIVE} then the level types (200) and the row the code before E-28 had stored for the
     * refusal, past its response retention (COMPACTED 409, EV-M-0932). The product no longer writes that row, so the
     * test inserts it as legacy data.
     */
    private static void earlierLoader(ConfigurableApplicationContext ctx, String api, String admin, Earlier earlier)
            throws Exception {
        String parent = null;
        for (JsonNode o : JSON.readTree(PACK_SEED.toFile()).path("offices")) {
            if (OFFICE.equals(o.path("id").asText())) {
                parent = o.path("parent").asText("").isEmpty() ? null : o.path("parent").asText();
            }
        }
        Map<String, Object> unit = new java.util.LinkedHashMap<>();
        unit.put("id", OFFICE);
        unit.put("code", OFFICE);
        unit.put("name", "Jaipur");
        unit.put("level", "OFFICE");
        unit.put("parentId", parent);
        String unitKey = uuid5Url("sangam-presentation/" + TENANT + "/unit/" + OFFICE);
        assertEquals("04851e5a-405b-536e-8b22-3bcb1f941f3d", unitKey, "the key of the stored row on demo.ifms.dev");
        // E-28 (SANGAM.md X.2.2.4 step 3): 409 UNKNOWN_LEVEL depends on server state and is not stored, so the same
        // key runs the command again; the second answer is a fresh refusal, not a replay
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> r = send("POST", api + "/admin/org-units", admin, unitKey, unit);
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("UNKNOWN_LEVEL", JSON.readTree(r.body()).path("code").asText(), r.body());
            assertTrue(r.headers().firstValue("Idempotent-Replayed").isEmpty(),
                    "a state-dependent 409 is not replayed: " + r.headers().map());
        }
        assertEquals(0, idempotencyRows(ctx, "admin.orgunit.create", unitKey), "the refusal is not stored");
        if (earlier != Earlier.AS_ON_LIVE) {
            return;
        }
        HttpResponse<String> levels = send("PUT", api + "/admin/org-levels", admin,
                uuid5Url("sangam-presentation/" + TENANT + "/org-levels/OFFICE"), Map.of("tenantId", TENANT, "levels", List.of("OFFICE")));
        assertEquals(200, levels.statusCode(), levels.body());

        // demo.ifms.dev still holds the row that the code before E-28 wrote for the refusal, now past its response
        // retention (COMPACTED 409). The product no longer writes such a row, so the test puts that legacy row in
        // place, with the fingerprint the IdempotencyFilter computes for this request and the principal and
        // fingerprint version of the loader's own org-levels row.
        var match = ctx.getBean(org.sangam.rest.web.CommandCatalog.class).match("POST", "/api/v1/admin/org-units")
                .orElseThrow();
        assertEquals("admin.orgunit.create", match.command().commandType());
        Map<String, Object> semantic = new java.util.LinkedHashMap<>();
        semantic.put("query", new java.util.TreeMap<String, Object>());
        semantic.put("ifMatch", null);
        semantic.put("actingForSeat", null);
        byte[] fp = org.sangam.core.layer.IdempotencyStore.fingerprint(match.command().commandType(), "POST",
                match.command().pathTemplate(), match.pathParams(), semantic,
                JSON.readValue(JSON.writeValueAsString(unit), Object.class));
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection()) {
            c.setAutoCommit(true);
            try (var ps = c.prepareStatement("INSERT INTO IDEMPOTENCY_RECORD(tenant_id, command_type, idem_key, fp_version,"
                    + " fingerprint, principal_id, state, owner_node, claim_epoch, claim_expires_at, created_at, http_status,"
                    + " completed_at, response_expires_at, key_expires_at)"
                    + " SELECT tenant_id, 'admin.orgunit.create', ?, fp_version, ?, principal_id, 'COMPACTED', owner_node, 1,"
                    + " ?, ?, 409, ?, ?, ? FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type='admin.orglevels.define'")) {
                java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
                ps.setString(1, unitKey);
                ps.setBytes(2, fp);
                ps.setObject(3, now.minusDays(2));
                ps.setObject(4, now.minusDays(2));
                ps.setObject(5, now.minusDays(2));
                ps.setObject(6, now.minusDays(1));
                ps.setObject(7, now.plusDays(28));
                ps.setString(8, TENANT);
                assertEquals(1, ps.executeUpdate(), "the legacy refusal row");
            }
        }
        List<String> rows = new ArrayList<>();
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement("SELECT command_type, state, http_status FROM IDEMPOTENCY_RECORD WHERE tenant_id=?"
                     + " AND command_type LIKE 'admin.org%' ORDER BY command_type")) {
            ps.setString(1, TENANT);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + " " + rs.getString(2) + " " + rs.getInt(3));
                }
            }
        }
        assertEquals(List.of("admin.orglevels.define COMPLETED 200", "admin.orgunit.create COMPACTED 409"), rows,
                "the idempotency rows of GOI.FIN as on demo.ifms.dev (IFMS check 3, Q4)");
        // a compacted row answers with its status only: no body, no code (IdempotencyStore COMPACTED, IdempotencyFilter.replay)
        HttpResponse<String> compacted = send("POST", api + "/admin/org-units", admin, unitKey, unit);
        assertEquals(409, compacted.statusCode(), compacted.body());
        assertEquals("", compacted.body(), "a compacted answer has no body");
        assertEquals("compacted", compacted.headers().firstValue("Idempotent-Replayed").orElse(null));
        assertEquals(0, items(get(api + "/admin/org-units", admin)).size(), "no org unit yet, as on demo.ifms.dev");
    }

    private static int idempotencyRows(ConfigurableApplicationContext ctx, String commandType, String key) throws Exception {
        try (var c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement("SELECT COUNT(*) FROM IDEMPOTENCY_RECORD WHERE tenant_id=? AND command_type=?"
                     + " AND idem_key=?")) {
            ps.setString(1, TENANT);
            ps.setString(2, commandType);
            ps.setString(3, key);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static HttpResponse<String> send(String method, String url, String token, String key, Object body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Python's {@code uuid.uuid5(uuid.NAMESPACE_URL, name)} (RFC 4122 version 5: SHA-1 of namespace and name). */
    static String uuid5Url(String name) throws java.security.NoSuchAlgorithmException {
        java.util.UUID ns = java.util.UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
        sha1.update(java.nio.ByteBuffer.allocate(16).putLong(ns.getMostSignificantBits())
                .putLong(ns.getLeastSignificantBits()).array());
        byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        h[6] = (byte) ((h[6] & 0x0f) | 0x50);
        h[8] = (byte) ((h[8] & 0x3f) | 0x80);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h, 0, 16);
        return new java.util.UUID(b.getLong(), b.getLong()).toString();
    }

    static String token(String user, List<String> roles) throws Exception {
        return DemoComposeFragmentStartupTest.RealmIdp.get().tokenWithoutSub(TENANT, user, roles);
    }

    static JsonNode get(String url, String token) throws Exception {
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

    record Seed(int exit, String out) {
    }

    /** Runs the seed as IFMS does ({@code python -I}); no Keycloak settings, so only the Sangam part runs. */
    private static Seed seed(List<String> args, Map<String, String> env) throws IOException, InterruptedException {
        return script(SEED, args, env);
    }

    /** Runs a script of deploy/presentation as IFMS does ({@code python -I}), with only the given settings. */
    static Seed script(Path script, List<String> args, Map<String, String> env) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(python(), "-I", script.toString()));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("KEYCLOAK_") || k.startsWith("SANGAM_"));
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(15, TimeUnit.MINUTES), script.getFileName() + " finished");
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
