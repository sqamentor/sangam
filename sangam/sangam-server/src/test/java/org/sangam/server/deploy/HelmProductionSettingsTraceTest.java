// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SG-38: traces, in the Helm chart text, how the production settings reach the server JVM. The repository has no
 * helm-unittest set-up and no helm binary in the unit-test build, so the chart text is checked here; ci-build.yml
 * (deploy-lint) renders the chart with the real helm and checks the same path on the rendered manifests.
 *
 * <ol>
 *   <li>{@code database.existingSecret} is required and is the only source of {@code SANGAM_DB_USER} /
 *       {@code SANGAM_DB_PASSWORD} (secretKeyRef, keys {@code database.usernameKey}/{@code passwordKey}).</li>
 *   <li>That env block is used by the server Deployment and by the migration Job.</li>
 *   <li>The Deployment loads the {@code -server-env} ConfigMap, which sets {@code SANGAM_SECURITY_PRODUCTION} from
 *       {@code server.production}, whose chart default is {@code true}.</li>
 *   <li>The Spring profile is the database type, and every database profile binds the datasource credentials to
 *       {@code SANGAM_DB_USER}/{@code SANGAM_DB_PASSWORD}.</li>
 * </ol>
 */
class HelmProductionSettingsTraceTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final List<String> HELM_DB_TYPES = List.of("postgres", "oracle", "sqlserver", "mysql");

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    /**
     * The body of a {@code define} block of _helpers.tpl, up to the first {@code {{- end -}}} line (the chart closes
     * inner {@code if}/{@code with} blocks with {@code {{- end }}}, so for the blocks read here that is the define's own
     * end, or a later point of it).
     */
    private static String define(String helpers, String name) {
        Matcher m = Pattern.compile("\\{\\{- define \"" + Pattern.quote(name) + "\" -}}(.*?)\\n\\{\\{- end -}}",
                Pattern.DOTALL).matcher(helpers);
        assertTrue(m.find(), "_helpers.tpl defines " + name);
        return m.group(1);
    }

    @Test
    void existingSecretIsRequiredAndIsTheOnlySourceOfTheDatabaseCredentials() throws IOException {
        String helpers = read(CHART.resolve("templates/_helpers.tpl"));
        String secret = define(helpers, "sangam.db.secret");
        assertTrue(secret.contains("required \"database.existingSecret is required")
                && secret.contains(".Values.database.existingSecret"), "sangam.db.secret requires database.existingSecret");

        String env = define(helpers, "sangam.server.env");
        for (String[] v : new String[][] {{"SANGAM_DB_USER", "usernameKey"}, {"SANGAM_DB_PASSWORD", "passwordKey"}}) {
            Pattern ref = Pattern.compile("- name: " + v[0] + "\\n\\s+valueFrom:\\n\\s+secretKeyRef:\\n"
                    + "\\s+name: \\{\\{ include \"sangam\\.db\\.secret\" \\. }}\\n"
                    + "\\s+key: \\{\\{ \\.Values\\.database\\." + v[1] + " }}\\n");
            assertTrue(ref.matcher(env).find(), v[0] + " comes from secretKeyRef(database.existingSecret, " + v[1] + ")");
            assertEquals(1, count(env, "- name: " + v[0] + "\n"), v[0] + " is set exactly once");
        }

        String values = read(CHART.resolve("values.yaml"));
        assertTrue(Pattern.compile("(?m)^database:\\n(?:[ #].*\\n|\\n)*?  usernameKey: username\\n  passwordKey: password$")
                .matcher(values).find(), "chart default secret keys are username/password");
    }

    @Test
    void serverDeploymentAndMigrationJobUseTheSameEnvBlock() throws IOException {
        for (String t : List.of("server-deployment.yaml", "liquibase-job.yaml")) {
            String text = read(CHART.resolve("templates").resolve(t));
            assertTrue(text.contains("{{- include \"sangam.server.env\" . | nindent 12 }}"), t + " includes sangam.server.env");
        }
    }

    @Test
    void productionPostureReachesThePodFromTheConfigMap() throws IOException {
        String deployment = read(CHART.resolve("templates/server-deployment.yaml"));
        assertTrue(Pattern.compile("envFrom:\\n\\s+- configMapRef:\\n\\s+name: \\{\\{ \\$fullname }}-server-env\\n")
                .matcher(deployment).find(), "the server container loads the -server-env ConfigMap");

        String configMap = read(CHART.resolve("templates/server-configmap.yaml"));
        assertTrue(configMap.contains("name: {{ include \"sangam.fullname\" . }}-server-env"), "ConfigMap name");
        assertTrue(configMap.contains("SANGAM_SECURITY_PRODUCTION: {{ .Values.server.production | quote }}"),
                "SANGAM_SECURITY_PRODUCTION (relaxed binding: sangam.security.production) comes from server.production");

        String values = read(CHART.resolve("values.yaml"));
        assertTrue(Pattern.compile("(?m)^server:\\n(?:[ #].*\\n|\\n)*?  production: true$").matcher(values).find(),
                "server.production defaults to true");
    }

    @Test
    void everyHelmDatabaseProfileBindsTheSecretCredentials() throws IOException {
        String helpers = read(CHART.resolve("templates/_helpers.tpl"));
        assertTrue(define(helpers, "sangam.server.env").contains(
                "value: {{ trimSuffix \",\" (printf \"%s,oidc,%s\" (include \"sangam.db.type\" .) .Values.server.springProfiles) | quote }}"),
                "SPRING_PROFILES_ACTIVE starts with the database type");
        for (String type : HELM_DB_TYPES) {
            assertTrue(define(helpers, "sangam.db.type").contains('"' + type + '"'), "chart accepts " + type);
            String profile = read(RESOURCES.resolve("application-" + type + ".yaml"));
            assertTrue(Pattern.compile("(?m)^\\s+username: \\$\\{SANGAM_DB_USER[:}]").matcher(profile).find(),
                    "application-" + type + ".yaml binds spring.datasource.username to SANGAM_DB_USER");
            assertTrue(Pattern.compile("(?m)^\\s+password: \\$\\{SANGAM_DB_PASSWORD[:}]").matcher(profile).find(),
                    "application-" + type + ".yaml binds spring.datasource.password to SANGAM_DB_PASSWORD");
        }
    }

    /**
     * SL-59: the chart's pool acquire wait equals the server's decided default (2 s; the Helm key
     * SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT outranks application.yaml, so a different chart default would silently
     * replace it), and the optional socket and Retry-After bounds are rendered only when set.
     */
    @Test
    void sl59DatabaseWaitBoundsReachThePod() throws IOException {
        String application = read(RESOURCES.resolve("application.yaml"));
        Matcher server = Pattern.compile("connection-timeout: \\$\\{SANGAM_DB_CONNECTION_TIMEOUT_MS:(\\d+)}").matcher(application);
        assertTrue(server.find(), "application.yaml pool acquire default");
        Matcher chart = Pattern.compile("(?m)^    connectionTimeoutMs: (\\d+)$").matcher(read(CHART.resolve("values.yaml")));
        assertTrue(chart.find(), "values.yaml database.pool.connectionTimeoutMs");
        assertEquals(server.group(1), chart.group(1), "chart default = server default (SL-59, SCALE-ARCHITECTURE 1.6)");

        String configMap = read(CHART.resolve("templates/server-configmap.yaml"));
        assertTrue(configMap.contains("SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT: {{ .Values.database.pool.connectionTimeoutMs | quote }}"));
        for (String[] v : new String[][] {{"networkTimeout", "SANGAM_DB_NETWORK_TIMEOUT"},
                {"unavailableRetryAfter", "SANGAM_DB_UNAVAILABLE_RETRY_AFTER"}}) {
            assertTrue(Pattern.compile("\\{\\{- with \\.Values\\.database\\." + v[0] + " }}\\n(?:\\s*#.*\\n)?\\s*" + v[1]
                    + ": \\{\\{ \\. \\| quote }}\\n\\s*\\{\\{- end }}").matcher(configMap).find(), v[1] + " only when set");
            assertTrue(application.contains("${" + v[1] + ":"), v[1] + " is read by application.yaml");
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
