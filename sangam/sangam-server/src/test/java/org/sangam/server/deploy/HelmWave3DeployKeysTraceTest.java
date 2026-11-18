// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3 deploy keys, traced from the server key to the chart, the schema and compose (text trace in the style of
 * {@link HelmLeafPoolsAndEngineTracingTraceTest}; schema cases through {@link HelmValuesSchemaTest.MiniSchema}):
 * M-082 malware scanning and the optional ClamAV service (owner decision 71), M-075 secret providers, M-063 state
 * roll-up tier, M-044 UI trace links and M-061 script worker (owner decision 78).
 */
class HelmWave3DeployKeysTraceTest {

    private static final Path DEPLOY = Path.of("..", "deploy");
    private static final Path CHART = DEPLOY.resolve("helm/sangam");
    private static final Path SERVER_CONFIG = Path.of("src", "main", "java", "org", "sangam", "server", "config");

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        Object v = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

    private static List<String> schemaErrors(String overrideYaml) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = new ObjectMapper().readValue(read(CHART.resolve("values.schema.json")), Map.class);
        Map<String, Object> values = HelmValuesSchemaTest.coalesce(yaml(read(CHART.resolve("values.yaml"))), yaml(overrideYaml));
        return new HelmValuesSchemaTest.MiniSchema(schema).validate(values);
    }

    // ------------------------------------------------------------------------------------------------ M-082

    @Test
    void malwareKeysGoFromValuesToTheServerProperties() throws IOException {
        String cm = read(CHART.resolve("templates/server-configmap.yaml"));
        String wiring = read(SERVER_CONFIG.resolve("MalwareScanWiring.java"));
        // env name -> the property the server reads (relaxed binding: dots and dashes become underscores)
        Map<String, String> keys = Map.of(
                "SANGAM_MALWARE_SCANNER", "sangam.malware.scanner",
                "SANGAM_MALWARE_FAIL_CLOSED", "sangam.malware.fail-closed",
                "SANGAM_MALWARE_TIMEOUT", "sangam.malware.timeout",
                "SANGAM_MALWARE_MAX_BYTES", "sangam.malware.max-bytes",
                "SANGAM_MALWARE_CLAMD_HOST", "sangam.malware.clamd.host",
                "SANGAM_MALWARE_CLAMD_PORT", "sangam.malware.clamd.port",
                "SANGAM_MALWARE_ICAP_URI", "sangam.malware.icap.uri");
        for (Map.Entry<String, String> k : keys.entrySet()) {
            assertTrue(cm.contains("  " + k.getKey() + ": "), k.getKey() + " rendered by the server ConfigMap");
            assertTrue(wiring.contains("\"" + k.getValue() + "\""), k.getValue() + " read by MalwareScanWiring");
            assertEquals(k.getKey(), k.getValue().toUpperCase().replace('.', '_').replace('-', '_'), "relaxed binding");
        }
        assertTrue(cm.contains("required \"malware.icap.uri is required with malware.scanner=icap\""), "icap needs a uri");
        assertTrue(cm.contains("printf \"%s-clamav\" (include \"sangam.fullname\" .)"),
                "scanner clamd without a host uses this release's ClamAV service");
    }

    @Test
    void malwareDefaultsEqualTheServerDefaults() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> mw = (Map<String, Object>) yaml(read(CHART.resolve("values.yaml"))).get("malware");
        String scanning = read(Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam", "integration",
                "malware", "MalwareScanning.java"));
        String wiring = read(SERVER_CONFIG.resolve("MalwareScanWiring.java"));
        assertEquals("none", mw.get("scanner"), "no scanner by default (server default none)");
        assertTrue(wiring.contains("env.getProperty(\"sangam.malware.scanner\", \"none\")"), "server default none");
        assertEquals(Boolean.TRUE, mw.get("failClosed"), "fail closed by default (server default true)");
        assertTrue(wiring.contains("env.getProperty(\"sangam.malware.fail-closed\", Boolean.class, true)"));
        assertEquals("30s", mw.get("timeout"));
        assertEquals("26214400", mw.get("maxBytes"), "25 MiB, kept as a string so helm prints the digits");
        assertEquals(3310, ((Map<?, ?>) mw.get("clamd")).get("port"));
        assertTrue(scanning.contains("DEFAULT_TIMEOUT = Duration.ofSeconds(30)"), "server timeout default 30 s");
        assertTrue(scanning.contains("DEFAULT_MAX_BYTES = 25L * 1024 * 1024"), "server max-bytes default 25 MiB");
        assertTrue(scanning.contains("DEFAULT_CLAMD_PORT = 3310"), "server clamd port default 3310");
    }

    @Test
    void theClamAvServiceIsOptionalPinnedAndReachableOnlyFromTheServer() throws IOException {
        Map<String, Object> values = yaml(read(CHART.resolve("values.yaml")));
        @SuppressWarnings("unchecked")
        Map<String, Object> clamav = (Map<String, Object>) ((Map<String, Object>) values.get("malware")).get("clamav");
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) clamav.get("image");
        assertEquals(Boolean.FALSE, clamav.get("enabled"), "off by default");
        assertEquals("clamav/clamav", image.get("repository"), "owner decision 71 names clamav/clamav");
        String tag = String.valueOf(image.get("tag"));
        assertTrue(tag.matches("\\d+\\.\\d+\\.\\d+"), "pinned tag, got " + tag);
        // RM-33 (UP-L3): the tag is pinned by its registry digest too, and compose names the same tag@digest
        String digest = String.valueOf(image.get("digest"));
        assertTrue(digest.matches("sha256:[0-9a-f]{64}"), "pinned digest, got " + digest);

        String template = read(CHART.resolve("templates/clamav.yaml"));
        assertTrue(template.startsWith("{{- /* SPDX-License-Identifier: Apache-2.0 */ -}}"), "SPDX header");
        assertTrue(template.contains("{{- if .Values.malware.clamav.enabled }}"), "rendered only when enabled");
        assertFalse(template.contains("kind: Ingress"), "never exposed outside the cluster");
        assertTrue(template.contains("(dict \"ctx\" . \"component\" \"server\")"), "clamd admits the server pods only");
        assertTrue(read(CHART.resolve("templates/networkpolicy.yaml")).contains("{{- if .Values.malware.clamav.enabled }}"),
                "the server egress reaches clamd only when the service exists");

        String compose = read(DEPLOY.resolve("compose/docker-compose.yml"));
        assertTrue(compose.contains("    image: clamav/clamav:" + tag + "@" + digest + "\n"), "compose uses the same pinned image");
        assertTrue(compose.contains("    profiles: [malware]\n"), "compose ClamAV only with --profile malware");
        assertFalse(Pattern.compile("(?m)^\\s+- \"?[0-9.:]*3310:3310").matcher(compose).find(), "clamd not published");
        assertTrue(compose.contains("SANGAM_MALWARE_SCANNER: ${SANGAM_MALWARE_SCANNER:-none}"), "compose default none");

        assertTrue(schemaErrors("malware: { clamav: { image: { tag: latest } } }").stream().anyMatch(e -> e.contains("tag")),
                "a floating tag is refused");
        assertFalse(schemaErrors("malware: { scanner: icap }").isEmpty(), "icap without a uri is refused");
        assertEquals(List.of(), schemaErrors("malware: { scanner: icap, icap: { uri: 'icap://av.internal:1344/avscan' } }"));
        assertEquals(List.of(), schemaErrors("malware: { scanner: clamd, clamav: { enabled: true } }"));
    }

    // ------------------------------------------------------------------------------------------------ M-075

    @Test
    void secretProviderKeysAreRenderedOnlyWhenProvidersIsSet() throws IOException {
        String cm = read(CHART.resolve("templates/server-configmap.yaml"));
        String wiring = read(SERVER_CONFIG.resolve("SecretsWiring.java"));
        int with = cm.indexOf("{{- with $sec.providers }}");
        assertTrue(with > 0, "SANGAM_SECRETS_* sit inside with providers (an empty value would still switch the bean on)");
        Matcher m = Pattern.compile("SANGAM_SECRETS_(VAULT|AWS|AZURE|GCP)_\\{\\{ \\$k }}|SANGAM_SECRETS_[A-Z_]+").matcher(cm);
        while (m.find()) {
            assertTrue(m.start() > with, m.group() + " rendered only with providers set");
        }
        for (String key : List.of("providers", "cache-ttl", "file.root", "vault.url", "vault.mount", "vault.path-prefix",
                "vault.namespace", "vault.token-ref", "vault.allow-private", "aws.region", "aws.endpoint", "aws.name-prefix",
                "aws.access-key-ref", "aws.secret-key-ref", "aws.session-token-ref", "azure.vault-url", "azure.tenant-id",
                "azure.client-id", "azure.client-secret-ref", "azure.authority", "gcp.project",
                "gcp.service-account-key-ref", "gcp.name-prefix", "gcp.endpoint")) {
            assertTrue(wiring.contains("\"" + key + "\""), "SecretsWiring reads sangam.secrets." + key);
            String env = key.toUpperCase().replace('.', '_').replace('-', '_');
            String group = env.contains("_") ? env.substring(0, env.indexOf('_')) : env;
            String rest = env.substring(group.length()).replaceFirst("^_", "");
            boolean rendered = cm.contains("SANGAM_SECRETS_" + env + ":")
                    || (List.of("VAULT", "AWS", "AZURE", "GCP").contains(group) && cm.contains("\"" + rest + "\" $sec."));
            assertTrue(rendered, "SANGAM_SECRETS_" + env + " rendered by the ConfigMap");
        }
        assertFalse(read(DEPLOY.resolve("compose/docker-compose.yml")).contains("SANGAM_SECRETS_PROVIDERS:"),
                "compose never sets an (empty) providers key");
        String deployment = read(CHART.resolve("templates/server-deployment.yaml"));
        assertTrue(deployment.contains("{{- with .Values.secrets.file.existingSecret }}"), "file provider Secret mounted");
        assertTrue(deployment.contains("mountPath: {{ required \"secrets.file.root is required with secrets.file.existingSecret\""
                + " .Values.secrets.file.root }}"), "mounted at secrets.file.root");
    }

    @Test
    void secretReferencesAreNeverValues() throws IOException {
        assertEquals(List.of(), schemaErrors("secrets: { providers: 'file,vault,env', vault: { url: 'https://vault.internal',"
                + " tokenRef: 'secret://vault/token' } }"));
        assertFalse(schemaErrors("secrets: { vault: { tokenRef: 's.abcdef' } }").isEmpty(), "a token value is refused");
        assertFalse(schemaErrors("secrets: { providers: 'file,keychain' }").isEmpty(), "unknown provider refused");
    }

    // ------------------------------------------------------------------------------------------------ M-063

    @Test
    void stateRollupInstancesUseTheRollupKeys() throws IOException {
        String app = read(Path.of("..", "sangam-rollup", "src", "main", "resources", "application.yaml"));
        String tpl = read(DEPLOY.resolve("helm/sangam-national/templates/rollup.yaml"));
        for (String env : List.of("SANGAM_RU_PRE_AGGREGATE", "SANGAM_RU_SCOPE", "SANGAM_RU_INSTANCE_ID",
                "SANGAM_RU_UPSTREAM_URL", "SANGAM_RU_UPSTREAM_TOKEN")) {
            assertTrue(app.contains("${" + env + ":"), env + " is read by sangam-rollup");
            assertTrue(tpl.contains("name: " + env), env + " rendered for the state instances");
        }
        assertTrue(tpl.contains("{{- range $st := $r.states }}"), "one instance per state");
        assertTrue(tpl.contains("key: upstreamToken"), "the TIER token comes from a Secret");
        @SuppressWarnings("unchecked")
        Map<String, Object> rollup = (Map<String, Object>) yaml(read(DEPLOY.resolve("helm/sangam-national/values.yaml"))).get("rollup");
        assertEquals(List.of(), rollup.get("states"), "default: a single national roll-up");

        String cells = read(DEPLOY.resolve("compose/docker-compose.cells.yml"));
        assertTrue(cells.contains("    profiles: [state-tier]\n"), "compose state tier only with its profile");
        assertTrue(cells.contains("dev-tier-ra-token:TIER"), "national accepts the state's TIER token");
        assertTrue(cells.contains("SANGAM_RU_UPSTREAM_TOKEN: dev-tier-ra-token"), "the state sends with that token");
    }

    // ------------------------------------------------------------------------------------------------ M-044

    @Test
    void theTraceUrlTemplateReachesTheUiConfig() throws IOException {
        String conf = read(DEPLOY.resolve("docker/ui/default.conf.template"));
        assertTrue(conf.contains("\"traceUrlTemplate\":\"${SANGAM_TRACE_URL_TEMPLATE}\""), "served in /config.json");
        assertTrue(read(CHART.resolve("templates/ui.yaml")).contains(
                "- name: SANGAM_TRACE_URL_TEMPLATE\n              value: {{ .Values.ui.traceUrlTemplate | quote }}"), "Helm env");
        assertTrue(read(DEPLOY.resolve("compose/docker-compose.yml")).contains(
                "SANGAM_TRACE_URL_TEMPLATE: ${SANGAM_TRACE_URL_TEMPLATE:-}"), "compose env");
        assertEquals(List.of(), schemaErrors("ui: { traceUrlTemplate: 'https://tracing.example/trace/{traceId}' }"));
        assertFalse(schemaErrors("ui: { traceUrlTemplate: 'javascript:alert(1)' }").isEmpty(), "only http(s)");
    }

    // ------------------------------------------------------------------------------------------------ M-061

    @Test
    void theScriptWorkerHasTheDecision78PrivilegesAndNoDatabaseCredentials() throws IOException {
        String tpl = read(CHART.resolve("templates/script-worker.yaml"));
        assertTrue(tpl.contains("{{- if .Values.scriptWorker.enabled }}"), "off by default");
        assertFalse(tpl.contains("SANGAM_DB_") || tpl.contains("sangam.server.env"), "no database credentials");
        assertTrue(tpl.contains("sangam.io/api-client: \"true\""), "reaches the API as a labelled API client");
        assertTrue(tpl.contains("  ingress: []"), "no ingress");
        @SuppressWarnings("unchecked")
        Map<String, Object> sw = (Map<String, Object>) yaml(read(CHART.resolve("values.yaml"))).get("scriptWorker");
        assertEquals(Boolean.FALSE, sw.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) sw.get("securityContext");
        assertEquals(Map.of("add", List.of("SYS_ADMIN"), "drop", List.of()), sc.get("capabilities"));
        assertEquals(Map.of("type", "Unconfined"), sc.get("seccompProfile"));
        assertEquals(Map.of("type", "Unconfined"), sc.get("appArmorProfile"));

        String compose = read(DEPLOY.resolve("compose/docker-compose.yml"));
        int at = compose.indexOf("\n  script-worker:\n");
        assertTrue(at > 0, "compose service");
        String svc = compose.substring(at, compose.indexOf("\n\n", at));
        for (String s : List.of("profiles: [script-worker]", "cgroup: private", "cap_add: [SYS_ADMIN]",
                "- seccomp=unconfined", "- apparmor=unconfined", "- systempaths=unconfined")) {
            assertTrue(svc.contains(s), "compose script-worker " + s);
        }
        assertFalse(svc.contains("DATASOURCE") || svc.contains("SANGAM_DB_"), "no database credentials in compose");
        assertFalse(schemaErrors("scriptWorker: { enabled: true }").isEmpty(), "enabled needs a credentials Secret");
        assertEquals(List.of(), schemaErrors("scriptWorker: { enabled: true, existingSecret: script-worker }"));
    }
}
