// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.sangam.server.SangamServerApplication;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EV-M-0890 (IFMS deploy hold): the demo compose fragment (deploy/demo/sangam-compose-fragment.yml) must carry the
 * whole production start set of {@link ProductionStartupFailFastTest} under env var NAMES that really bind.
 * <p>
 * The server is started in production mode the way ProductionStartupFailFastTest starts it, but its settings come only
 * from an environment property source holding exactly the env var names of the fragment's sangam-server service, each
 * with a test value (random keys, a temporary secrets root laid out as generate-demo-keys.sh writes it). An env name
 * that does not bind leaves its setting unset, and production refuses the start, so a start proves the names.
 * <p>
 * Profiles: the fragment's postgres,oidc with h2 in place of postgres (oidc maps SANGAM_OIDC_ISSUER to the issuer-uri that
 * production requires, RM-09). Harness differences, kept out of the fragment on purpose: the database is in-memory H2 (profile h2 instead of
 * postgres, SANGAM_DB_URL given an H2 URL), the h2 profile's development conveniences are switched off as in
 * ProductionStartupFailFastTest, ports are random, and the three owner-OPEN names (malware waiver, foreign fingerprints,
 * GL connector, which the fragment leaves empty) get test values.
 * Before the fix (fragment of 8455ed90-84827e97) the KEK, registry signer, vault pepper, checkpoint refs, file provider,
 * GL connector and waiver names were missing: {@link #fragmentCarriesTheProductionStartSet()} fails, and the start
 * is refused.
 */
class DemoComposeFragmentStartupTest {

    private static final Path FRAGMENT = Path.of("..", "deploy", "demo", "sangam-compose-fragment.yml");

    /** The env names the fragment must carry: one per setting of ProductionStartupFailFastTest.baseline(). */
    private static final Set<String> REQUIRED = Set.of(
            "SANGAM_SECURITY_PRODUCTION", "SANGAM_DB_URL", "SANGAM_DB_USER", "SANGAM_DB_PASSWORD",
            "SANGAM_SECURITY_KEK_KEYS_K1", "SANGAM_SECURITY_KEK_CURRENTID",
            "SANGAM_REGISTRY_SIGNER_PRIVATEKEY", "SANGAM_REGISTRY_SIGNER_PUBLICKEY", "SANGAM_REGISTRY_VAULT_HMACPEPPER",
            "SANGAM_MALWARE_PRODUCTIONWAIVER", "SANGAM_SECRETS_PROVIDERS", "SANGAM_SECRETS_FILE_ROOT",
            "SANGAM_AUDIT_CHECKPOINT_PRIVATEKEYREF", "SANGAM_AUDIT_CHECKPOINT_PUBLICKEYREF",
            "SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF", "SANGAM_KEYS_ENVIRONMENT", "SANGAM_KEYS_FOREIGN_FINGERPRINTS",
            "SANGAM_LEDGER_GLCONNECTOR", "SANGAM_TRUSTED_PROXIES");

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
    }

    @Test
    void productionStartsFromExactlyTheFragmentsEnvNames() throws Exception {
        Path secrets = secretsRoot();
        Path foreign = Files.createTempFile("demo-foreign-fingerprints", ".txt");
        Files.writeString(foreign, "staging " + "0123456789abcdef".repeat(4) + " sangam.example\n", StandardCharsets.UTF_8);
        String[] signer = ed25519();

        Map<String, Object> env = new LinkedHashMap<>();
        for (String name : serverEnvironment().keySet()) {
            env.put(name, testValue(name, secrets, foreign, signer));
        }
        StandardEnvironment environment = new StandardEnvironment();
        // the "-systemEnvironment" suffix gives the source Spring Boot's env-name mapping, as on the container
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("demo-fragment-systemEnvironment", env));

        String[] harness = {
                "--sangam.security.bootstrap.enabled=false", "--sangam.security.dev-token.enabled=false",
                "--sangam.security.platform-issuer=", "--sangam.debug.enabled=false",
                "--server.port=0", "--management.server.port=0", "--sangam.engine.allow-multiple-engines-per-jvm=true"};
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SangamServerApplication.class)
                .environment(environment).profiles("h2", "oidc").run(harness)) {
            assertTrue(ctx.isActive());
            assertEquals("true", ctx.getEnvironment().getProperty("sangam.security.production"));
            assertEquals("k1", ctx.getEnvironment().getProperty("sangam.security.kek.current-id"));
            assertEquals("gl-demo-test", ctx.getEnvironment().getProperty("sangam.ledger.gl-connector"));
            assertEquals(foreign.toString(), ctx.getEnvironment().getProperty("sangam.keys.foreign-fingerprints"));
        }
    }

    /** A test value per env name of the fragment; an unknown name fails, so every new name is looked at. */
    private static String testValue(String name, Path secrets, Path foreign, String[] signer) {
        return switch (name) {
            case "TZ" -> "UTC";
            case "SPRING_PROFILES_ACTIVE" -> "h2,oidc";
            case "SANGAM_SECURITY_PRODUCTION" -> "true";
            case "SANGAM_DB_URL" -> "jdbc:h2:mem:demo-fragment;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;MODE=PostgreSQL";
            case "SANGAM_DB_USER" -> "sangam_app";
            case "SANGAM_DB_PASSWORD" -> random(24);
            case "SANGAM_LIQUIBASE_MODE" -> "startup";
            case "SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE" -> "system:platform";
            case "SANGAM_TENANT_DEFAULT", "SANGAM_COMPLIANCE_TENANTS", "SANGAM_SECURITY_JITPROVISIONINGTENANTS" -> "GOI.FIN";
            case "SANGAM_COMPLIANCE_JURISDICTIONS", "SANGAM_COMPLIANCE_LOG_ARCHIVE_COUNTRY" -> "IN";
            case "SANGAM_COMPLIANCE_ORGANISATION" -> "OneIFMS demo";
            case "SANGAM_SECURITY_KEK_KEYS_K1", "SANGAM_REGISTRY_VAULT_HMACPEPPER" -> random(32);
            case "SANGAM_SECURITY_KEK_CURRENTID" -> "k1";
            case "SANGAM_REGISTRY_SIGNER_PRIVATEKEY" -> signer[0];
            case "SANGAM_REGISTRY_SIGNER_PUBLICKEY" -> signer[1];
            case "SANGAM_SECRETS_PROVIDERS" -> "file";
            case "SANGAM_SECRETS_FILE_ROOT" -> secrets.toString();
            case "SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF" -> "secret://compliance/principal-key";
            case "SANGAM_AUDIT_CHECKPOINT_PRIVATEKEYREF" -> "secret://audit/checkpoint-private";
            case "SANGAM_AUDIT_CHECKPOINT_PUBLICKEYREF" -> "secret://audit/checkpoint-public";
            case "SANGAM_KEYS_ENVIRONMENT" -> "demo-ifms";
            case "SANGAM_KEYS_FOREIGN_FINGERPRINTS" -> foreign.toString();   // OPEN (b): test value
            case "SANGAM_KEYS_BOOTSTRAP" -> "false";
            case "SANGAM_LEDGER_GLCONNECTOR" -> "gl-demo-test";                // OPEN (c): test value
            case "SANGAM_MALWARE_SCANNER" -> "none";
            case "SANGAM_MALWARE_PRODUCTIONWAIVER" -> "demo-fragment start-up test: no scanner"; // OPEN (a)
            case "SANGAM_OIDC_ISSUER" -> "https://idp.example/realms/oneifms";
            case "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" ->
                    "https://idp.example/realms/oneifms/protocol/openid-connect/certs";
            case "SANGAM_SECURITY_OIDC_AUDIENCE" -> "sangam-server";
            case "SANGAM_SECURITY_OIDC_TENANT_CLAIM" -> "tenant";
            case "SANGAM_SECURITY_OIDC_ROLES_CLAIM" -> "roles";
            case "SANGAM_SECURITY_OIDC_SEATS_CLAIM" -> "seats";
            case "SANGAM_SECURITY_PLATFORM_ISSUER", "SANGAM_CORS_ORIGINS", "SANGAM_CONNECTORS_PAYGOV_BASE_URL",
                 "SANGAM_CONNECTORS_IFMS_BASE_URL", "SANGAM_CONNECTORS_PFMS_DIRECTORY" -> "";
            case "SANGAM_TRUSTED_PROXIES" -> "172.29.250.0/24,173.245.48.0/20,2400:cb00::/32";
            case "SANGAM_CLIENT_IP_HEADERS" -> "CF-Connecting-IP,X-Forwarded-For";
            case "SANGAM_INTEGRATION_SIMULATORS_ENABLED", "SANGAM_OTLP_ENABLED" -> "false";
            case "JAVA_TOOL_OPTIONS" -> "";
            default -> throw new AssertionError("no test value for the fragment's env name " + name
                    + ": add one here (and a binding check if it is a production setting)");
        };
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    private static String[] ed25519() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new String[] {Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())};
    }

    /** The secrets root as generate-demo-keys.sh lays it out: exact bytes, no trailing line break (SL-65). */
    private static Path secretsRoot() throws Exception {
        Path root = Files.createTempDirectory("demo-fragment-secrets");
        Path audit = Files.createDirectories(root.resolve("audit"));
        String[] checkpoint = ed25519();
        Files.writeString(audit.resolve("checkpoint-private"), checkpoint[0], StandardCharsets.UTF_8);
        Files.writeString(audit.resolve("checkpoint-public"), checkpoint[1], StandardCharsets.UTF_8);
        Path compliance = Files.createDirectories(root.resolve("compliance"));
        Files.writeString(compliance.resolve("principal-key"), random(32), StandardCharsets.UTF_8);
        return root;
    }
}
