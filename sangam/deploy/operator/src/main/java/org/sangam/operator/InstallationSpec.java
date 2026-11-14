// SPDX-License-Identifier: Apache-2.0
package org.sangam.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validated {@code spec} of a {@code SangamInstallation} (crds/sangaminstallations.yaml). Defaults follow the Helm
 * chart (deploy/helm/sangam/values.yaml), so an installation behaves the same whichever artefact deployed it.
 *
 * @param name        the custom resource's name (at most {@link #MAX_NAME} characters, so derived names stay DNS labels)
 * @param passthrough Kubernetes structures copied as given: {@code env}, {@code envFrom}, {@code resources},
 *                    {@code migrationResources}, {@code imagePullSecrets}
 */
public record InstallationSpec(String namespace, String name, String uid, long generation, String image,
                               String imagePullPolicy, int replicas, Database database, String springProfiles,
                               String javaToolOptions, String outboxDestinations, String configMapName,
                               String keysSecret, State state,
                               String driversClaimName, String serviceAccountName, String restartToken, boolean paused,
                               Migration migration, Passthrough passthrough) {

    public static final int MAX_NAME = 40;
    static final Set<String> DB_TYPES = Set.of("postgres", "oracle", "sqlserver", "mysql");
    static final String DEFAULT_JAVA_OPTIONS = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC"
            + " -Duser.timezone=UTC -Dfile.encoding=UTF-8";
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern DNS_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Environment variables the operator sets itself; {@code spec.env} may not repeat them (server-side apply merges env by name). */
    static final Set<String> MANAGED_ENV = Set.of("SANGAM_DB_URL", "SANGAM_DB_USER", "SANGAM_DB_PASSWORD",
            "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS", "SANGAM_LIQUIBASE_MODE", "SANGAM_INSTANCE_ID",
            "SPRING_CONFIG_ADDITIONAL_LOCATION", "SANGAM_OUTBOX_DESTINATIONS", "SANGAM_JOB_EXECUTOR_ENABLED",
            "SANGAM_COMPLIANCE_SCHEDULE", "TZ", "SANGAM_CONTROL_PLANE_TOKEN", "SANGAM_ROLLUP_TOKEN", "SANGAM_SERVICE_TOKEN");

    /** Database connection; credentials come from an existing Secret only. */
    public record Database(String type, String jdbcUrl, String existingSecret, String usernameKey, String passwordKey) {
    }

    /** Writable state at /var/lib/sangam (compliance log archive, cell exports): an existing claim or one to create. */
    public record State(String claimName, String size, String storageClassName, ArrayNode accessModes) {
    }

    /** R12: how schema changes are applied. {@code job}: one migrate-only Job per image; {@code none}: by the DBA. */
    public record Migration(String mode, int backoffLimit, long activeDeadlineSeconds, long ttlSecondsAfterFinished) {
    }

    /** Kubernetes structures passed through unchanged (validated by the API server). */
    public record Passthrough(ArrayNode env, ArrayNode envFrom, ObjectNode resources, ObjectNode migrationResources,
                              ArrayNode imagePullSecrets) {
    }

    /** A spec the operator refuses; the reason goes into {@code status.message}. */
    public static final class InvalidSpec extends IllegalArgumentException {
        public InvalidSpec(String message) {
            super(message);
        }
    }

    /** Validates the custom resource and applies the defaults. */
    public static InstallationSpec parse(JsonNode cr) {
        JsonNode meta = cr.path("metadata");
        String name = meta.path("name").asText("");
        if (name.isEmpty() || name.length() > MAX_NAME || !DNS_LABEL.matcher(name).matches()) {
            throw new InvalidSpec("metadata.name must be a DNS label of at most " + MAX_NAME + " characters");
        }
        JsonNode s = cr.path("spec");
        String image = text(s, "image", null);
        if (image == null || image.isBlank() || image.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidSpec("spec.image is required (repository:tag or repository@sha256:digest)");
        }
        int replicas = s.path("replicas").asInt(2);
        if (replicas < 1 || replicas > 1000) {
            throw new InvalidSpec("spec.replicas must be between 1 and 1000");
        }
        JsonNode db = s.path("database");
        String type = text(db, "type", "");
        if (!DB_TYPES.contains(type)) {
            throw new InvalidSpec("spec.database.type must be one of postgres, oracle, sqlserver, mysql");
        }
        String jdbcUrl = text(db, "jdbcUrl", "");
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new InvalidSpec("spec.database.jdbcUrl is required and must start with jdbc:");
        }
        Database database = new Database(type, jdbcUrl, dnsName(db, "existingSecret", true, "spec.database.existingSecret"),
                text(db, "usernameKey", "username"), text(db, "passwordKey", "password"));
        JsonNode st = s.path("state");
        ArrayNode modes = st.path("accessModes").isArray() && !st.path("accessModes").isEmpty()
                ? (ArrayNode) st.path("accessModes").deepCopy() : JSON.createArrayNode().add("ReadWriteMany");
        State state = new State(dnsName(st, "claimName", false, "spec.state.claimName"), text(st, "size", "50Gi"),
                text(st, "storageClassName", ""), modes);
        JsonNode m = s.path("migration");
        String mode = text(m, "mode", "job");
        if (!mode.equals("job") && !mode.equals("none")) {
            throw new InvalidSpec("spec.migration.mode must be job or none");
        }
        int backoff = m.path("backoffLimit").asInt(0);
        long deadline = m.path("activeDeadlineSeconds").asLong(1800);
        long ttl = m.path("ttlSecondsAfterFinished").asLong(86400);
        if (backoff < 0 || backoff > 10 || deadline < 60 || ttl < 0) {
            throw new InvalidSpec("spec.migration: backoffLimit 0..10, activeDeadlineSeconds >= 60, ttlSecondsAfterFinished >= 0");
        }
        ArrayNode env = array(s, "env");
        for (JsonNode e : env) {
            String n = e.path("name").asText("");
            if (MANAGED_ENV.contains(n)) {
                throw new InvalidSpec("spec.env must not set " + n + ": the operator sets it from the spec fields");
            }
        }
        Passthrough p = new Passthrough(env, array(s, "envFrom"),
                object(s, "resources", defaults("1", "2Gi", "3Gi")), object(m, "resources", defaults("250m", "768Mi", "1536Mi")),
                array(s, "imagePullSecrets"));
        return new InstallationSpec(meta.path("namespace").asText(""), name, meta.path("uid").asText(""),
                meta.path("generation").asLong(0), image, text(s, "imagePullPolicy", "IfNotPresent"), replicas, database,
                text(s, "springProfiles", ""), text(s, "javaToolOptions", DEFAULT_JAVA_OPTIONS),
                text(s, "outboxDestinations", ""), dnsName(s, "configMapName", false, "spec.configMapName"), dnsName(s, "keysSecret", false, "spec.keysSecret"),
                state, dnsName(s, "driversClaimName", false, "spec.driversClaimName"),
                dnsName(s, "serviceAccountName", false, "spec.serviceAccountName"), text(s, "restartToken", ""),
                s.path("paused").asBoolean(false), new Migration(mode, backoff, deadline, ttl), p);
    }

    /** {@code SPRING_PROFILES_ACTIVE} as the Helm chart sets it: database type, oidc, then the extra profiles. */
    public String springProfilesActive() {
        String extra = springProfiles.isBlank() ? "" : "," + springProfiles.trim();
        return database.type() + ",oidc" + extra;
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText() : fallback;
    }

    private static String dnsName(JsonNode n, String field, boolean required, String label) {
        String v = text(n, field, "");
        if (v.isEmpty()) {
            if (required) {
                throw new InvalidSpec(label + " is required");
            }
            return "";
        }
        if (v.length() > 253 || !DNS_NAME.matcher(v).matches()) {
            throw new InvalidSpec(label + " must be a Kubernetes object name");
        }
        return v;
    }

    private static ArrayNode array(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return JSON.createArrayNode();
        }
        if (!v.isArray()) {
            throw new InvalidSpec(field + " must be a list");
        }
        return (ArrayNode) v.deepCopy();
    }

    private static ObjectNode object(JsonNode n, String field, ObjectNode fallback) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return fallback;
        }
        if (!v.isObject()) {
            throw new InvalidSpec(field + " must be an object");
        }
        return (ObjectNode) v.deepCopy();
    }

    private static ObjectNode defaults(String cpu, String memory, String memoryLimit) {
        ObjectNode r = JSON.createObjectNode();
        r.putObject("requests").put("cpu", cpu).put("memory", memory);
        r.putObject("limits").put("memory", memoryLimit);
        return r;
    }
}
