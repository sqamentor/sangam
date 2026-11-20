// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.server.SangamServerApplication;
import org.sangam.server.config.KeyAttestation;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EV-M-0890 (IFMS deploy hold): the demo compose fragment (deploy/demo/sangam-compose-fragment.yml) must carry the
 * whole production start set of {@link ProductionStartupFailFastTest} under env var NAMES that really bind, and its
 * key-environment flow (decision 387(4), deploy/README.md section 5.1) must work: the one-time bootstrap start, then a
 * restart over the custodian's attestation.
 * <p>
 * The server is started in production mode with its settings only from an environment property source holding exactly
 * the env var names of the fragment's sangam-server service, each with a test value (random keys, a temporary secrets
 * root laid out as generate-demo-keys.sh writes it). An env name that does not bind leaves its setting unset, and
 * production refuses the start, so a start proves the names. Profiles: the fragment's postgres,oidc with h2 in place
 * of postgres (oidc maps SANGAM_OIDC_ISSUER to the issuer-uri production requires, RM-09); the database is an H2 file
 * database so that the bootstrap record survives into the restart. Harness-only: the h2 profile's development
 * conveniences off (as in ProductionStartupFailFastTest) and random ports.
 * <p>
 * Fail-first: on the fragment of 84827e97 (before EV-M-0890) eight names were missing and both checks failed. The GL
 * waiver name needs the gl-export change (6024c281): until it is merged the production start is refused for want of
 * sangam.ledger.gl-connector.
 */
class DemoComposeFragmentStartupTest {

    private static final Path FRAGMENT = Path.of("..", "deploy", "demo", "sangam-compose-fragment.yml");
    private static final String ENV = "demo-ifms";
    private static final String TENANT = "GOI.FIN";
    /** The two realm oneifms administrators (EV-M-0916): person id : principal claim (preferred_username) : name. */
    private static final String FIRST_ADMINS = "rajendra.sharma:rajendra.sharma:Rajendra Prasad Sharma,"
            + "meenakshi.kanwar:meenakshi.kanwar:Meenakshi Kanwar";

    /** The env names the fragment must carry: the production start set and the decision 387(4) key flow. */
    private static final Set<String> REQUIRED = Set.of(
            "SANGAM_SECURITY_PRODUCTION", "SANGAM_DB_URL", "SANGAM_DB_USER", "SANGAM_DB_PASSWORD",
            "SANGAM_SECURITY_KEK_KEYS_K1", "SANGAM_SECURITY_KEK_CURRENTID",
            "SANGAM_REGISTRY_SIGNER_PRIVATEKEY", "SANGAM_REGISTRY_SIGNER_PUBLICKEY", "SANGAM_REGISTRY_VAULT_HMACPEPPER",
            "SANGAM_MALWARE_PRODUCTIONWAIVER", "SANGAM_SECRETS_PROVIDERS", "SANGAM_SECRETS_FILE_ROOT",
            "SANGAM_AUDIT_CHECKPOINT_PRIVATEKEYREF", "SANGAM_AUDIT_CHECKPOINT_PUBLICKEYREF",
            "SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF", "SANGAM_KEYS_ENVIRONMENT", "SANGAM_KEYS_BOOTSTRAP",
            "SANGAM_KEYS_FINGERPRINT_EXPORT", "SANGAM_KEYS_ATTESTATION", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY",
            "SANGAM_LEDGER_GLEXPORTWAIVER", "SANGAM_TRUSTED_PROXIES", "SANGAM_DEMO_SANDBOXTENANTS",
            "SANGAM_SECURITY_FIRSTADMINS_TENANT", "SANGAM_SECURITY_FIRSTADMINS_SUBJECTS");

    @org.junit.jupiter.api.AfterAll
    static void releaseRealm() {
        RealmIdp.release();
    }

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> serverEnvironment() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(FRAGMENT, StandardCharsets.UTF_8));
        Map<String, Object> services = (Map<String, Object>) doc.get("services");
        Map<String, Object> server = (Map<String, Object>) services.get("sangam-server");
        return (Map<String, Object>) server.get("environment");
    }

    @Test
    void fragmentCarriesTheProductionStartSet() throws IOException {
        Set<String> missing = new TreeSet<>(REQUIRED);
        missing.removeAll(serverEnvironment().keySet());
        assertEquals(Set.of(), missing, "env names of the production start set missing from the compose fragment");
        assertFalse(serverEnvironment().containsKey("SANGAM_LEDGER_GLCONNECTOR"),
                "the GL connector stays unset: the demo runs under the audited GL export waiver");
    }

    /**
     * EV-M-0916 defect 2: the front image runs as a non-root user (deploy/presentation/Dockerfile USER), and a tmpfs
     * mounted without uid/gid is root-owned, so nginx could not write conf.d and listened on nothing. Every tmpfs of
     * sangam-front must carry the image's uid and gid.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyFrontTmpfsIsOwnedByTheImageUser() throws IOException {
        String uid = Files.readAllLines(Path.of("..", "deploy", "presentation", "Dockerfile"), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(l -> l.startsWith("USER ")).reduce((a, b) -> b).orElseThrow()
                .substring(5).strip().split(":")[0];
        assertTrue(uid.matches("[1-9][0-9]*"), "the front image runs as a numeric non-root uid: " + uid);
        Map<String, Object> doc = new Yaml().load(Files.readString(FRAGMENT, StandardCharsets.UTF_8));
        Map<String, Object> front = (Map<String, Object>) ((Map<String, Object>) doc.get("services")).get("sangam-front");
        List<String> tmpfs = (List<String>) front.get("tmpfs");
        assertFalse(tmpfs == null || tmpfs.isEmpty(), "sangam-front is read_only and needs its tmpfs mounts");
        for (String m : tmpfs) {
            List<String> opts = m.contains(":") ? List.of(m.substring(m.indexOf(':') + 1).split(",")) : List.of();
            assertTrue(opts.contains("uid=" + uid) && opts.contains("gid=" + uid),
                    "tmpfs " + m + " must be owned by the image user " + uid + " (uid=" + uid + ",gid=" + uid + ")");
        }
    }

    @Test
    void bootstrapStartThenAttestedRestartFromExactlyTheFragmentsEnvNames() throws Exception {
        Keys keys = new Keys(dir);
        Path export = dir.resolve("key-fingerprints.txt");

        // start 1: the one-time audited bootstrap, writing the fingerprint export
        Map<String, String> first = Map.of("SANGAM_KEYS_BOOTSTRAP", "true", "SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString(),
                "SANGAM_KEYS_ATTESTATION", "", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", "");
        try (ConfigurableApplicationContext ctx = start(keys, first)) {
            assertTrue(ctx.isActive());
            assertEquals("true", ctx.getEnvironment().getProperty("sangam.security.production"));
            assertEquals("k1", ctx.getEnvironment().getProperty("sangam.security.kek.current-id"));
            // SANGAM_DEMO_SANDBOXTENANTS binds to sangam.demo.sandbox-tenants and is installed into the packs
            assertEquals(Set.of("GOI.FIN"), org.sangam.packs.sim.DemoSandbox.tenants());
            assertFirstAdmins(ctx);
        }
        String afterFirst = tenantState(keys);

        // the custodian, on the host: signs this environment's export (the shell tool is KeyAttestationEndToEndTest's)
        List<KeyAttestation.Key> attested = Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(" ", 4))
                .peek(t -> assertEquals(ENV, t[0], "the export is this environment's"))
                .map(t -> new KeyAttestation.Key(t[1], t[3])).distinct().toList();
        assertFalse(attested.isEmpty(), "the bootstrap start exported this environment's key fingerprints");
        KeyPair custodian = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path attestation = dir.resolve("key-attestation.json");
        Files.writeString(attestation, KeyAttestation.sign(ENV, "2026-11-19T00:00:00Z", attested, custodian.getPrivate()),
                StandardCharsets.UTF_8);

        // start 2: every later start, bootstrap off, over the attestation
        Map<String, String> later = Map.of("SANGAM_KEYS_BOOTSTRAP", "false", "SANGAM_KEYS_FINGERPRINT_EXPORT", export.toString(),
                "SANGAM_KEYS_ATTESTATION", attestation.toString(),
                "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY", Base64.getEncoder().encodeToString(custodian.getPublic().getEncoded()));
        try (ConfigurableApplicationContext ctx = start(keys, later)) {
            assertTrue(ctx.isActive());
            assertEquals(attestation.toString(), ctx.getEnvironment().getProperty("sangam.keys.attestation"));
            assertEquals(Set.of("GOI.FIN"), org.sangam.packs.sim.DemoSandbox.tenants());
            assertFirstAdmins(ctx);
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(Set.of());
        }
        assertEquals(afterFirst, tenantState(keys), "a second start with the first-admins settings changes nothing");
    }

    /**
     * EV-M-0916: FirstAdminBootstrap under production creates GOI.FIN (a tenant id with a dot) and appoints the two
     * administrators, and a token shaped like realm oneifms's sangam-ui token (NO sub; preferred_username) signs in
     * with tenant-admin rights. The seed's external subject is matched against the principal claim
     * (sangam.security.oidc.claims.principal, default preferred_username), not against sub.
     */
    private static void assertFirstAdmins(ConfigurableApplicationContext ctx) throws Exception {
        assertEquals("CREATED", tenantStatus(ctx.getBean(javax.sql.DataSource.class)),
                "GOI.FIN exists and serves requests (CREATED; ACTIVE comes from the administrators' activation, AJ-01)");
        String port = ctx.getEnvironment().getRequiredProperty("local.server.port");
        for (String[] admin : new String[][] {{"rajendra.sharma", "Rajendra Prasad Sharma"}, {"meenakshi.kanwar", "Meenakshi Kanwar"}}) {
            String token = RealmIdp.get().tokenWithoutSub(TENANT, admin[0], List.of("sangam-tenant-admin"));
            java.net.http.HttpResponse<String> me = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/me"))
                            .header("Authorization", "Bearer " + token).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode(), admin[0] + ": " + me.body());
            assertFalse(me.body().contains("TENANT_UNAVAILABLE"), me.body());
            assertTrue(me.body().contains("\"" + admin[0] + "\""), "the seeded person " + admin[0] + ": " + me.body());
            assertTrue(me.body().contains("\"admin.write\""), admin[0] + " has administrator permissions: " + me.body());
            // the sign-in resolved to the SEEDED person (no just-in-time person), which holds the tenant-admin binding
            assertEquals(List.of(admin[0]), query(ctx, "SELECT person_id FROM SEC_PERSON WHERE tenant_id=? AND external_subject=?",
                    TENANT, admin[0]), "one person per principal, the seed");
            assertEquals(List.of(admin[1]), query(ctx, "SELECT display_name FROM SEC_PERSON WHERE tenant_id=? AND person_id=?",
                    TENANT, admin[0]));
            assertEquals(List.of(org.sangam.security.admin.TenantAdminService.TENANT_ADMIN_ROLE), query(ctx,
                    "SELECT role_id FROM SEC_ROLE_BINDING WHERE tenant_id=? AND member_id=?", TENANT,
                    org.sangam.core.security.SecurityIdentity.USER + admin[0]), admin[0] + " is bound to tenant-admin");
        }
        assertEquals(List.of("2"), query(ctx, "SELECT COUNT(*) FROM SEC_PERSON WHERE tenant_id=?", TENANT),
                "no just-in-time person was provisioned beside the two seeds");
    }

    private static List<String> query(ConfigurableApplicationContext ctx, String sql, String... args) throws java.sql.SQLException {
        try (java.sql.Connection c = ctx.getBean(javax.sql.DataSource.class).getConnection();
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<String> out = new java.util.ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    private static String tenantStatus(javax.sql.DataSource ds) throws java.sql.SQLException {
        try (java.sql.Connection c = ds.getConnection();
             var ps = c.prepareStatement("SELECT status FROM SEC_TENANT WHERE tenant_id=?")) {
            ps.setString(1, TENANT);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** The tenant row, its two administrators and its role count, as one comparable string. */
    private static String tenantState(Keys keys) throws java.sql.SQLException {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(keys.db, keys.dbUser, keys.dbPassword)) {
            StringBuilder sb = new StringBuilder();
            for (String q : List.of(
                    "SELECT tenant_id, status, created_at FROM SEC_TENANT WHERE tenant_id=?",
                    "SELECT person_id, external_subject, display_name, status, created_at FROM SEC_PERSON WHERE tenant_id=?"
                            + " AND person_id IN ('rajendra.sharma','meenakshi.kanwar') ORDER BY person_id",
                    "SELECT COUNT(*) FROM SEC_ROLE WHERE tenant_id=?")) {
                try (var ps = c.prepareStatement(q)) {
                    ps.setString(1, TENANT);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                sb.append(rs.getString(i)).append('|');
                            }
                            sb.append('\n');
                        }
                    }
                }
            }
            return sb.toString();
        }
    }

    static ConfigurableApplicationContext start(Keys keys, Map<String, String> keyFlow) throws IOException {
        return start(keys, keyFlow, "h2", "oidc");
    }

    /** The fragment's env names under the given profiles (an IT lane's database: the fragment's own postgres,oidc). */
    static ConfigurableApplicationContext start(Keys keys, Map<String, String> keyFlow, String... profiles) throws IOException {
        return start(keys, keyFlow, List.of(), profiles);
    }

    /** As above, with further command-line settings of a test (each named and explained where it is passed). */
    static ConfigurableApplicationContext start(Keys keys, Map<String, String> keyFlow, List<String> settings,
                                                String... profiles) throws IOException {
        Map<String, Object> env = new LinkedHashMap<>();
        for (String name : serverEnvironment().keySet()) {
            env.put(name, keyFlow.containsKey(name) ? keyFlow.get(name) : keys.value(name));
        }
        StandardEnvironment environment = new StandardEnvironment();
        // the "-systemEnvironment" suffix gives the source Spring Boot's env-name mapping, as on the container
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("demo-fragment-systemEnvironment", env));
        String[] harness = {
                "--sangam.security.bootstrap.enabled=false", "--sangam.security.dev-token.enabled=false",
                "--sangam.security.platform-issuer=", "--sangam.debug.enabled=false",
                "--server.port=0", "--management.server.port=0", "--sangam.engine.allow-multiple-engines-per-jvm=true"};
        List<String> args = new java.util.ArrayList<>(List.of(harness));
        args.addAll(settings);
        return new SpringApplicationBuilder(SangamServerApplication.class).environment(environment)
                .profiles(profiles).run(args.toArray(String[]::new));
    }

    /** Test values, fixed for both starts (the same keys and database): one per env name of the fragment. */
    static final class Keys {
        final Path secrets;
        final String db;
        final String dbUser;
        final String dbPassword;
        final String profiles;
        final String kek = random(32);
        final String pepper = random(32);
        final String[] signer = ed25519();

        Keys(Path dir) throws IOException {
            this(dir, "jdbc:h2:file:" + dir.resolve("db").toString().replace('\\', '/') + ";LOCK_TIMEOUT=10000;MODE=PostgreSQL",
                    "sangam_app", random(24), "h2,oidc");
        }

        /** A real database (an IT lane's), with its own credentials, under the fragment's profiles. */
        Keys(Path dir, String db, String dbUser, String dbPassword, String profiles) throws IOException {
            secrets = secretsRoot(dir.resolve("secrets"));
            this.db = db;
            this.dbUser = dbUser;
            this.dbPassword = dbPassword;
            this.profiles = profiles;
        }

        /** An unknown name fails, so every new name of the fragment is looked at. */
        String value(String name) {
            return switch (name) {
                case "TZ" -> "UTC";
                case "SPRING_PROFILES_ACTIVE" -> profiles;
                case "SANGAM_SECURITY_PRODUCTION" -> "true";
                case "SANGAM_DB_URL" -> db;
                case "SANGAM_DB_USER" -> dbUser;
                case "SANGAM_DB_PASSWORD" -> dbPassword;
                case "SANGAM_LIQUIBASE_MODE" -> "startup";
                case "SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE" -> "system:platform";
                case "SANGAM_TENANT_DEFAULT", "SANGAM_COMPLIANCE_TENANTS", "SANGAM_SECURITY_JITPROVISIONINGTENANTS" -> "GOI.FIN";
                case "SANGAM_COMPLIANCE_JURISDICTIONS", "SANGAM_COMPLIANCE_LOG_ARCHIVE_COUNTRY" -> "IN";
                case "SANGAM_COMPLIANCE_ORGANISATION" -> "OneIFMS";
                case "SANGAM_SECURITY_KEK_KEYS_K1" -> kek;
                case "SANGAM_REGISTRY_VAULT_HMACPEPPER" -> pepper;
                case "SANGAM_SECURITY_KEK_CURRENTID" -> "k1";
                case "SANGAM_REGISTRY_SIGNER_PRIVATEKEY" -> signer[0];
                case "SANGAM_REGISTRY_SIGNER_PUBLICKEY" -> signer[1];
                case "SANGAM_SECRETS_PROVIDERS" -> "file";
                case "SANGAM_SECRETS_FILE_ROOT" -> secrets.toString();
                case "SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF" -> "secret://compliance/principal-key";
                case "SANGAM_AUDIT_CHECKPOINT_PRIVATEKEYREF" -> "secret://audit/checkpoint-private";
                case "SANGAM_AUDIT_CHECKPOINT_PUBLICKEYREF" -> "secret://audit/checkpoint-public";
                case "SANGAM_KEYS_ENVIRONMENT" -> ENV;
                case "SANGAM_LEDGER_GLEXPORTWAIVER" -> "demo-fragment start-up test: no GL connector";
                case "SANGAM_DEMO_SANDBOXTENANTS" -> "GOI.FIN";
                case "SANGAM_MALWARE_SCANNER" -> "none";
                case "SANGAM_MALWARE_PRODUCTIONWAIVER" -> "demo-fragment start-up test: no scanner";
                case "SANGAM_SECURITY_FIRSTADMINS_TENANT" -> TENANT;
                case "SANGAM_SECURITY_FIRSTADMINS_SUBJECTS" -> FIRST_ADMINS;
                case "SANGAM_OIDC_ISSUER" -> RealmIdp.ISSUER;
                case "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" ->
                        RealmIdp.get().jwksUri();
                case "SANGAM_SECURITY_OIDC_AUDIENCE" -> "sangam-server";
                case "SANGAM_SECURITY_OIDC_TENANT_CLAIM" -> "tenant";
                case "SANGAM_SECURITY_OIDC_ROLES_CLAIM" -> "roles";
                case "SANGAM_SECURITY_OIDC_SEATS_CLAIM" -> "seats";
                case "SANGAM_SECURITY_PLATFORM_ISSUER", "SANGAM_CORS_ORIGINS", "SANGAM_CONNECTORS_PAYGOV_BASE_URL",
                     "SANGAM_CONNECTORS_IFMS_BASE_URL", "SANGAM_CONNECTORS_PFMS_DIRECTORY", "JAVA_TOOL_OPTIONS" -> "";
                case "SANGAM_TRUSTED_PROXIES" -> "172.29.250.0/24,173.245.48.0/20,2400:cb00::/32";
                case "SANGAM_CLIENT_IP_HEADERS" -> "CF-Connecting-IP,X-Forwarded-For";
                case "SANGAM_INTEGRATION_SIMULATORS_ENABLED", "SANGAM_OTLP_ENABLED" -> "false";
                default -> throw new AssertionError("no test value for the fragment's env name " + name
                        + ": add one here (and a binding check if it is a production setting)");
            };
        }
    }

    /** A non-loopback IPv4 address of this machine: production refuses loopback URLs (HostingWiring, SG-38). */
    static String nonLoopbackIPv4() throws java.net.SocketException {
        return java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
                .filter(n -> { try { return n.isUp() && !n.isLoopback(); } catch (java.net.SocketException e) { return false; } })
                .flatMap(n -> java.util.Collections.list(n.getInetAddresses()).stream())
                .filter(a -> a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                .map(java.net.InetAddress::getHostAddress).findFirst()
                .orElseThrow(() -> new IllegalStateException("no non-loopback IPv4 address on this machine"));
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String[] ed25519() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The secrets root as generate-demo-keys.sh lays it out: exact bytes, no trailing line break (SL-65). */
    private static Path secretsRoot(Path root) throws IOException {
        Path audit = Files.createDirectories(root.resolve("audit"));
        String[] checkpoint = ed25519();
        Files.writeString(audit.resolve("checkpoint-private"), checkpoint[0], StandardCharsets.UTF_8);
        Files.writeString(audit.resolve("checkpoint-public"), checkpoint[1], StandardCharsets.UTF_8);
        Path compliance = Files.createDirectories(root.resolve("compliance"));
        Files.writeString(compliance.resolve("principal-key"), random(32), StandardCharsets.UTF_8);
        return root;
    }

    /**
     * A stand-in for realm oneifms: issuer https://idp.example/realms/oneifms (the token's iss is only compared) and a
     * JWKS served on a NON-loopback address of this machine, because production refuses loopback URLs
     * (HostingWiring.sangamProductionUrlGuard). Its tokens are shaped like Keycloak 25+ sangam-ui access tokens without
     * the basic client scope (EV-M-0916): no sub; preferred_username, tenant, roles, aud, sid.
     */
    static final class RealmIdp {
        static final String ISSUER = "https://idp.example/realms/oneifms";
        private static RealmIdp instance;
        private final com.nimbusds.jose.jwk.RSAKey key;
        private final String jwks;
        private final com.sun.net.httpserver.HttpServer server;

        private RealmIdp() throws Exception {
            key = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID(java.util.UUID.randomUUID().toString())
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).generate();
            String host = nonLoopbackIPv4();
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(java.net.InetAddress.getByName(host), 0), 0);
            byte[] body = new com.nimbusds.jose.jwk.JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/realms/oneifms/protocol/openid-connect/certs", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (java.io.OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            jwks = "http://" + host + ":" + server.getAddress().getPort() + "/realms/oneifms/protocol/openid-connect/certs";
        }

        /** Stops the JWKS server and releases the instance (TestClassStaticsReleasedTest). */
        static synchronized void release() {
            if (instance != null) {
                instance.server.stop(0);
                instance = null;
            }
        }

        static synchronized RealmIdp get() {
            if (instance == null) {
                try {
                    instance = new RealmIdp();
                } catch (Exception e) {
                    throw new IllegalStateException("cannot start the test realm", e);
                }
            }
            return instance;
        }

        String jwksUri() {
            return jwks;
        }

        String tokenWithoutSub(String tenant, String preferredUsername, List<String> roles) throws Exception {
            java.time.Instant now = java.time.Instant.now();
            com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience("sangam-server")
                    .issueTime(java.util.Date.from(now)).notBeforeTime(java.util.Date.from(now.minusSeconds(5)))
                    .expirationTime(java.util.Date.from(now.plusSeconds(600))).jwtID(java.util.UUID.randomUUID().toString())
                    .claim("tenant", tenant).claim("preferred_username", preferredUsername).claim("roles", roles)
                    .claim("sid", java.util.UUID.randomUUID().toString()).build();
            com.nimbusds.jwt.SignedJWT jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(
                    com.nimbusds.jose.JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));
            assertEquals(null, jwt.getJWTClaimsSet().getSubject(), "shaped like the sangam-ui token: no sub");
            return jwt.serialize();
        }
    }}
