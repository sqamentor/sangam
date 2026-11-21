// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.server.config.KeyAttestation;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Register E-25: on demo.ifms.dev the tenant GOI.FIN, created by FirstAdminBootstrap in production mode, has no
 * workflow pack, so every process start answers 404. The server here is started as there: from the env names of the
 * demo fragment's sangam-server service, production on ({@link DemoComposeFragmentStartupTest}), on an H2 file
 * database that survives a restart; {@link PresentationSeedOnLaneDatabaseIT} runs {@link #liveLike} on PostgreSQL
 * under the fragment's own profiles.
 * <p>
 * What it establishes, each as a {@code PACKS-TENANT | <fact> | <value>} line and an assertion:
 * <ul>
 *   <li>the state as deployed: the tenant exists and serves, no pack is deployed for it, a start is 404, and no call a
 *       tenant administrator can make deploys one;</li>
 *   <li>the two pack settings bind under their environment names, in both spellings, for a tenant id with a dot; a
 *       tenant that is not listed gets nothing and a listed id that is no tenant gets the packs all the same;</li>
 *   <li>a fresh database: the start that creates the tenant also deploys its packs;</li>
 *   <li>the fragment carries the two settings for its first-admins tenant, and a restart from it deploys the packs of
 *       the tenant that already exists (seats, postings and funds untouched), once: a further start deploys nothing
 *       and leaves every open case on the definition it was started on.</li>
 * </ul>
 * The h2 stand-in for the fragment's postgres profile: {@code sangam.packs.deploy-on-start} defaults to true only
 * under profile h2, and there {@code sangam.packs.tenants} falls back to the dev-token tenant, which is the fragment's
 * SANGAM_TENANT_DEFAULT. So "as deployed" sets the postgres default (false) here, and what a name binds to is read
 * from the property the wiring reads, never taken from the fact that packs were deployed.
 * <p>
 * Not silently: a start that leaves the first-admins tenant without a pack says so in the server log, at WARN, with
 * the tenant and the two settings ({@link #NO_PACKS}); a start that deploys them does not.
 */
@ExtendWith(OutputCaptureExtension.class)
class PacksOfBootstrapTenantAgainstServerTest {

    /** The words of the start-up warning for a first-admins tenant without packs (PacksWiring). */
    static final String NO_PACKS = "has no workflow pack deployed";

    static final String TENANT = "GOI.FIN";
    /** The two settings as environment names (Spring's system-environment mapping of the property names). */
    static final String ON = "SANGAM_PACKS_DEPLOY_ON_START";
    static final String TENANTS = "SANGAM_PACKS_TENANTS";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @AfterAll
    static void releaseRealm() {
        DemoComposeFragmentStartupTest.RealmIdp.release();
    }

    @AfterEach
    void sandboxOff() {
        org.sangam.packs.sim.DemoSandbox.install(Set.of());
    }

    // ------------------------------------------------------------------ the state as deployed

    /**
     * The image that is live now (packs code as in bac3d46b), on a database as there: GOI.FIN was created by
     * FirstAdminBootstrap, which does not go through the tenant hook of PacksWiring, and nothing else deployed a pack.
     * None of the calls open to the tenant's administrators deploys one: activation is blocked by the go-live
     * checklist, whose evidence only onboarding records, and onboarding and the pack list are platform operations (the
     * demo has no platform issuer).
     */
    @Test
    void asDeployedTheTenantHasNoPackAndNoCallOfItsAdministratorsDeploysOne(CapturedOutput output) throws Exception {
        Host host = Host.h2(dir.resolve("as-deployed"));
        try (ConfigurableApplicationContext ctx = host.startAsDeployed()) {
            // not silently: the start itself says that the tenant it created has no pack, and how it gets them
            List<String> warned = output.getOut().lines().filter(l -> l.contains(NO_PACKS)).toList();
            fact("as deployed: start-up log lines saying the tenant has no pack", warned.size()
                    + (warned.isEmpty() ? "" : ": " + warned.get(0)));
            assertEquals(1, warned.size(), "one WARN line of the start names the tenant without packs");
            assertTrue(warned.get(0).contains("WARN") && warned.get(0).contains(TENANT)
                    && warned.get(0).contains("sangam.packs.deploy-on-start") && warned.get(0).contains("sangam.packs.tenants"),
                    "at WARN, with the tenant and the two settings: " + warned.get(0));
            String api = api(ctx);
            int shipped = shipped(ctx);
            assertTrue(shipped > 0, "packs are on the class path of the server: " + shipped);
            fact("as deployed: packs shipped in the image", String.valueOf(shipped));
            assertEquals("CREATED", tenantStatus(ctx, TENANT), "FirstAdminBootstrap created the tenant");
            assertEquals(List.of(), installed(ctx, TENANT), "no pack is deployed for the tenant");
            assertEquals(0, definitions(ctx, TENANT), "no process definition of the tenant");
            List<String> chain = chain(ctx, TENANT);
            fact("as deployed: audit chain sec:" + TENANT, chain.toString());
            assertTrue(chain.contains("TENANT_CREATED"), chain.toString());
            assertFalse(chain.contains("PACKS_DEPLOYED"), "the bootstrap did not go through the pack hook: " + chain);

            String admin = token("rajendra.sharma", List.of("sangam-tenant-admin", "sangam-user"));
            String officer = token("kavita.sharma", List.of("DDO", "sangam-user"));

            // the symptom: a start of a pack process
            HttpResponse<String> start = send("POST", api + "/process-instances", officer,
                    Map.of("key", "sangam-ifms-bill", "businessKey", "E-25/as-deployed/1", "variables", Map.of()));
            fact("as deployed: POST /process-instances sangam-ifms-bill", start.statusCode() + " " + problem(start));
            assertEquals(404, start.statusCode(), start.body());

            // the tenant hook fires after createTenant/updateTenant with status ACTIVE: activation by its administrator
            HttpResponse<String> ready = send("POST", api + "/admin/tenants/" + TENANT + "/validate", admin, null);
            assertEquals(200, ready.statusCode(), ready.body());
            JsonNode readiness = JSON.readTree(ready.body());
            fact("as deployed: go-live checklist of the tenant", "required " + readiness.path("required") + ", missing "
                    + readiness.path("missing"));
            assertFalse(readiness.path("ready").asBoolean(), ready.body());
            HttpResponse<String> activate = send("POST", api + "/admin/tenants/" + TENANT + "/activate", admin,
                    Map.of("reason", "E-25: does activation deploy the packs?"));
            fact("as deployed: POST /admin/tenants/" + TENANT + "/activate (tenant administrator)",
                    activate.statusCode() + " " + problem(activate));
            assertEquals(409, activate.statusCode(), activate.body());
            assertEquals("ACTIVATION_BLOCKED", code(activate), activate.body());
            HttpResponse<String> put = send("PUT", api + "/admin/tenants/" + TENANT, admin,
                    Map.of("id", TENANT, "name", TENANT, "status", "ACTIVE"));
            fact("as deployed: PUT /admin/tenants/" + TENANT + " status ACTIVE (tenant administrator)",
                    put.statusCode() + " " + problem(put));
            assertEquals(409, put.statusCode(), put.body());
            assertEquals("ACTIVATION_BLOCKED", code(put), put.body());

            // platform operations: no platform issuer on the demo, so nobody holds them
            HttpResponse<String> list = send("GET", api + "/packs/installed?tenant=" + TENANT, admin, null);
            fact("as deployed: GET /packs/installed (tenant administrator)", list.statusCode() + " " + problem(list));
            assertEquals(403, list.statusCode(), list.body());
            HttpResponse<String> runs = send("GET", api + "/onboarding/runs", admin, null);
            fact("as deployed: GET /onboarding/runs (tenant administrator)", runs.statusCode() + " " + problem(runs));
            assertEquals(403, runs.statusCode(), runs.body());

            assertEquals("CREATED", tenantStatus(ctx, TENANT), "nothing above changed the tenant");
            assertEquals(List.of(), installed(ctx, TENANT), "and nothing above deployed a pack");
            readOnlyCheck(ctx, 0);

            // the other half of the hook: before PackagePort.transition with action deploy. An officer reaches the
            // route (package.read); for a package that does not exist the answer is 404 and no pack is deployed. A
            // real package deploy needs an authored, reviewed and approved package (AJ-06), which the tenant has not.
            HttpResponse<String> transition = send("POST", api + "/packages/e-25-no-such-package/transitions", officer,
                    Map.of("action", "deploy", "reason", "E-25: what does the pack hook do before a package deploy?"));
            int after = installed(ctx, TENANT).size();
            fact("as deployed: POST /packages/<unknown>/transitions action deploy (an officer, role sangam-user)",
                    transition.statusCode() + " " + problem(transition) + "; packs deployed for the tenant afterwards: " + after
                            + " of " + shipped + "; audit chain " + chain(ctx, TENANT));
            assertEquals(404, transition.statusCode(), "no such package: " + transition.body());
            assertEquals(0, after, "a deploy transition of a package that does not exist deploys no pack");
        }
    }

    // ------------------------------------------------------------------ the settings under their environment names

    /**
     * {@code sangam.packs.deploy-on-start} and {@code sangam.packs.tenants} as environment variables. Under profile h2
     * the default deploys (first start), so a name that binds shows when it switches the deployment off, in the
     * spelling with underscores for the dashes and in the one without; the tenant list shows when another id gets the
     * packs and GOI.FIN, not listed, gets none.
     */
    @Test
    void thePackSettingsBindUnderTheirEnvironmentNames() throws Exception {
        int shipped;
        try (ConfigurableApplicationContext ctx = Host.h2(dir.resolve("default")).start(Host.WITHOUT_PACK_SETTINGS)) {
            shipped = shipped(ctx);
            assertEquals(null, ctx.getEnvironment().getProperty("sangam.packs.deploy-on-start"), "not set anywhere");
            assertEquals(null, ctx.getEnvironment().getProperty("sangam.packs.tenants"), "not set anywhere");
            assertEquals(shipped, installed(ctx, TENANT).size(), "profile h2: deploy-on-start defaults to true and the "
                    + "tenants to the dev-token tenant, the fragment's SANGAM_TENANT_DEFAULT");
            fact("h2 stand-in without the settings", shipped + " of " + shipped + " packs for " + TENANT
                    + " (the h2 default; under the fragment's postgres profile the default is false)");
        }
        for (String name : List.of(ON, "SANGAM_PACKS_DEPLOYONSTART")) {
            try (ConfigurableApplicationContext ctx = Host.h2(dir.resolve("off-" + name)).start(env -> {
                Host.WITHOUT_PACK_SETTINGS.accept(env);
                env.put(name, "false");
            })) {
                assertEquals("false", ctx.getEnvironment().getProperty("sangam.packs.deploy-on-start"), name + " binds");
                assertEquals(List.of(), installed(ctx, TENANT), name + "=false switches the start-up deployment off");
                fact("env " + name + "=false", "binds: sangam.packs.deploy-on-start=false, 0 packs for " + TENANT);
            }
        }
        String other = "GOI.OTHER";
        try (ConfigurableApplicationContext ctx = Host.h2(dir.resolve("other")).start(env -> {
            Host.WITHOUT_PACK_SETTINGS.accept(env);
            env.put(ON, "true");
            env.put(TENANTS, other);
        })) {
            assertEquals("true", ctx.getEnvironment().getProperty("sangam.packs.deploy-on-start"));
            assertEquals(other, ctx.getEnvironment().getProperty("sangam.packs.tenants"), TENANTS + " binds");
            assertEquals(List.of(), installed(ctx, TENANT), "a tenant that is not listed gets no pack");
            assertEquals(shipped, installed(ctx, other).size(), "the listed id gets the packs");
            assertEquals(null, tenantStatus(ctx, other), "although no such tenant exists");
            fact("env " + TENANTS + "=" + other + " (no such tenant)", "0 packs for " + TENANT + " (not listed); "
                    + installed(ctx, other).size() + " of " + shipped + " packs deployed under the id " + other
                    + ", which has no tenant row; its audit chain " + chain(ctx, other));
        }
    }

    // ------------------------------------------------------------------ a fresh database

    /**
     * A fresh database with both settings: the same start creates GOI.FIN (FirstAdminBootstrap) and deploys its packs
     * (the start-up runner), whichever of the two runs first; the order and what the audit chain records are reported.
     * A second start deploys nothing.
     */
    @Test
    void onAFreshDatabaseTheStartThatCreatesTheTenantDeploysItsPacks(CapturedOutput output) throws Exception {
        Host host = Host.h2(dir.resolve("fresh"));
        Consumer<Map<String, Object>> settings = env -> {
            Host.WITHOUT_PACK_SETTINGS.accept(env);
            env.put(ON, "true");
            env.put(TENANTS, TENANT);
        };
        List<String> first;
        List<String> all;
        try (ConfigurableApplicationContext ctx = host.start(settings)) {
            assertEquals("CREATED", tenantStatus(ctx, TENANT));
            first = installed(ctx, TENANT);
            all = deployments(ctx, TENANT);
            assertEquals(shipped(ctx), first.size(), "every shipped pack is deployed for the tenant");
            assertEquals(first.size(), new TreeSet<>(packIds(first)).size(), "one deployment per pack");
            List<String> chain = chain(ctx, TENANT);
            fact("fresh database, both settings: audit chain sec:" + TENANT, chain.toString());
            assertEquals(1, chain.stream().filter("PACKS_DEPLOYED"::equals).count(), chain.toString());
            assertTrue(chain.contains("TENANT_CREATED"), chain.toString());
            fact("fresh database: order at start-up", chain.indexOf("TENANT_CREATED") < chain.indexOf("PACKS_DEPLOYED")
                    ? "FirstAdminBootstrap before the pack runner" : "the pack runner before FirstAdminBootstrap");
        }
        try (ConfigurableApplicationContext ctx = host.start(settings)) {
            assertEquals(first, installed(ctx, TENANT), "a second start deploys nothing: the same deployments");
            assertEquals(all, deployments(ctx, TENANT), "packs and the tenant's own tables: the first start, in which "
                    + "the packs were deployed before the tenant existed, had left nothing out");
            List<String> chain = chain(ctx, TENANT);
            fact("fresh database, second start: audit chain sec:" + TENANT, chain.toString());
            fact("fresh database: deployments of the tenant", all.size() + " (" + first.size() + " packs), the same after "
                    + "the second start");
        }
        // the pack runner ran before FirstAdminBootstrap in the first start; the warning is judged after both
        assertEquals(List.of(), output.getOut().lines().filter(l -> l.contains(NO_PACKS)).toList(),
                "neither start warns: the tenant has its packs when the start is over");
    }

    // ------------------------------------------------------------------ the fragment

    /**
     * The fragment IFMS runs must deploy the packs of the tenant it creates: both settings, as quoted literals, the
     * tenant list naming the first-admins tenant. Fail-first: the fragment of 6fa1f830 carries neither.
     */
    @Test
    void theFragmentCarriesThePackSettingsOfItsFirstAdminsTenant() {
        assertEquals("true", DemoComposeFragmentStartupTest.fragmentValue(ON));
        assertEquals(DemoComposeFragmentStartupTest.fragmentValue("SANGAM_SECURITY_FIRSTADMINS_TENANT"),
                DemoComposeFragmentStartupTest.fragmentValue(TENANTS),
                "the packs are deployed for the tenant the fragment creates");
        assertEquals(TENANT, DemoComposeFragmentStartupTest.fragmentValue(TENANTS));
    }

    /** {@link #liveLike} on the H2 stand-in. */
    @Test
    void aRestartFromTheFragmentDeploysThePacksOfTheTenantThatExistsAndAFurtherStartChangesNothing() throws Exception {
        liveLike(Host.h2(dir.resolve("live")), "H2 stand-in");
    }

    /**
     * The demo host's history, then the way that needs no new image. Starts 1 and 2: the image as it is deployed now
     * (the fragment without the two pack settings); the live steps 2c, 3a and 3b run, 3b fails for want of packs.
     * Start 3: a restart from the fragment as it is in this tree. The settings must come from the fragment's
     * environment; every shipped pack is then deployed for GOI.FIN, the tenant's data is as before, and steps 3b and
     * 4a run (they stop at connectors nothing answers, register E-26: reported per case, not judged here). Start 4,
     * the same again: the same deployments, and every open case on the instance and definition it had.
     */
    static void liveLike(Host host, String label) throws Exception {
        try (ConfigurableApplicationContext ctx = host.startAsDeployed()) {
            assertTrue(ctx.isActive());
            assertEquals(List.of(), installed(ctx, TENANT), "as deployed: no pack for the tenant");
        }
        String dataBefore;
        try (ConfigurableApplicationContext ctx = host.startAsDeployed()) {
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, label + ", as deployed", List.of("2c seed", "3a funds", "3b smoke"));
            assertEquals(List.of(), installed(ctx, TENANT), "as deployed: no pack for the tenant");
            dataBefore = tenantData(ctx, TENANT);
            fact(label + ": tenant data before the restart", dataBefore);
        }
        List<String> packs;
        List<String> all;
        long definitions;
        Map<String, String> cases;
        // register E-26 Follow-up 2 (vi): the payee custody vault, for this test only (open decision, see the constant)
        try (ConfigurableApplicationContext ctx = host.start(env -> { }, DemoLoaderSequenceAgainstServerTest.PAYEE_CUSTODY_FOR_THIS_TEST)) {
            assertEquals("true", ctx.getEnvironment().getProperty("sangam.packs.deploy-on-start"),
                    "the fragment's environment switches the start-up deployment on (no profile default is relied on)");
            assertEquals(TENANT, ctx.getEnvironment().getProperty("sangam.packs.tenants"),
                    "the fragment's environment names the tenant");
            packs = installed(ctx, TENANT);
            int shipped = shipped(ctx);
            assertEquals(shipped, packs.size(), "every shipped pack is deployed for " + TENANT);
            assertEquals(packs.size(), new TreeSet<>(packIds(packs)).size(), "one deployment per pack");
            definitions = definitions(ctx, TENANT);
            assertEquals(dataBefore, tenantData(ctx, TENANT), "the deployment left the tenant's data as it was");
            List<String> chain = chain(ctx, TENANT);
            assertEquals(1, chain.stream().filter("PACKS_DEPLOYED"::equals).count(), chain.toString());
            fact(label + ": restart from the fragment", packs.size() + " of " + shipped + " packs deployed for " + TENANT
                    + ", " + definitions + " process definitions; tenant data unchanged; PACKS_DEPLOYED on sec:" + TENANT + " x1");
            readOnlyCheck(ctx, packs.size());
            DemoLoaderSequenceAgainstServerTest.sequence(ctx, label + ", packs deployed by a restart from the fragment",
                    List.of("3b smoke", "4a cases"), DemoLoaderSequenceAgainstServerTest.PACKS_GAPS);
            cases = openCases(ctx);
            assertFalse(cases.isEmpty(), "cases are open after step 4a");
            all = deployments(ctx, TENANT);
        }
        try (ConfigurableApplicationContext ctx = host.start(env -> { }, DemoLoaderSequenceAgainstServerTest.PAYEE_CUSTODY_FOR_THIS_TEST)) {
            assertEquals(packs, installed(ctx, TENANT), "a further start deploys nothing: the same deployments");
            assertEquals(all, deployments(ctx, TENANT), "packs and the tenant's own tables alike");
            assertEquals(definitions, definitions(ctx, TENANT), "and no new definition version");
            assertEquals(cases, openCases(ctx), "every open case is the instance it was, on the definition it had");
            List<String> chain = chain(ctx, TENANT);
            fact(label + ": further start", "same " + packs.size() + " deployments, " + definitions + " definitions, "
                    + cases.size() + " open instances unchanged; PACKS_DEPLOYED on sec:" + TENANT + " x"
                    + chain.stream().filter("PACKS_DEPLOYED"::equals).count());
        }
    }

    /**
     * The check IFMS can run on the host without changing anything, as any signed-in user of the tenant (the two
     * commands of deploy/demo/README.md): one page of {@code GET /deployments} holds one {@code "name":"pack:<id>@<version>"}
     * per shipped pack (none as deployed), and the definition the first smoke case starts is there (or is not). The
     * list also holds the deployments that belong to no tenant, so its {@code X-Total-Count} is only reported.
     */
    private static void readOnlyCheck(ConfigurableApplicationContext ctx, int expected) throws Exception {
        String api = api(ctx);
        String officer = token("kavita.sharma", List.of("DDO", "sangam-user"));
        HttpResponse<String> page = send("GET", api + "/deployments?page=0&size=500", officer, null);
        assertEquals(200, page.statusCode(), page.body());
        Set<String> names = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"name\":\"pack:[^\"]*\"").matcher(page.body());
        while (m.find()) {
            names.add(m.group());
        }
        JsonNode bill = PresentationSeedAgainstServerTest.get(api + "/process-definitions?key=sangam-ifms-bill&latest=true", officer);
        int found = (bill.isArray() ? bill : bill.path("items")).size();
        fact("read-only check: GET /deployments?page=0&size=500 as an officer", names.size()
                + " distinct \"name\":\"pack:<id>@<version>\" (X-Total-Count " + page.headers().firstValue("X-Total-Count").orElse("-")
                + ", deployments of the tenant in the engine " + deployments(ctx, TENANT).size()
                + "); GET /process-definitions?key=sangam-ifms-bill&latest=true: " + found);
        assertEquals(expected, names.size(), "one pack deployment per shipped pack is listed: " + names);
        assertEquals(expected == 0 ? 0 : 1, found, bill.toString());
    }
    // ------------------------------------------------------------------ one demo host across starts

    /** One demo host: its database, its keys and its key attestation, across starts (decision 387(4)). */
    static final class Host {
        /** The fragment of the image that is deployed now: the two pack settings are not in it. */
        static final Consumer<Map<String, Object>> WITHOUT_PACK_SETTINGS = env -> {
            env.remove(ON);
            env.remove(TENANTS);
        };

        private final Path dir;
        private final DemoComposeFragmentStartupTest.Keys keys;
        private final String[] profiles;
        private final boolean h2;
        private Map<String, String> attested;

        Host(Path dir, DemoComposeFragmentStartupTest.Keys keys, String... profiles) {
            this.dir = dir;
            this.keys = keys;
            this.profiles = profiles;
            this.h2 = List.of(profiles).contains("h2");
        }

        static Host h2(Path dir) throws IOException {
            Files.createDirectories(dir);
            return new Host(dir, new DemoComposeFragmentStartupTest.Keys(dir), "h2", "oidc");
        }

        /**
         * As on demo.ifms.dev now. Under the fragment's profiles (postgres) the missing settings mean no start-up
         * deployment; the h2 stand-in is given that default explicitly (see the class comment).
         */
        ConfigurableApplicationContext startAsDeployed() throws Exception {
            return h2 ? start(WITHOUT_PACK_SETTINGS, "--sangam.packs.deploy-on-start=false") : start(WITHOUT_PACK_SETTINGS);
        }

        /**
         * The first start is the one-time key bootstrap (on a real database also the schema, as the migrate-only
         * one-shot applies it); its fingerprint export is signed by a custodian key made here, and every later start
         * runs over that attestation (on a real database in validate mode, as the serving start of the demo).
         */
        ConfigurableApplicationContext start(Consumer<Map<String, Object>> edit, String... more) throws Exception {
            Path export = dir.resolve("key-fingerprints.txt");
            Map<String, String> flow = new LinkedHashMap<>();
            // the fragment does not set the log archive; keep this test's segments out of the module directory
            List<String> settings = new ArrayList<>(List.of("--sangam.compliance.log-archive.root=" + dir.resolve("compliance-logs")));
            settings.addAll(List.of(more));
            boolean first = attested == null;
            if (first) {
                flow.put("SANGAM_KEYS_BOOTSTRAP", "true");
                flow.put("SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString());
                flow.put("SANGAM_KEYS_ATTESTATION", "");
                flow.put("SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
                if (!h2) {
                    flow.put("SANGAM_LIQUIBASE_MODE", "startup");
                    settings.add("--operaton.bpm.database.schema-update=true");
                }
            } else {
                flow.putAll(attested);
                if (!h2) {
                    flow.put("SANGAM_LIQUIBASE_MODE", "validate");
                }
            }
            ConfigurableApplicationContext ctx = DemoComposeFragmentStartupTest.start(keys, flow, settings, edit, profiles);
            if (first) {
                try {
                    attested = attest(export);
                } catch (Exception | AssertionError e) {
                    ctx.close();
                    throw e;
                }
            }
            return ctx;
        }

        private Map<String, String> attest(Path export) throws Exception {
            List<KeyAttestation.Key> keysOfExport = Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(" ", 4))
                    .map(t -> new KeyAttestation.Key(t[1], t[3])).distinct().toList();
            assertFalse(keysOfExport.isEmpty(), "the bootstrap start exported this environment's key fingerprints");
            KeyPair custodian = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path attestation = dir.resolve("key-attestation.json");
            Files.writeString(attestation, KeyAttestation.sign("demo-ifms", "2026-11-21T00:00:00Z", keysOfExport,
                    custodian.getPrivate()), StandardCharsets.UTF_8);
            Map<String, String> later = new LinkedHashMap<>();
            later.put("SANGAM_KEYS_BOOTSTRAP", "false");
            later.put("SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString());
            later.put("SANGAM_KEYS_ATTESTATION", attestation.toString());
            later.put("SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", Base64.getEncoder().encodeToString(custodian.getPublic().getEncoded()));
            return later;
        }
    }

    // ------------------------------------------------------------------ what is read from a started server

    private static void fact(String name, String value) {
        System.out.println("PACKS-TENANT | " + name + " | " + value);
    }

    private static String api(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getRequiredProperty("local.server.port") + "/api/v1";
    }

    private static String token(String user, List<String> roles) throws Exception {
        return PresentationSeedAgainstServerTest.token(user, roles);
    }

    /** The packs on the server's class path (and its plugins). */
    static int shipped(ConfigurableApplicationContext ctx) {
        return ctx.getBean(org.sangam.packs.spi.PackRegistry.class).all().size();
    }

    /** The pack deployments of a tenant in the engine, as sorted {@code packId@version=deploymentId}. */
    static List<String> installed(ConfigurableApplicationContext ctx, String tenant) {
        List<String> out = new ArrayList<>();
        for (org.sangam.packs.spi.PackDeployer.Installed i : ctx.getBean(org.sangam.packs.spi.PackDeployer.class).installed(tenant)) {
            assertEquals(tenant, i.tenantId());
            out.add(i.packId() + "@" + i.version() + "=" + i.deploymentId());
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Every engine deployment of a tenant (packs, its own delegation tables, anything else), as sorted {@code name=id}. */
    static List<String> deployments(ConfigurableApplicationContext ctx, String tenant) {
        List<String> out = new ArrayList<>();
        ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class).getRepositoryService().createDeploymentQuery()
                .tenantIdIn(tenant).list().forEach(d -> out.add(d.getName() + "=" + d.getId()));
        java.util.Collections.sort(out);
        return out;
    }

    private static List<String> packIds(List<String> installed) {
        return installed.stream().map(s -> s.substring(0, s.lastIndexOf('@'))).toList();
    }

    static long definitions(ConfigurableApplicationContext ctx, String tenant) {
        return ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class).getRepositoryService()
                .createProcessDefinitionQuery().tenantIdIn(tenant).count();
    }

    /** Every open process instance of the tenant: id to definition id (which carries key and version). */
    private static Map<String, String> openCases(ConfigurableApplicationContext ctx) {
        Map<String, String> out = new TreeMap<>();
        ctx.getBean(org.operaton.bpm.engine.ProcessEngine.class).getRuntimeService().createProcessInstanceQuery()
                .tenantIdIn(TENANT).list().forEach(pi -> out.put(pi.getId(), pi.getProcessDefinitionId()));
        return out;
    }

    /** The event types of the tenant's security chain {@code sec:<tenant>}, in order. */
    static List<String> chain(ConfigurableApplicationContext ctx, String tenant) throws java.sql.SQLException {
        return query(ctx, "SELECT event_type FROM AUDIT_EVENT WHERE tenant_id=? AND chain_id=? ORDER BY seq", tenant,
                "sec:" + tenant);
    }

    static String tenantStatus(ConfigurableApplicationContext ctx, String tenant) throws java.sql.SQLException {
        List<String> s = query(ctx, "SELECT status FROM SEC_TENANT WHERE tenant_id=?", tenant);
        return s.isEmpty() ? null : s.get(0);
    }

    /**
     * The tenant's rows in the security and ledger tables that have a tenant_id column (tenant, persons, roles and
     * bindings, org units, seats, postings, balances, entries, ...): table to row count, and the tenant's status.
     */
    static String tenantData(ConfigurableApplicationContext ctx, String tenant) throws java.sql.SQLException {
        Map<String, String> counts = new TreeMap<>();
        try (java.sql.Connection c = ctx.getBean(javax.sql.DataSource.class).getConnection()) {
            Set<String> tables = new TreeSet<>();
            try (java.sql.ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, null, null)) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME").toUpperCase(java.util.Locale.ROOT);
                    // a quota window is a rate counter of the running server, not data of the tenant
                    if ("TENANT_ID".equalsIgnoreCase(rs.getString("COLUMN_NAME")) && !table.endsWith("_WINDOW")
                            && (table.startsWith("SEC_") || table.startsWith("LDG_"))) {
                        tables.add(table);
                    }
                }
            }
            for (String table : tables) {
                try (var ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?")) {
                    ps.setString(1, tenant);
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            counts.put(table, String.valueOf(rs.getLong(1)));
                        }
                    }
                }
            }
        }
        assertNotEquals(Map.of(), counts, "the tenant has rows");
        return "status " + tenantStatus(ctx, tenant) + " " + counts;
    }

    private static List<String> query(ConfigurableApplicationContext ctx, String sql, String... args) throws java.sql.SQLException {
        try (java.sql.Connection c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static HttpResponse<String> send(String method, String url, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (!method.equals("GET")) {
            b.header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        }
        return HTTP.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The problem code of an error answer ("" for a 2xx or a body without one). */
    private static String code(HttpResponse<String> r) {
        try {
            return JSON.readTree(r.body()).path("code").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** The problem code with its detail, for the report. */
    private static String problem(HttpResponse<String> r) {
        try {
            String detail = JSON.readTree(r.body()).path("detail").asText("");
            return code(r) + (detail.isEmpty() ? "" : " (" + detail + ")");
        } catch (Exception e) {
            return "";
        }
    }
}
