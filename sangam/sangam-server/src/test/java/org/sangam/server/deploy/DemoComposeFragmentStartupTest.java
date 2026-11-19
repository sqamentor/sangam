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

    /** The env names the fragment must carry: the production start set and the decision 387(4) key flow. */
    private static final Set<String> REQUIRED = Set.of(
            "SANGAM_SECURITY_PRODUCTION", "SANGAM_DB_URL", "SANGAM_DB_USER", "SANGAM_DB_PASSWORD",
            "SANGAM_SECURITY_KEK_KEYS_K1", "SANGAM_SECURITY_KEK_CURRENTID",
            "SANGAM_REGISTRY_SIGNER_PRIVATEKEY", "SANGAM_REGISTRY_SIGNER_PUBLICKEY", "SANGAM_REGISTRY_VAULT_HMACPEPPER",
            "SANGAM_MALWARE_PRODUCTIONWAIVER", "SANGAM_SECRETS_PROVIDERS", "SANGAM_SECRETS_FILE_ROOT",
            "SANGAM_AUDIT_CHECKPOINT_PRIVATEKEYREF", "SANGAM_AUDIT_CHECKPOINT_PUBLICKEYREF",
            "SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF", "SANGAM_KEYS_ENVIRONMENT", "SANGAM_KEYS_BOOTSTRAP",
            "SANGAM_KEYS_FINGERPRINT_EXPORT", "SANGAM_KEYS_ATTESTATION", "SANGAM_KEYS_CUSTODIAN_PUBLIC_KEY",
            "SANGAM_LEDGER_GLEXPORTWAIVER", "SANGAM_TRUSTED_PROXIES", "SANGAM_DEMO_SANDBOXTENANTS");

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
        }

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
        } finally {
            org.sangam.packs.sim.DemoSandbox.install(Set.of());
        }
    }

    private ConfigurableApplicationContext start(Keys keys, Map<String, String> keyFlow) throws IOException {
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
        return new SpringApplicationBuilder(SangamServerApplication.class).environment(environment)
                .profiles("h2", "oidc").run(harness);
    }

    /** Test values, fixed for both starts (the same keys and database): one per env name of the fragment. */
    private static final class Keys {
        final Path secrets;
        final String db;
        final String dbPassword = random(24);
        final String kek = random(32);
        final String pepper = random(32);
        final String[] signer = ed25519();

        Keys(Path dir) throws IOException {
            secrets = secretsRoot(dir.resolve("secrets"));
            db = "jdbc:h2:file:" + dir.resolve("db").toString().replace('\\', '/') + ";LOCK_TIMEOUT=10000;MODE=PostgreSQL";
        }

        /** An unknown name fails, so every new name of the fragment is looked at. */
        String value(String name) {
            return switch (name) {
                case "TZ" -> "UTC";
                case "SPRING_PROFILES_ACTIVE" -> "h2,oidc";
                case "SANGAM_SECURITY_PRODUCTION" -> "true";
                case "SANGAM_DB_URL" -> db;
                case "SANGAM_DB_USER" -> "sangam_app";
                case "SANGAM_DB_PASSWORD" -> dbPassword;
                case "SANGAM_LIQUIBASE_MODE" -> "startup";
                case "SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE" -> "system:platform";
                case "SANGAM_TENANT_DEFAULT", "SANGAM_COMPLIANCE_TENANTS", "SANGAM_SECURITY_JITPROVISIONINGTENANTS" -> "GOI.FIN";
                case "SANGAM_COMPLIANCE_JURISDICTIONS", "SANGAM_COMPLIANCE_LOG_ARCHIVE_COUNTRY" -> "IN";
                case "SANGAM_COMPLIANCE_ORGANISATION" -> "OneIFMS demo";
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
                case "SANGAM_OIDC_ISSUER" -> "https://idp.example/realms/oneifms";
                case "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" ->
                        "https://idp.example/realms/oneifms/protocol/openid-connect/certs";
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
}
