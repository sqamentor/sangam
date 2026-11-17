// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * DEC-153 (owner decision 153, W4-C): the PDF validator accepts only the bearer token of its token file over TLS, and
 * only the server can reach it. The code reads the settings (PdfValidatorMain, RemotePdfValidator); this text trace, in
 * the style of {@link HelmLeafPoolsAndEngineTracingTraceTest}, proves the deploy files keep every part of it:
 * <ul>
 *   <li>compose: the sidecar gets transport tls, its token file, key store and key store password file from a read-only
 *       secrets volume, publishes no port and sits only on the {@code sidecars} network, which is internal; the demo
 *       secrets script makes exactly those files and the server's trust store and token copy; the sidecars override
 *       gives the server https, tls, its token file, the trust store and the sidecars network;</li>
 *   <li>Helm: the sidecar Deployment requires its existing Secret and mounts token, key store and password; its
 *       NetworkPolicy allows ingress from this release's server pods only and no egress; the server gets the token file,
 *       the trust store (a required Secret) and egress to the sidecar; transport is only tls or mesh.</li>
 * </ul>
 * Each env name is also checked against the code that reads it, so a rename on either side fails here.
 */
class PdfValidatorDeployTraceTest {

    private static final Path DEPLOY = Path.of("..", "deploy");
    private static final Path COMPOSE = DEPLOY.resolve("compose");
    private static final Path CHART = DEPLOY.resolve("helm").resolve("sangam");
    private static final Path SIDECAR_MAIN = Path.of("..", "sangam-pdf-validator", "src", "main", "java", "org", "sangam", "pdfvalidator",
            "PdfValidatorMain.java");
    private static final Path CLIENT = Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam", "integration", "document",
            "pdf", "remote", "RemotePdfValidator.java");

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /** The lines of one top-level compose service (indent 2), up to the next service or comment at indent 2. */
    private static String service(String compose, String name) {
        int start = compose.indexOf("\n  " + name + ":\n");
        assertTrue(start >= 0, "compose service " + name);
        Matcher next = Pattern.compile("\n  [#a-z]").matcher(compose);
        int end = next.find(start + name.length() + 4) ? next.start() : compose.length();
        return compose.substring(start, end);
    }

    private static void has(String text, String part, String what) {
        assertTrue(text.contains(part), what + ": expected " + part);
    }

    @Test
    void theEnvNamesAreTheOnesTheCodeReads() throws IOException {
        String sidecar = read(SIDECAR_MAIN);
        for (String env : List.of("SANGAM_PDF_VALIDATOR_TRANSPORT", "SANGAM_PDF_VALIDATOR_TOKEN_FILE", "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE",
                "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE_PASSWORD_FILE")) {
            has(sidecar, "= \"" + env + "\";", "PdfValidatorMain reads");
        }
        String client = read(CLIENT);
        for (String env : List.of("SANGAM_PDF_VALIDATOR_URL", "SANGAM_PDF_VALIDATOR_TRANSPORT", "SANGAM_PDF_VALIDATOR_TOKEN_FILE")) {
            has(client, "= \"" + env + "\";", "RemotePdfValidator reads");
        }
    }

    @Test
    void composeSidecarHasTokenTlsAndOnlyTheInternalNetwork() throws IOException {
        String compose = read(COMPOSE.resolve("docker-compose.yml"));
        String s = service(compose, "pdf-validator");
        has(s, "SANGAM_PDF_VALIDATOR_TRANSPORT: tls", "pdf-validator");
        has(s, "SANGAM_PDF_VALIDATOR_TOKEN_FILE: /run/sangam-secrets/token", "pdf-validator");
        has(s, "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE: /run/sangam-secrets/keystore.p12", "pdf-validator");
        has(s, "SANGAM_PDF_VALIDATOR_TLS_KEYSTORE_PASSWORD_FILE: /run/sangam-secrets/keystore-password", "pdf-validator");
        has(s, "- pdf-validator-secrets:/run/sangam-secrets:ro", "pdf-validator secrets volume, read-only");
        has(s, "networks: [sidecars]", "pdf-validator only on the sidecars network");
        has(s, "read_only: true", "pdf-validator");
        has(s, "cap_drop: [ALL]", "pdf-validator");
        assertFalse(Pattern.compile("(?m)^    ports:").matcher(s).find(), "pdf-validator publishes no port");
        has(s, "condition: service_completed_successfully", "pdf-validator waits for its secrets");
        Matcher net = Pattern.compile("(?m)^  sidecars:\n    name: sangam-sidecars\n    internal: true$").matcher(compose);
        assertTrue(net.find(), "the sidecars network is internal (no route out)");
        has(compose, "\n  pdf-validator-secrets:\n", "named secrets volume");
        has(compose, "\n  sidecar-client-secrets:\n", "named client secrets volume");
    }

    @Test
    void composeDemoSecretsAreTheFilesTheSidecarAndServerUse() throws IOException {
        String init = read(COMPOSE.resolve("sidecar-secrets-init.sh"));
        has(init, "make_sidecar pdf-validator", "secrets init");
        for (String f : List.of("$dir/token", "$dir/keystore-password", "$dir/keystore.p12")) {
            has(init, f, "sidecar secret file");
        }
        has(init, "cp \"$dir/token\" \"$CLIENT/$name.token\"", "server copy of the token");
        has(init, "mv -f \"$trust\" \"$CLIENT/truststore.p12\"", "server trust store");
        has(init, "-ext \"SAN=dns:$name\"", "certificate for the service name");
        String s = service(read(COMPOSE.resolve("docker-compose.yml")), "sidecar-secrets-init");
        has(s, "network_mode: none", "secrets init has no network");
        has(s, "- pdf-validator-secrets:/secrets/pdf-validator", "secrets init writes the sidecar volume");
        has(s, "- sidecar-client-secrets:/secrets/client", "secrets init writes the client volume");
    }

    @Test
    void composeServerUsesHttpsItsTokenAndTheTrustStore() throws IOException {
        String o = read(COMPOSE.resolve("docker-compose.sidecars.yml"));
        has(o, "SANGAM_PDF_VALIDATOR_URL: ${SANGAM_PDF_VALIDATOR_URL-https://pdf-validator:8085}", "server URL is https");
        has(o, "SANGAM_PDF_VALIDATOR_TRANSPORT: tls", "server transport");
        has(o, "SANGAM_PDF_VALIDATOR_TOKEN_FILE: /run/sangam-sidecars/pdf-validator.token", "server token file");
        has(o, "-Djavax.net.ssl.trustStore=/run/sangam-sidecars/truststore.p12", "server trust store");
        has(o, "- sidecar-client-secrets:/run/sangam-sidecars:ro", "client secrets read-only");
        has(o, "    sidecars: {}", "server joins the sidecars network");
        for (String server : List.of("server-postgres", "server-oracle", "server-sqlserver", "server-mysql")) {
            has(o, "  " + server + ": *sidecar-client", "every server variant is wired");
        }
    }

    @Test
    void helmSidecarRequiresItsSecretAndHasANetworkPolicyWithServerIngressOnlyAndNoEgress() throws IOException {
        String t = read(CHART.resolve("templates").resolve("sidecars.yaml"));
        has(t, "{{- $secret := required (printf \"%s.existingSecret is required", "the sidecar Secret is required");
        has(t, "- name: {{ $prefix }}_TOKEN_FILE\n              value: /opt/sangam/sidecar-secrets/token", "token file env");
        has(t, "- name: {{ $prefix }}_TLS_KEYSTORE\n              value: /opt/sangam/sidecar-secrets/keystore.p12", "key store env");
        has(t, "- name: {{ $prefix }}_TLS_KEYSTORE_PASSWORD_FILE\n              value: /opt/sangam/sidecar-secrets/keystore-password",
                "key store password env");
        has(t, "secretName: {{ $secret }}", "secret volume");
        for (String key : List.of("{ key: token, path: token }", "{ key: keystore.p12, path: keystore.p12 }",
                "{ key: keystore-password, path: keystore-password }")) {
            has(t, key, "secret item");
        }
        has(t, "automountServiceAccountToken: false", "sidecar");
        has(t, "{{- include \"sangam.sidecars.checkTransport\" $v }}", "transport checked");
        int np = t.indexOf("kind: NetworkPolicy");
        assertTrue(np >= 0, "sidecar NetworkPolicy");
        String policy = t.substring(np);
        has(policy, "policyTypes: [Ingress, Egress]", "NetworkPolicy");
        has(policy, "(dict \"ctx\" $root \"component\" \"server\")", "ingress from the server pods only");
        has(policy, "- { protocol: TCP, port: {{ $v.port }} }", "ingress on the sidecar port only");
        has(policy, "  egress: []", "no egress");
    }

    @Test
    void helmServerGetsTheTokenTheTrustStoreAndEgressToTheSidecar() throws IOException {
        String h = read(CHART.resolve("templates").resolve("_helpers.tpl"));
        has(h, "pdfValidator:pdf-validator:SANGAM_PDF_VALIDATOR", "sidecar list");
        has(h, "{{- if not (has .transport (list \"tls\" \"mesh\")) -}}", "transport is tls or mesh only");
        has(h, "- name: {{ index $f 2 }}_TOKEN_FILE", "server token file env");
        has(h, "required \"sidecarTrustStore.existingSecret is required when a sidecar uses transport tls", "trust store Secret required");
        has(h, "-Djavax.net.ssl.trustStore=/opt/sangam/sidecars/truststore/truststore.p12", "server trust store");
        has(h, "(ternary \"https\" \"http\" (eq $v.transport \"tls\"))", "https with tls");
        String d = read(CHART.resolve("templates").resolve("server-deployment.yaml"));
        for (String part : List.of("sangam.sidecars.serverEnv", "sangam.sidecars.serverMounts", "sangam.sidecars.serverVolumes")) {
            has(d, "include \"" + part + "\"", "server Deployment");
        }
        String n = read(CHART.resolve("templates").resolve("networkpolicy.yaml"));
        has(n, "(include \"sangam.sidecars.list\" .)", "server egress to each enabled sidecar");
        has(n, "(dict \"ctx\" $ \"component\" (index $f 1))", "server egress selects the sidecar pods");
        String v = read(CHART.resolve("values.yaml"));
        assertTrue(Pattern.compile("(?m)^pdfValidator:\n  enabled: false\n").matcher(v).find(), "off by default");
        assertTrue(Pattern.compile("(?m)^  transport: tls ").matcher(v.substring(v.indexOf("\npdfValidator:"))).find(), "tls by default");
        String schema = read(CHART.resolve("values.schema.json"));
        has(schema, "\"transport\": { \"type\": \"string\", \"enum\": [\"tls\", \"mesh\"] }", "schema");
        has(schema, "\"pdfValidator\": { \"$ref\": \"#/definitions/sidecar\" }", "schema");
    }
}
